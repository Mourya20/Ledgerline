# Vaultora Threat Model & Security Architecture

This document defines the engineering threat model for **Vaultora**, an immutable double-entry banking and money movement platform. Written from the perspective of an application security engineer and financial systems auditor, it models realistic attack vectors, operational failure modes, architectural trust boundaries, defense-in-depth guarantees, and residual risks across the Phase 1 implementation and future phases.

---

## 1. System Overview & Trust Boundaries

```
[ UNTRUSTED CLIENT DOMAIN ]
  Web Browser / HTTP Client / Attacker
          │
          │  HTTPS / Session Cookie / CSRF Token / JSON
          ▼
┌────────────────────────────────────────────────────────────────────────┐
│ TRUST BOUNDARY 1: HTTP INGRESS & EDGE CONTROLS                        │
│ - Origin verification & CORS (`CORS_ALLOWED_ORIGINS`)                 │
│ - CSRF Token validation (`CsrfTokenRequestAttributeHandler`)          │
│ - In-memory IP/Email Rate Limiter (`LoginRateLimiter`)                 │
│ - HTTP Strict Session Cookies (`SameSite`, `HttpOnly`, `Secure`)       │
└────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────────────────────┐
│ TRUST BOUNDARY 2: SPRING SECURITY & IDENTITY                           │
│ - Session fixation protection (`SessionFixationProtectionStrategy`)    │
│ - BCrypt password hashing with strict UTF-8 72-byte pre-validation     │
│ - Role-Based Access Control (`CUSTOMER`, `ADMIN`, `AUDITOR`)           │
│ - Explicit read-only enforcement on audit logs (`AUDITOR` vs `ADMIN`) │
│ - Disabled user session revocation (`UserDetailsService` / checks)     │
└────────────────────────────────────────────────────────────────────────┘
          │
          ▼
┌────────────────────────────────────────────────────────────────────────┐
│ TRUST BOUNDARY 3: APPLICATION SERVICE & DOMAIN LAYER                  │
│ - Request DTO validation (`@Valid`, `@MaxUtf8Bytes`, Bean Validation)  │
│ - Idempotency key tracking (Phase 2)                                  │
│ - Business logic validation & currency consistency checks             │
│ - Asynchronous security audit event dispatcher (`AuditService`)        │
│ - Persistence context balance reloading (`entityManager.refresh`)      │
└────────────────────────────────────────────────────────────────────────┘
          │
          │  JDBC Connection Pool (`HikariCP`, `ledgerline_runtime` role)
          ▼
┌────────────────────────────────────────────────────────────────────────┐
│ TRUST BOUNDARY 4: POSTGRESQL 18 DATABASE AUTHORITY                     │
│ - Schema DDL isolation (`ddl-auto=validate`, Flyway V1-V9 migrations)  │
│ - Least-privilege runtime role (`ledgerline_runtime`, NOINHERIT)        │
│   * No TRUNCATE or DELETE on any table                                 │
│   * Column-level UPDATE restricted to status and timestamps            │
│   * No CREATE on schema public                                         │
│ - INSERT boundary guards: blocks ADMIN/AUDITOR roles & fake balances   │
│ - Call-stack protected balance projection trigger (`PG_CONTEXT`)       │
│ - Normal balance classification (`normal_balance`: DEBIT / CREDIT)     │
│ - Account ownership invariants (`accounts_ownership_policy_chk`)       │
│ - Account classification immutability (`protect_account_classification`)│
│ - Concurrency-safe row-level locking (`FOR UPDATE` on `accounts`)      │
│ - Trigger-maintained balance projections (`apply_ledger_entry...`)     │
│ - Append-only ledger entries (PostgreSQL trigger blocks UPDATE/DELETE) │
│ - Append-only audit log table (PostgreSQL trigger blocks UPDATE/DELETE)│
│ - Deferred constraint triggers: zero-sum debits=credits at commit      │
│ - Account non-negative & customer credit-normal policy constraints     │
└────────────────────────────────────────────────────────────────────────┘
```

Vaultora operates under a strict **defense-in-depth model**: the application layer serves as the primary enforcement point for user interaction and business workflows, but the **PostgreSQL database is the ultimate authority for financial integrity and ledger invariants**. No application bug, compromised service, or administrative bypass can violate the mathematical laws of the general ledger or corrupt persisted financial history.

---

## 2. Threat Categories & Concrete Engineering Threat Models

### Category A: Authentication & Account Security

#### SEC-AUTH-001: Brute-Force & Credential Stuffing Attacks
- **Threat ID**: `SEC-AUTH-001`
- **Name**: Distributed Credential Stuffing & Password Brute-Force
- **Category**: Authentication & Account Security
- **Severity**: High (Targeted account takeover, credential exposure)
- **Attack Scenario**: An attacker uses automated tools (e.g., Hydra, Burp Intruder) to cycle through thousands of leaked credentials against `POST /api/v1/auth/login`. Because financial systems hold monetary assets, automated password guessing against high-value customer accounts or employee administrative accounts represents an active threat.
- **Potential Impact**: Unauthorized account takeover, unauthorized access to customer account balances, transaction initiation (Phase 2), compliance penalties.
- **Mitigation Strategy**: Multi-dimensional concurrency-safe rate limiting implemented in `LoginRateLimiter`. To prevent legitimate users from experiencing accidental lockout due to normal repeated logins or test evaluation, the rate limiter tracks **failed authentication attempts exclusively**:
  1. **Per-Email Failure Threshold**: Maximum 5 rejected credential attempts per normalized email within a 60-second sliding window.
  2. **Per-IP Failure Threshold**: Maximum 30 rejected credential attempts per client IP address within a 60-second sliding window (preventing distributed spraying across multiple usernames from a single source).
  3. **Non-Consuming Success**: Successful logins do **not** increment failure counters, allowing repeated authorized sign-ins across sessions.
  4. **Immediate Reset on Success**: Successful authentication immediately clears the per-email failure history (`recordSuccess`), while preserving the shared IP history so attackers cycling target usernames remain rate-limited.
  5. **Dynamic Cooldown**: Calculates the exact remaining time until the oldest failure expires (capped at 60 seconds) and delivers it via the HTTP `Retry-After` header.
  6. **Bounded LRU Storage**: Map capacity is bounded to 10,000 tracked keys with access-ordered LRU eviction (`accessOrder = true`) and input key lengths are capped (IP 64 chars, email 255 chars) to prevent memory exhaustion DoS.
- **Implementation Evidence**: 
  - `backend/ledgerline/src/main/java/com/vaultora/auth/LoginRateLimiter.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java` (lines 71-105)
  - `backend/ledgerline/src/main/java/com/vaultora/auth/ApiExceptionHandler.java` (HTTP 429 mapping with `Retry-After`)
- **Verification**: `Phase1IntegrationTests.loginRateLimitingRejectsExcessiveAttempts()`, `LoginRateLimiterTest` (11 unit tests verifying 5 email / 30 IP thresholds, non-consuming successes, atomic concurrency, and cooldown expiration)
- **Status**: MITIGATED
- **Residual Risk**: A high-volume distributed botnet cycling across millions of unique IP addresses could attempt 4 failures per target account before triggering per-email limits. Further mitigations (CAPTCHA challenges, geo-IP velocity analysis, and WAF rules) can be introduced at edge API gateways.

---

#### SEC-AUTH-002: BCrypt 72-Byte Truncation Attack
- **Threat ID**: `SEC-AUTH-002`
- **Name**: Password Truncation Collision via BCrypt 72-Byte Limit
- **Category**: Authentication & Account Security
- **Severity**: High (Authentication bypass on long passwords)
- **Attack Scenario**: The standard BCrypt key expansion algorithm only processes the first 72 bytes of any input string; all bytes beyond byte 72 are silently discarded. If a user registers with a password of length 73+ bytes (e.g., `CorrectHorseBatteryStaple1234567890123456789012345678901234567890...ABC`), an adversary who knows or guesses the first 72 bytes can authenticate with *any* suffix (e.g., `...XYZ`), successfully authenticating without knowing the true password. Furthermore, multi-byte UTF-8 characters (e.g., accented characters, emojis) take 2 to 4 bytes each, meaning a password with fewer than 25 characters could trigger truncation unexpectedly.
- **Potential Impact**: Authentication collision, compromised user accounts, false sense of security with long passphrases.
- **Mitigation Strategy**: Strict pre-hashing validation using `@MaxUtf8Bytes(max = 72)` applied directly to `RegisterRequest.password` and `LoginRequest.password`. Inputs whose UTF-8 byte length exceeds 72 bytes are rejected at the HTTP request validation boundary with HTTP 400 Bad Request before ever reaching the BCrypt encoder.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/auth/validation/MaxUtf8Bytes.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/validation/MaxUtf8BytesValidator.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/LoginRequest.java`
- **Verification**: `Phase1IntegrationTests.passwordExceeding72Utf8BytesRejectedOnRegistration()`, `MaxUtf8BytesValidatorTest`
- **Status**: MITIGATED
- **Residual Risk**: None for the BCrypt vector. Upgrading to Argon2id in a future release would eliminate byte-length limitations entirely.

---

#### SEC-AUTH-003: Session Fixation Attack
- **Threat ID**: `SEC-AUTH-003`
- **Name**: Pre-Authentication Session Identifier Fixation
- **Category**: Authentication & Account Security
- **Severity**: Critical (Account hijack via fixed session ID)
- **Attack Scenario**: An attacker obtains a valid session cookie by connecting to the application (or tricks a victim into using a specific session ID via HTTP header manipulation or malicious subdomain cookie tossing). When the victim subsequently logs in via `POST /api/v1/auth/login`, if the application preserves the pre-login session ID and merely associates user authentication details with it, the attacker uses their retained session ID to access the victim's authenticated session.
- **Potential Impact**: Complete account takeover without knowing victim credentials, unauthorized access to financial accounts.
- **Mitigation Strategy**: Explicit integration of Spring Security's `SessionFixationProtectionStrategy` in `AuthController.login()`. Upon successful authentication, the existing session is invalidated, all session attributes are migrated, and a new cryptographic session ID is generated and returned to the client in the `Set-Cookie` header.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java` (SessionAuthenticationStrategy bean)
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java` (line 92: `sessionAuthenticationStrategy.onAuthentication(...)`)
- **Verification**: `Phase1IntegrationTests.sessionFixationProtectionRotatesSessionId()`
- **Status**: MITIGATED
- **Residual Risk**: None on the application boundary. Client-side browser hygiene (preventing XSS) remains required to prevent post-authentication session token theft.

---

#### SEC-AUTH-004: Session Hijacking & Insecure Cookie Transport
- **Threat ID**: `SEC-AUTH-004`
- **Name**: Session Token Interception & Sidejacking
- **Category**: Authentication & Account Security
- **Severity**: High (Session token theft leading to impersonation)
- **Attack Scenario**: An attacker on an untrusted local network (e.g., public Wi-Fi) intercepts plain HTTP traffic or reads browser cookies via malicious JavaScript executing in the client browser context.
- **Potential Impact**: Account impersonation, unauthorized money movement.
- **Mitigation Strategy**: Server-side session management using hardened cookies configured in `application.properties`:
  - `server.servlet.session.cookie.http-only=true`: JavaScript cannot access `JSESSIONID`.
  - `server.servlet.session.cookie.same-site=lax` (or `strict`/`none` with `secure=true` in cross-site production): Mitigates cross-site request leakage.
  - `server.servlet.session.cookie.secure=${COOKIE_SECURE:false}`: In production environments, forced HTTPS prevents transmission over plaintext channels.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/application.properties` (lines 14-17)
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java`
- **Verification**: `Phase1IntegrationTests.unauthenticatedAccessToProtectedEndpointsFails()`
- **Status**: MITIGATED
- **Residual Risk**: Production deployments must enforce TLS 1.3 with HSTS headers so `COOKIE_SECURE=true` is guaranteed.

---

#### SEC-AUTH-005: Authentication State Desynchronization (Disabled Users)
- **Threat ID**: `SEC-AUTH-005`
- **Name**: Active Session Persistence for Suspended or Disabled Accounts
- **Category**: Authentication & Account Security
- **Severity**: High (Terminated employees or frozen fraudulent accounts continuing operations)
- **Attack Scenario**: A customer account is flagged for fraud or an employee is terminated, and an administrator sets `users.enabled = FALSE` or transitions status from `ACTIVE`. If the application only evaluates user credentials at login time and caches the authorization principal in the HTTP session without re-validating the user's status, the attacker can continue executing financial actions using their existing session.
- **Potential Impact**: Fraudulent withdrawals from frozen accounts, disgruntled employee data exfiltration.
- **Mitigation Strategy**: The application evaluates user status both at authentication time and on **every subsequent HTTP request** via `SessionUserValidationFilter` (`OncePerRequestFilter`). On each request with an authenticated session, the filter queries the database to confirm that the user exists, holds `UserStatus.ACTIVE`, and that their session authorities still match `"ROLE_" + user.getRole().name()`. If the user is disabled, deleted, or their role was modified mid-session, the security context is immediately cleared, the session is invalidated, and HTTP 401 Unauthorized is returned.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/security/SessionUserValidationFilter.java`
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java` (lines 80-87)
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditService.java`
- **Verification**: `Phase1IntegrationTests.disabledUserCannotAuthenticate()`
- **Status**: MITIGATED
- **Residual Risk**: Single-instance database lookup per authenticated request adds minimal latency. Multi-node clustering without sticky sessions or distributed session store is deferred.

---

#### SEC-AUTH-006: Cross-Site Request Forgery (CSRF)
- **Threat ID**: `SEC-AUTH-006`
- **Name**: Cross-Site Request Forgery Against State-Changing Endpoints
- **Category**: Authentication & Account Security
- **Severity**: High (Unauthorized action execution in authenticated context)
- **Attack Scenario**: A logged-in customer visits an attacker-controlled website that contains an invisible `<form>` or script submitting a `POST` request to `https://bank.com/api/v1/transfers`. Because browsers automatically attach session cookies to ambient requests, the server processes the transaction.
- **Potential Impact**: Unauthorized money transfers, unauthorized password changes.
- **Mitigation Strategy**: Spring Security CSRF protection is strictly enabled using `CookieCsrfTokenRepository` with `HttpOnly=false` (enabling Single Page Applications to read the token) and `CsrfTokenRequestAttributeHandler`. All state-changing requests (`POST`, `PUT`, `DELETE`) require a valid `X-XSRF-TOKEN` header. Safe retrieval is exposed via `GET /api/v1/auth/csrf`.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java` (lines 43-48)
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java` (lines 53-56)
- **Verification**: `Phase1IntegrationTests.csrfIsRequiredForStateChangingEndpoints()`
- **Status**: MITIGATED
- **Residual Risk**: Requires frontend integration to extract `XSRF-TOKEN` cookie and include it in request headers.

---

#### SEC-AUTH-007: Demonstration Identity Provisioning & Privilege Isolation
- **Threat ID**: `SEC-AUTH-007`
- **Name**: Unauthorized Identity Escalation During Demonstration Provisioning
- **Category**: Authentication & Account Security
- **Severity**: High (Privilege escalation, unauthorized admin/auditor injection)
- **Attack Scenario**: An untrusted runtime process or compromised endpoint attempts to insert high-privilege identities (`ADMIN`, `AUDITOR`) into the `users` table, bypassing regular application registration controls.
- **Potential Impact**: Full platform takeover, unauthorized audit trail tampering, ledger manipulation.
- **Mitigation Strategy**: Multi-layered identity protection:
  1. **PostgreSQL Database Trigger (`trg_protect_user_role_assignment`)**: Enforces that the operational runtime database user (`ledgerline_runtime`) can never insert or escalate users to `ADMIN` or `AUDITOR` roles (aborts with SQLState `42501`).
  2. **Dedicated Schema-Owner Provisioning Utility (`DemoProvisioningTool`)**: Executes outside the web request lifecycle using bootstrap/schema-owner credentials (`ledgerline_app`) to provision the 16 demonstration identities (12 CUSTOMER, 3 AUDITOR, 1 ADMIN).
  3. **Strict BCrypt Encoding**: Plaintext passwords are never stored in PostgreSQL; dynamic BCrypt cost-10 hashes are generated during provisioning.
  4. **Idempotent Non-Destructive Operation**: Validates existing users and accounts; never deletes existing user data, balances, or transactions.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V9__prevent_privilege_escalation_and_balance_fabrication.sql`
  - `backend/ledgerline/src/main/java/com/vaultora/provision/DemoProvisioningTool.java`
- **Verification**: Database catalog verification (16 demo identities, 19 baseline accounts, non-demo users preserved).
- **Status**: MITIGATED
- **Residual Risk**: Provisioning tool must only be executed by authorized system operators with access to bootstrap database credentials.

---

#### SEC-AUTH-008: Assessment Credential Segregation & Git Secret Protection
- **Threat ID**: `SEC-AUTH-008`
- **Name**: Hardcoded Assessment Credentials in Source Code & Version Control
- **Category**: Authentication & Account Security
- **Severity**: Medium (Credential leakage via git repository exposure)
- **Attack Scenario**: Demonstration passwords or sensitive assessment identity sheets are committed to tracked repository files (e.g., git commits, SQL migration scripts, frontend bundles, or public Markdown documentation).
- **Potential Impact**: Leaked credentials across public repository clones or deployment artifacts.
- **Mitigation Strategy**:
  1. **Strict Git Ignore Boundary**: The `/private/` directory is explicitly listed in `.gitignore` and verified with `git check-ignore`.
  2. **Isolated Local Output**: `DemoProvisioningTool` outputs the evaluator credential record exclusively to `private/demo-credentials.local.md`.
  3. **Tracked Source Sanitization**: No plaintext demonstration passwords appear in tracked SQL scripts, frontend components, `README.md`, or test suites. Demonstration passwords adhere to the user's complete email address (satisfying the >= 12 character complexity policy).
- **Implementation Evidence**:
  - `.gitignore` (line 12: `/private/`)
  - `backend/ledgerline/src/main/java/com/vaultora/provision/DemoProvisioningTool.java` (lines 234-281)
- **Verification**: `git check-ignore private/demo-credentials.local.md` returns match; `git status --ignored` verifies exclusion.
- **Status**: MITIGATED
- **Residual Risk**: Evaluator must maintain local file secrecy for the generated `private/` directory.

---

### Category B: Authorization & Access Control

#### SEC-AUTHZ-001: Insecure Direct Object References (IDOR) & Cross-Customer Account Access
- **Threat ID**: `SEC-AUTHZ-001`
- **Name**: Broken Object-Level Authorization on Accounts & Ledger Entries
- **Category**: Authorization & Access Control
- **Severity**: Critical (Customer data exposure, unauthorized fund access)
- **Attack Scenario**: Customer A logs in and has account UUID `11111111-...`. Customer A changes the requested account ID in an API call to Customer B's account UUID `22222222-...` (e.g., `GET /api/v1/accounts/22222222-...` or `POST /api/v1/transfers`). If the service layer retrieves the account purely by primary key without asserting `account.user_id == authenticatedUser.id`, Customer A accesses Customer B's private financial records.
- **Potential Impact**: Severe privacy breach, regulatory non-compliance (GLBA, GDPR), unauthorized fund transfer.
- **Mitigation Strategy**: The domain model binds each `CUSTOMER` account to an explicit `user_id` foreign key (`AccountEntity.owner`). Service-layer methods strictly enforce ownership boundaries: both `AccountService` and `TransactionService` invoke `resolveCustomerAccount(accountId, userId)`, which asserts that the account exists, is a `CUSTOMER` account (rejecting `SYSTEM` accounts with `SystemAccountAccessException`), and that `account.getOwner().getId().equals(userId)` (rejecting cross-customer access with `AccountAccessDeniedException`). Transfer source accounts, deposits, withdrawals, and balance queries strictly resolve through this ownership guard. Account listing strictly filters by the authenticated user's ID (`accountRepository.findAllByOwnerIdOrderByCreatedAtDesc(userId)`).
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V1__initial_schema.sql` (`accounts.owner_user_id` FK)
  - `backend/ledgerline/src/main/java/com/vaultora/domain/AccountEntity.java`
  - `backend/ledgerline/src/main/java/com/vaultora/account/AccountService.java` (`resolveCustomerAccount` ownership check)
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (`resolveCustomerAccount` ownership check)
- **Verification**: `Phase2FinancialEngineIntegrationTests$AccountManagementTests.customerCannotAccessAnotherCustomerAccount()`, `Phase2FinancialEngineIntegrationTests$DepositTests.depositToAnotherCustomerAccountIsRejected()`, `Phase2FinancialEngineIntegrationTests$WithdrawalTests.withdrawalFromAnotherCustomerAccountIsRejected()`
- **Status**: MITIGATED
- **Residual Risk**: Any newly added endpoints must continue to pass authenticated principal identity directly into service-layer ownership resolvers.

---

#### SEC-AUTHZ-002: Role Privilege Escalation via Registration & Request Modification
- **Threat ID**: `SEC-AUTHZ-002`
- **Name**: Unauthorized Elevation to ADMIN or AUDITOR Role
- **Category**: Authorization & Access Control
- **Severity**: Critical (Complete system compromise)
- **Attack Scenario**: A malicious actor registers a new user while passing `{"role": "ADMIN"}` or `{"role": "AUDITOR"}` in the JSON payload of `POST /api/v1/auth/register`. If the DTO or controller binds the role field dynamically, the attacker creates an administrative user.
- **Potential Impact**: System-wide administrative compromise, access to all customer accounts, audit trail evasion.
- **Mitigation Strategy**: `RegisterRequest` DTO contains *only* `email` and `password`. The role is hardcoded to `UserRole.CUSTOMER` in `AuthController.register()`. It is impossible for external callers to supply a role parameter.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java` (line 46)
- **Verification**: `Phase1IntegrationTests.publicRegistrationCreatesCustomerRoleOnly()`
- **Status**: MITIGATED
- **Residual Risk**: Administrative user provisioning must be conducted via secure database migrations or dedicated internal provisioning tools.

---

#### SEC-AUTHZ-003: AUDITOR Role Write Bypass
- **Threat ID**: `SEC-AUTHZ-003`
- **Name**: AUDITOR Role Executing Mutating Administrative Actions
- **Category**: Authorization & Access Control
- **Severity**: High (Violation of segregation of duties, unauthorized configuration changes)
- **Attack Scenario**: A user assigned the `AUDITOR` role (intended strictly for read-only regulatory inspection) attempts to trigger mutating actions or modify audit data. If security configurations map role authorization using wildcard `hasAnyRole('ADMIN', 'AUDITOR')` across entire URL prefixes, auditors can modify data.
- **Potential Impact**: Segregation of duties failure, audit trail tampering, unauthorized state changes.
- **Mitigation Strategy**: Complete elimination of fake mutating audit endpoints (`POST /api/v1/audit/logs` and `AdminController` removed). Fine-grained HTTP request authorization in `SecurityConfig`:
  - `GET /api/v1/audit/**`: Permitted to `ADMIN` and `AUDITOR`.
  - Mutating HTTP methods (`POST`, `PUT`, `DELETE`) on `/api/v1/audit/**`: Denied.
  - Customer accounts (`CUSTOMER` role): Explicitly forbidden (HTTP 403) from accessing audit records.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java`
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditController.java`
- **Verification**: `Phase1IntegrationTests.auditorRoleHasReadOnlyAccessAndCannotMutate()`
- **Status**: MITIGATED
- **Residual Risk**: Any new administrative endpoints created in Phase 2 must adhere to explicit HTTP method-level matching.

---

#### SEC-AUTHZ-004: Administrative Ledger Override & Integrity Bypass
- **Threat ID**: `SEC-AUTHZ-004`
- **Name**: Rogue Administrator Creating Direct Balance Adjustments or Unbalanced Entries
- **Category**: Authorization & Access Control
- **Severity**: Critical (Financial ledger corruption, embezzlement)
- **Attack Scenario**: An administrator with valid `ADMIN` credentials accesses administrative endpoints or the database and attempts to create unbalanced ledger entries, directly update an account balance, or delete ledger entries to conceal fraudulent transfers.
- **Potential Impact**: Balance divergence, undetectable embezzlement, general ledger collapse.
- **Mitigation Strategy**: Multi-tiered architectural boundaries prevent administrators from bypassing ledger invariants:
  1. No application API exists to directly modify `accounts.balance`.
  2. Database trigger `forbid_ledger_entry_mutation()` completely rejects all `UPDATE` and `DELETE` statements on `ledger_entries`.
  3. Database constraint trigger `check_transaction_integrity_by_id()` runs at commit time with `INITIALLY DEFERRED`, requiring every transaction with ledger entries to be `COMPLETED`, zero-sum balanced (`sum(debit) == sum(credit)`), and currency consistent. Even an administrator executing SQL directly cannot commit unbalanced entries or delete historical entries.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql`
- **Verification**: `Phase1IntegrationTests.ledgerEntriesAreStrictlyAppendOnly()`, `Phase1IntegrationTests.unbalancedTransactionFailsAtCommit()`
- **Status**: MITIGATED
- **Residual Risk**: A database superuser (`postgres`) could theoretically disable triggers via `ALTER TABLE ... DISABLE TRIGGER ALL`. Mitigated by running the application as restricted role `ledgerline_app` without superuser privileges.

---

#### SEC-AUTHZ-005: Account Ownership Ambiguity & Misattribution
- **Threat ID**: `SEC-AUTHZ-005`
- **Name**: Unowned Customer Accounts or User-Owned System Accounts
- **Category**: Authorization & Access Control
- **Severity**: High (Broken ownership accountability, asset misattribution)
- **Attack Scenario**: A software bug or malicious direct database query creates a customer account without an owner (`owner_user_id IS NULL`), leaving funds orphaned, or attaches a human user as the owner of an internal system vault account (`SYSTEM` with `owner_user_id IS NOT NULL`), allowing a customer to claim system-wide bank assets.
- **Potential Impact**: Financial misattribution, orphaned liability balances, broken regulatory reporting.
- **Mitigation Strategy**: Two-tier enforcement:
  1. Database check constraint `accounts_ownership_policy_chk` added in migration `V6`:
     ```sql
     CHECK (
         (account_type = 'CUSTOMER' AND owner_user_id IS NOT NULL)
         OR
         (account_type = 'SYSTEM' AND owner_user_id IS NULL)
     )
     ```
  2. Entity lifecycle callbacks `@PrePersist` and `@PreUpdate` in `AccountEntity.java` validating the invariant before executing database operations.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V6__account_ownership_immutability_and_privileges.sql`
  - `backend/ledgerline/src/main/java/com/vaultora/domain/AccountEntity.java`
- **Verification**: `Phase1IntegrationTests.customerAccountRequiresOwnerAndSystemAccountRequiresNullOwner()`, `Phase1IntegrationTests.customerAccountCannotBeCreatedWithoutOwnerAtDatabaseLevel()`, `Phase1IntegrationTests.systemAccountCannotHaveOwnerAtDatabaseLevel()`
- **Status**: MITIGATED
- **Residual Risk**: None. PostgreSQL check constraints reject any row violating ownership policy regardless of caller.

---

### Category C: Financial & Ledger Integrity (Highest Priority)

#### SEC-FIN-001: Race Conditions & Concurrent Double-Spending
- **Threat ID**: `SEC-FIN-001`
- **Name**: Concurrent Withdrawal Exceeding Available Balance
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Direct financial loss, customer overdraft)
- **Attack Scenario**: A customer with a balance of $100 issues two concurrent withdrawal requests for $100 within milliseconds of each other. If both threads read the account balance as $100 simultaneously before either writes a debit, both withdrawals succeed, overdrafting the account to -$100.
- **Potential Impact**: Direct monetary theft, negative balance on non-overdraft accounts.
- **Mitigation Strategy**: 
  1. Row-level pessimistic locking in PostgreSQL trigger `apply_ledger_entry_to_balance()`:
     ```sql
     SELECT balance, currency, account_type, normal_balance, allow_negative
     INTO v_account
     FROM accounts
     WHERE id = NEW.account_id
     FOR UPDATE;
     ```
  2. Database check constraint `accounts_customer_policy_chk`:
     ```sql
     CHECK (account_type <> 'CUSTOMER' OR (normal_balance = 'CREDIT' AND allow_negative = FALSE AND balance >= 0))
     ```
     Any concurrent attempt to debit the account below zero causes the second transaction to abort with a check constraint violation.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 43-47, 72-78)
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (`lockAccountsInDeterministicOrder` row locking, post-lock balance validation)
  - `backend/ledgerline/src/main/java/com/vaultora/repository/AccountRepository.java` (`findByIdForUpdate` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`)
- **Verification**: `Phase1IntegrationTests.customerAccountCannotBecomeNegative()`, `Phase2FinancialEngineIntegrationTests$ConcurrencyTests.concurrentWithdrawalsPreventOverdraft()`
- **Status**: MITIGATED
- **Residual Risk**: High concurrency on a single hot account serializes requests at the database level. Application backpressure and transaction timeouts ensure connections return cleanly.

---

#### SEC-FIN-002: Universal Accounting Inversion & Balance Calculation Flaw
- **Threat ID**: `SEC-FIN-002`
- **Name**: Incorrect Asset/Liability Normal Balance Modeling
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Incorrect financial statements, erroneous balances)
- **Attack Scenario**: Early implementations treated credits as positive (`+`) and debits as negative (`-`) for *all* accounts. Under double-entry accounting:
  - Customer liability accounts are **CREDIT-normal**: `Balance = Credits - Debits`.
  - Cash / Vault asset accounts are **DEBIT-normal**: `Balance = Debits - Credits`.
  If a bank asset account is debited $100 upon customer deposit, treating debit as negative would cause the bank asset balance to become -$100, violating non-negative constraints and corrupting asset accounting.
- **Potential Impact**: General ledger corruption, inability to execute valid deposits, regulatory reporting failure.
- **Mitigation Strategy**: Introduced explicit `normal_balance VARCHAR(6) NOT NULL DEFAULT 'CREDIT'` and `allow_negative BOOLEAN NOT NULL DEFAULT FALSE` in V5. The trigger function `apply_ledger_entry_to_balance()` calculates:
  - For `CREDIT` normal accounts: Credit adds (`+`), Debit subtracts (`-`).
  - For `DEBIT` normal accounts: Debit adds (`+`), Credit subtracts (`-`).
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 8-28, 80-99)
  - `backend/ledgerline/src/main/java/com/vaultora/domain/NormalBalance.java`
  - `backend/ledgerline/src/main/java/com/vaultora/domain/AccountEntity.java`
- **Verification**: `Phase1IntegrationTests.depositIncreasesBothCashAssetAndCustomerLiability()`, `Phase1IntegrationTests.withdrawalDecreasesBothCustomerLiabilityAndCashAsset()`, `Phase1IntegrationTests.transferDecreasesSenderAndIncreasesReceiver()`
- **Status**: MITIGATED
- **Residual Risk**: None. Future account classifications (e.g., Equity, Revenue, Expense) map directly onto `CREDIT` or `DEBIT` normal balance rules.

---

#### SEC-FIN-003: Unbalanced Ledger Entries (Zero-Sum Invariant Violation)
- **Threat ID**: `SEC-FIN-003`
- **Name**: Unbalanced Transaction Insertion (Debits != Credits)
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Money creation out of thin air, severe accounting breach)
- **Attack Scenario**: A malicious actor or software glitch submits a transaction with a credit of $10,000 to an account and a debit of only $10 to a clearing account, creating $9,990 of fictitious money.
- **Potential Impact**: Catastrophic ledger insolvency, unhedged balance sheet liabilities.
- **Mitigation Strategy**: Deferred PostgreSQL constraint triggers (`ledger_transactions_integrity_at_commit` and `ledger_entries_transaction_integrity_at_commit`). At commit time, `check_transaction_integrity_by_id()` verifies:
  1. Transaction contains at least one debit entry.
  2. Transaction contains at least one credit entry.
  3. `COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) = COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0)`.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 142-205)
- **Verification**: `Phase1IntegrationTests.unbalancedTransactionFailsAtCommit()`
- **Status**: MITIGATED
- **Residual Risk**: None. The constraint trigger operates in PostgreSQL at commit time, preventing any client from committing unbalanced entries.

---

#### SEC-FIN-004: Balance Pollution from Incomplete, Pending, or Failed Transactions
- **Threat ID**: `SEC-FIN-004`
- **Name**: Balance Updates from Non-Completed Transactions
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Phantom balances, unfulfilled commitments)
- **Attack Scenario**: A transaction is created with status `PENDING`. Ledger entries are inserted. If the application crashes, network fails, or validation fails and the transaction is marked `FAILED` or left `PENDING`, the entries would have modified account balances even though the transaction never completed.
- **Potential Impact**: Customer accounts reflect money from unfinished or aborted transactions.
- **Mitigation Strategy**: Database constraint trigger `check_transaction_integrity_by_id()` enforces that **any transaction that contains ledger entries must finish as `COMPLETED` at commit**. If the transaction is `PENDING` or `FAILED`, the commit is aborted and the entire database transaction rolls back, reverting all balance projection updates.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 160-164)
- **Verification**: `Phase1IntegrationTests.pendingTransactionWithEntriesCannotCommit()`, `Phase1IntegrationTests.failedTransactionWithEntriesCannotCommit()`
- **Status**: MITIGATED
- **Residual Risk**: Transactions that fail must rollback in their entirety; empty PENDING transactions are permitted for multi-step saga choreography prior to entry creation.

---

#### SEC-FIN-005: Currency Inconsistency & Cross-Currency Ledger Pollution
- **Threat ID**: `SEC-FIN-005`
- **Name**: Multi-Currency Mismatch in Single Transaction
- **Category**: Financial & Ledger Integrity
- **Severity**: High (Unhedged FX exposure, meaningless debit-credit equality)
- **Attack Scenario**: A transaction header is denominated in `USD`. A ledger entry debits a `EUR` account for 100 EUR and credits a `USD` account for 100 USD. If debits equal credits numerically (100 = 100) without currency verification, the system treats 100 EUR as equivalent to 100 USD.
- **Potential Impact**: Arbitrage exploitation, exchange rate losses, corrupt financial statements.
- **Mitigation Strategy**: Three-point currency integrity validation:
  1. Entry trigger `validate_ledger_entry()` verifies `NEW.currency = v_account.currency`.
  2. Entry trigger verifies `NEW.currency = v_tx.currency`.
  3. Commit trigger `check_transaction_integrity_by_id()` verifies that all entries match transaction currency and all target account currencies match transaction currency.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 125-132, 178-188)
- **Verification**: `Phase1IntegrationTests.currencyMismatchBetweenEntryAndAccountRejected()`, `Phase1IntegrationTests.transactionAndAccountCurrencyCannotDivergeFromExistingEntries()`
- **Status**: MITIGATED
- **Residual Risk**: FX cross-currency transfers require dedicated exchange clearing accounts (planned for Phase 3).

---

#### SEC-FIN-006: Stale JPA Balance Projection Reads
- **Threat ID**: `SEC-FIN-006`
- **Name**: Application Cache Desynchronization after Database Trigger Balance Mutation
- **Category**: Financial & Ledger Integrity
- **Severity**: Medium (Incorrect API responses, potential double-spend in application logic)
- **Attack Scenario**: An application service inserts a ledger entry. The database trigger updates `accounts.balance` in PostgreSQL. However, Hibernate's first-level session cache holds the prior in-memory `AccountEntity` instance with the old balance. If the service returns this entity to the caller or makes business decisions based on it, it operates on stale data.
- **Potential Impact**: Misleading API responses, race conditions if business rules evaluate stale in-memory state.
- **Mitigation Strategy**: After trigger-affected ledger mutations, entities are explicitly refreshed from the database using `entityManager.refresh(managedEntity)` within the active transaction. Redundant repository custom wrappers have been removed in favor of standard JPA persistence context synchronization.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/repository/AccountRepository.java`
  - Integration tests utilizing `@PersistenceContext EntityManager`
- **Verification**: `Phase1IntegrationTests.jpaRefreshesBalanceUpdatedByDatabaseTriggers()`
- **Status**: MITIGATED
- **Residual Risk**: Services must explicitly call `refresh()` or reload via query when trigger mutations occur in the same persistence context.

---

#### SEC-FIN-007: Ledger Immutability Violation & History Rewriting
- **Threat ID**: `SEC-FIN-007`
- **Name**: Modification or Deletion of Committed Financial Records
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Destruction of audit trail, fraudulent accounting)
- **Attack Scenario**: An attacker, compromised service, or rogue DBA attempts to update a ledger entry's amount or delete entries to eliminate evidence of transactions.
- **Potential Impact**: Regulatory fines, unprovable fraud, accounting records repudiation.
- **Mitigation Strategy**: PostgreSQL trigger `forbid_ledger_entry_mutation()` attached to `ledger_entries` intercepts all `BEFORE UPDATE OR DELETE` events and immediately raises an exception (`'ledger_entries are strictly append-only; UPDATE and DELETE operations are forbidden'`). Corrections must be performed exclusively via new compensating reversal transactions.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql` (lines 104-114)
- **Verification**: `Phase1IntegrationTests.ledgerEntriesAreStrictlyAppendOnly()`
- **Status**: MITIGATED
- **Residual Risk**: Superuser database access can disable triggers. Mitigated by least-privilege application database credentials.

---

#### SEC-FIN-008: Direct Account Balance Manipulation & Trigger Authorization Bypass
- **Threat ID**: `SEC-FIN-008`
- **Name**: Direct Modification of Account Balance Projection
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Ledger divergence, balance fraud)
- **Attack Scenario**: An attacker, compromised service, or rogue database user executes direct `UPDATE accounts SET balance = balance + 10000 WHERE id = ...` without inserting corresponding ledger entries, bypassing double-entry accounting. Prior designs relied on PostgreSQL session configuration (`current_setting('ledgerline.balance_update')`), which could be spoofed by any database connection executing `SET ledgerline.balance_update = 'true'`.
- **Potential Impact**: Balance divergence from ledger entries, undetected inflation, financial falsification.
- **Mitigation Strategy**: Multi-layer defense eliminating session variables:
  1. PostgreSQL trigger `protect_account_balance_projection()` inspects the call stack via `GET DIAGNOSTICS v_context = PG_CONTEXT`. Any `UPDATE` to `accounts.balance` originating outside the trigger function `apply_ledger_entry_to_balance` is immediately rejected.
  2. PostgreSQL column-level privilege revocation: the runtime application role `ledgerline_runtime` is denied `UPDATE (balance)` privilege on `accounts`. Only `apply_ledger_entry_to_balance()` (executing as `SECURITY DEFINER` owned by migration owner `ledgerline_app`) can mutate balances.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V6__account_ownership_immutability_and_privileges.sql`
- **Verification**: `Phase1IntegrationTests.accountBalanceProjectionCannotBeChangedDirectly()`, `Phase1IntegrationTests.runtimeUserCannotDirectlyUpdateBalance()`
- **Status**: MITIGATED
- **Residual Risk**: None. Both runtime SQL permissions and PL/pgSQL call-stack introspection forbid direct balance tampering.

---

#### SEC-FIN-009: Post-Creation Account Classification & Currency Tampering
- **Threat ID**: `SEC-FIN-009`
- **Name**: Retroactive Modification of Account Type, Normal Balance, or Currency
- **Category**: Financial & Ledger Integrity
- **Severity**: Critical (Retroactive financial corruption, ledger desynchronization)
- **Attack Scenario**: An account is created as a `DEBIT`-normal asset account with entries recorded. An attacker or administrative bug later executes `UPDATE accounts SET normal_balance = 'CREDIT'` or alters `currency` from `USD` to `EUR`. This would invert balance mathematical logic and invalidate the double-entry consistency of all historical entries.
- **Potential Impact**: Complete disruption of ledger accounting formulas, invalid balance sheets.
- **Mitigation Strategy**: PostgreSQL trigger `protect_account_classification()` attached to `accounts`:
  - Intercepts `BEFORE UPDATE` on `accounts`.
  - If `OLD.account_type <> NEW.account_type`, `OLD.normal_balance <> NEW.normal_balance`, or `OLD.currency <> NEW.currency`, it queries whether any entries exist in `ledger_entries` for `OLD.id`.
  - If entries exist, the update is blocked with exception `'Account classification, normal balance, and currency cannot be modified once ledger entries exist'`.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V6__account_ownership_immutability_and_privileges.sql`
- **Verification**: `Phase1IntegrationTests.accountClassificationCannotBeChangedOnceLedgerEntriesExist()`
- **Status**: MITIGATED
- **Residual Risk**: None. Enforced at the database trigger layer.

---

### Category D: Database & Data Store Security

#### SEC-DATA-001: SQL Injection
- **Threat ID**: `SEC-DATA-001`
- **Name**: SQL Injection via JPA, JPQL, or Dynamic Queries
- **Category**: Database & Data Store Security
- **Severity**: Critical (Data exfiltration, database takeover)
- **Attack Scenario**: An attacker injects malicious SQL statements into input parameters (e.g., email field or search filters).
- **Potential Impact**: Full database exfiltration, credential theft, data destruction.
- **Mitigation Strategy**: Zero raw concatenated SQL strings. All queries utilize Spring Data JPA parameterized repository methods (`findByEmail`, `findByAccountNumber`), JPA Criteria API, or strongly-typed named parameters.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/repository/UserRepository.java`
  - `backend/ledgerline/src/main/java/com/vaultora/repository/AccountRepository.java`
- **Verification**: `Phase1IntegrationTests.duplicateEmailRegistrationFailsGracefully()`
- **Status**: MITIGATED
- **Residual Risk**: None for existing Phase 1 queries. Code review guidelines require static analysis for any future native queries.

---

#### SEC-DATA-002: Audit Log Tampering & Deletion
- **Threat ID**: `SEC-DATA-002`
- **Name**: Erasure or Modification of Audit Trail Records
- **Category**: Database & Data Store Security
- **Severity**: High (Loss of forensic evidence, non-compliance)
- **Attack Scenario**: An attacker breaches the application and deletes entries from the `audit_logs` table to erase traces of unauthorized actions.
- **Potential Impact**: Inability to conduct forensic analysis, failed regulatory audits.
- **Mitigation Strategy**: Database trigger `audit_logs_immutability_trg` in migration V4 forbids all `UPDATE` and `DELETE` operations on `audit_logs`. The table is strictly append-only.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V4__ledger_database_protections.sql` (lines 4-20)
- **Verification**: `Phase1IntegrationTests.auditLogsAreImmutableInDatabase()`
- **Status**: MITIGATED
- **Residual Risk**: Offsite audit log streaming (e.g., syslog/SIEM) is required in production to protect against catastrophic storage failure.

---

#### SEC-DATA-003: Accidental Operations Against Production Database during Testing
- **Threat ID**: `SEC-DATA-003`
- **Name**: Test Suite Mutating Production/Development Database (`ledgerline_db`)
- **Category**: Database & Data Store Security
- **Severity**: High (Catastrophic data loss in dev/prod environments)
- **Attack Scenario**: Integration tests run against `ledgerline_db` instead of a dedicated test database, executing `@BeforeEach` cleanup scripts (`DELETE FROM accounts`, `DELETE FROM users`) and wiping real operational data.
- **Potential Impact**: Permanent data loss, disruption of active development/staging data.
- **Mitigation Strategy**: 
  1. Dedicated test configuration `application-test.properties` points strictly to `ledgerline_test_db`.
  2. Fail-safe runtime safety guard `TestDatabaseGuard` queries `SELECT current_database()` before any test execution. If the database name is not exactly `ledgerline_test_db`, it immediately throws `IllegalStateException`, halting the test runner before any schema or data modifications can occur.
- **Implementation Evidence**:
  - `backend/ledgerline/src/test/resources/application-test.properties`
  - `backend/ledgerline/src/test/java/com/vaultora/TestDatabaseGuard.java`
  - `backend/ledgerline/src/test/java/com/vaultora/Phase1IntegrationTests.java` (line 69)
- **Verification**: `TestDatabaseGuard` verified on every test execution.
- **Status**: MITIGATED
- **Residual Risk**: CI/CD pipelines must ensure test database credentials cannot access production database clusters.

---

#### SEC-DATA-004: Direct Database Access & Privilege Management
- **Threat ID**: `SEC-DATA-004`
- **Name**: Application Role Possessing Superuser or Excessive DML Privileges
- **Category**: Database & Data Store Security
- **Severity**: High (Privilege escalation via compromised database connection)
- **Attack Scenario**: An application database user has excessive privileges (such as superuser access, direct UPDATE/DELETE privileges on ledger or audit tables, or direct UPDATE privileges on account balances), allowing an attacker who exploits an application flaw or SQL injection to bypass application-level controls and manipulate persisted financial state.
- **Potential Impact**: Ledger destruction, unconstrained balance tampering, audit log tampering.
- **Mitigation Strategy**: Architectural separation of database identities:
  1. Migration Owner (`ledgerline_app`): Owns the schema and runs Flyway DDL migrations. Functions such as `apply_ledger_entry_to_balance` are owned by `ledgerline_app` and execute with `SECURITY DEFINER`.
  2. Runtime Application Role (`ledgerline_runtime`): The unprivileged role used by the running Spring Boot backend. Granted only necessary least-privilege permissions:
     - `SELECT` and `INSERT` on domain tables (`users`, `accounts`, `ledger_transactions`, `ledger_entries`, `audit_logs`).
     - Column-level `UPDATE` strictly restricted to `(status, updated_at)` on `users` and `accounts`, and `(status, completed_at)` on `ledger_transactions` (Migration V8).
     - Prohibited from `TRUNCATE` across all tables, and prohibited from `DELETE` across all tables (Migration V8).
     - Prohibited from `CREATE` on schema `public` (Migration V8 & V9).
     - Configured with `NOINHERIT` to prevent inheriting administrative capabilities (Migration V9).
     - Direct `INSERT` boundary triggers `trg_protect_user_role_assignment` and `trg_protect_account_insertion` (Migration V9) preventing role escalation to ADMIN/AUDITOR or balance fabrication.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V8__harden_runtime_database_privileges.sql`
  - `backend/ledgerline/src/main/resources/db/migration/V9__prevent_privilege_escalation_and_balance_fabrication.sql`
  - `backend/ledgerline/src/main/resources/application.properties`
  - `backend/ledgerline/src/test/resources/application-test.properties`
- **Verification**: `Phase2FinancialEngineIntegrationTests.runtimeRoleCatalogAttributesAssertLeastPrivilege()`, `runtimeRoleTableGrantsAreStrictlyLimitedToSelectAndInsert()`, `runtimeRoleColumnGrantsLimitUpdateStrictly()`, `schemaPublicHasNoCreatePrivilegeForRuntimeOrPublic()`
- **Status**: MITIGATED
- **Residual Risk**: Direct connection as `ledgerline_runtime` could still insert a valid customer account bound to an existing customer ID; application-layer authentication remains the authoritative ownership enforcement barrier.

---

### Category E: Application & Deployment Security

#### SEC-APP-001: Information Disclosure via Stack Traces & Exceptions
- **Threat ID**: `SEC-APP-001`
- **Name**: Internal Implementation Details Leakage in Error Responses
- **Category**: Application & Deployment Security
- **Severity**: Low (Information disclosure aiding reconnaissance)
- **Attack Scenario**: An attacker submits malformed inputs expecting a generic Java stack trace exposing database table names, SQL dialect, or library versions.
- **Potential Impact**: Attacker maps backend architecture and dependencies.
- **Mitigation Strategy**: Centralized exception handling via `ApiExceptionHandler` with `@RestControllerAdvice`. All business, validation, authentication, and rate limit exceptions are mapped to clean JSON responses (`{"error": "...", "message": "...", "timestamp": ...}`). Unhandled exceptions return generic messages without stack traces.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/auth/ApiExceptionHandler.java`
- **Verification**: `Phase1IntegrationTests.loginRateLimitingRejectsExcessiveAttempts()`, `Phase1IntegrationTests.passwordExceeding72Utf8BytesRejectedOnRegistration()`
- **Status**: MITIGATED
- **Residual Risk**: `server.error.include-stacktrace=never` must be verified in production profile.

---

#### SEC-APP-002: Insecure Deserialization & Mass Assignment
- **Threat ID**: `SEC-APP-002`
- **Name**: Mass Assignment of Protected Fields (Role, Balance, ID)
- **Category**: Application & Deployment Security
- **Severity**: High (Unauthorized field mutation)
- **Attack Scenario**: An attacker posts fields such as `{"id": "...", "role": "ADMIN", "balance": 1000000}` to an endpoint. If the controller directly binds to an entity class, unexpected fields are written.
- **Potential Impact**: Privilege escalation, balance manipulation.
- **Mitigation Strategy**: Strict separation of DTOs and JPA Entities. Controllers only accept dedicated request records (`RegisterRequest`, `LoginRequest`) containing strictly validated fields. Entities are constructed explicitly in service/controller logic.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/LoginRequest.java`
  - `backend/ledgerline/src/main/java/com/vaultora/auth/AuthController.java`
- **Verification**: `Phase1IntegrationTests.publicRegistrationCreatesCustomerRoleOnly()`
- **Status**: MITIGATED
- **Residual Risk**: None for existing endpoints. All future endpoints must use isolated DTO records.

---

#### SEC-APP-003: Sensitive Data in Security Audit Logs
- **Threat ID**: `SEC-APP-003`
- **Name**: Plaintext Credentials or Session Tokens Stored in Audit Logs
- **Category**: Application & Deployment Security
- **Severity**: High (Credential exposure to audit readers)
- **Attack Scenario**: During login failures or registration events, an audit service records the entire incoming HTTP payload, saving plain text passwords or session IDs in `audit_logs.metadata`.
- **Potential Impact**: Credential leakage to auditors or developers reading logs.
- **Mitigation Strategy**: `AuditService` explicitly sanitizes metadata before persistence. Passwords, auth tokens, and session cookies are never passed to or stored in `audit_logs`. Only non-sensitive operational identifiers (`email`, `ip`, `role`, `status`) are captured.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditService.java` (lines 40-60)
- **Verification**: `Phase1IntegrationTests.authenticationAuditLogsPersistCorrectly()`
- **Status**: MITIGATED
- **Residual Risk**: Future audit events must follow strict sanitization checklists.

---

#### SEC-APP-004: Password Hash Exposure via Audit Trail API
- **Threat ID**: `SEC-APP-004`
- **Name**: Password Hash Exposure in Audit Log Serialization
- **Category**: Application & Deployment Security
- **Severity**: Critical (Credential hash leakage to auditors or API consumers)
- **Attack Scenario**: An audit endpoint returns JPA entity instances (`AuditLogEntity`) containing full relationships to `UserEntity`. When Jackson serializes the response, it inadvertently includes `user.passwordHash` in the JSON output, allowing auditors or observers to capture bcrypt password hashes for offline cracking.
- **Potential Impact**: Mass credential exposure, unauthorized access to user accounts.
- **Mitigation Strategy**: 
  1. Strict DTO boundary via `AuditLogResponse` record containing only safe operational fields (`id`, `actorUserId`, `action`, `resourceType`, `resourceId`, `details`, `createdAt`).
  2. Controller returns `Page<AuditLogResponse>`, preventing full table dumping and avoiding any reference to `UserEntity` or `passwordHash`.
  3. `AuditLogEntity.details` stores native JSONB objects (`Map<String, Object>`), ensuring proper structured metadata serialization without double-encoded JSON strings.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditLogResponse.java`
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditController.java`
  - `backend/ledgerline/src/main/java/com/vaultora/audit/AuditLogEntity.java`
- **Verification**: `Phase1IntegrationTests.auditLogsEndpointDoesNotExposePasswordHash()`, `Phase1IntegrationTests.auditLogDetailsPersistedAsJsonObject()`
- **Status**: MITIGATED
- **Residual Risk**: None. The entity is never serialized directly by the controller.

---

### Category F: Financial Transaction Engine & Money Movement (Phase 2)

#### SEC-TX-001: Concurrent Double-Spending & Overdraft Race Condition
- **Threat ID**: `SEC-TX-001`
- **Name**: Simultaneous Withdrawal Race Condition Exceeding Available Balance
- **Category**: Financial Transaction Engine
- **Severity**: Critical (Direct financial loss, customer overdraft)
- **Attack Scenario**: A customer with an available balance of ₹1,000 issues two concurrent withdrawal requests for ₹800 within milliseconds of each other. If both execution threads read the balance simultaneously before either applies a debit, both withdrawals succeed, overdrafting the account to -₹600.
- **Potential Impact**: Unauthorized credit exposure, negative customer liability balances, direct monetary theft.
- **Mitigation Strategy**: Multi-layered defense:
  1. `TransactionService` acquires a pessimistic row-level write lock (`findByIdForUpdate` executing `SELECT ... FOR UPDATE`) on the account.
  2. The account balance is strictly validated **after** acquiring the row lock; if `balance < amount`, `InsufficientFundsException` is thrown (HTTP 422 Unprocessable Content).
  3. PostgreSQL check constraint `accounts_customer_policy_chk` guarantees that customer accounts (`account_type = 'CUSTOMER'`) can never have a negative balance (`balance >= 0`). Any transaction that attempts to drive the balance below zero is immediately aborted by PostgreSQL.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (`lockAccountsInDeterministicOrder`, balance validation under lock)
  - `backend/ledgerline/src/main/java/com/vaultora/repository/AccountRepository.java` (`findByIdForUpdate`)
  - `backend/ledgerline/src/main/resources/db/migration/V5__remediate_accounting_model_and_ledger_integrity.sql`
- **Verification**: `Phase2FinancialEngineIntegrationTests$ConcurrencyTests.concurrentWithdrawalsPreventOverdraft()` (2 concurrent threads requesting ₹800 against ₹1,000 -> exactly 1 succeeds, 1 rejected with 422, final balance ₹200).
- **Status**: MITIGATED
- **Residual Risk**: High concurrency on a single hot account serializes at the row level; backpressure should be monitored under extreme load.

---

#### SEC-TX-002: Bilateral Transfer Deadlock under High Concurrency
- **Threat ID**: `SEC-TX-002`
- **Name**: Database Deadlock during Simultaneous Opposing Transfers
- **Category**: Financial Transaction Engine
- **Severity**: High (Denial of service, transaction failure under load)
- **Attack Scenario**: Customer A transfers money to Customer B while Customer B simultaneously transfers money to Customer A. If Thread 1 locks Account A first then attempts to lock Account B, while Thread 2 locks Account B first then attempts to lock Account A, a classical cyclic deadlock occurs in PostgreSQL.
- **Potential Impact**: Database serialization failures, 500 internal server errors, connection pool exhaustion.
- **Mitigation Strategy**: Deterministic natural UUID order locking in `TransactionService.lockAccountsInDeterministicOrder(...)`. All involved accounts are sorted in natural UUID order before issuing `SELECT ... FOR UPDATE`. Both threads always request lock A before lock B (or vice versa), eliminating circular wait conditions.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (lines 385-392)
- **Verification**: `Phase2FinancialEngineIntegrationTests$ConcurrencyTests.concurrentTransfersInOppositeDirectionsPreventDeadlock()` (Parallel opposing transfers across 10 threads complete with 0 deadlocks and exact balance preservation).
- **Status**: MITIGATED
- **Residual Risk**: None for bilateral transfers. Multi-account split transfers in future phases must adhere to the same sorted lock acquisition protocol.

---

#### SEC-TX-003: Duplicate Request Ingestion & Idempotency Replay
- **Threat ID**: `SEC-TX-003`
- **Name**: Network Retry Duplicate Execution / Double Movement
- **Category**: Financial Transaction Engine
- **Severity**: Critical (Double debiting or double crediting of accounts)
- **Attack Scenario**: A mobile client or API consumer initiates a transfer of ₹500, but a transient network timeout causes the client to resend the exact same request. Without idempotency enforcement, the transfer is executed a second time, moving an additional ₹500.
- **Potential Impact**: Duplicate financial transactions, customer disputes, unapproved balance changes.
- **Mitigation Strategy**:
  1. Mandatory `Idempotency-Key` request header on all mutating financial endpoints.
  2. Canonical SHA-256 request payload fingerprinting computed over transaction type, account IDs, scale-2 normalized amount (`amount.setScale(2, RoundingMode.UNNECESSARY)`), and uppercase currency.
  3. Pre-transaction PostgreSQL advisory locking (`pg_advisory_xact_lock`) computed over a 64-bit hash of `userId + ":" + normalizedKey`. This serializes concurrent identical or differing requests from the same user across all accounts without encountering abort-inducing unique index collision rollbacks.
  4. Pre-execution check against `ledger_transactions` for existing completed transactions with the same `(user_id, idempotency_key)`. If completed with identical fingerprint, the original completed transaction is safely returned without duplicate ledger entries.
  5. Composite database unique index `ledger_transactions_idempotency_uidx` on `(initiated_by_user_id, idempotency_key)` providing secondary database-level uniqueness enforcement.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (`acquireIdempotencyAdvisoryLock`, `checkExistingIdempotentTransaction`)
  - `backend/ledgerline/src/main/resources/db/migration/V1__initial_schema.sql` (`ledger_transactions_idempotency_uidx`)
- **Verification**: `Phase2FinancialEngineIntegrationTests$IdempotencyTests.sameKeySamePayloadReturnsOriginalResult()`, `Phase2FinancialEngineIntegrationTests$ConcurrencyTests.concurrentDuplicateRequestsExecuteOnlyOnce()`
- **Status**: MITIGATED
- **Residual Risk**: Idempotency records are retained indefinitely; future phases may implement key expiration policies after statutory financial periods.

---

#### SEC-TX-004: Idempotency Key Reuse Conflict & Request Hijacking
- **Threat ID**: `SEC-TX-004`
- **Name**: Reusing an Idempotency Key with a Mutated Payload
- **Category**: Financial Transaction Engine
- **Severity**: High (Request tampering, payload confusion)
- **Attack Scenario**: An attacker or misbehaved client reuses a previously generated idempotency key but alters the amount, destination account, or currency. If the system blindly returned the prior result or executed the new payload under the old key, auditing and ledger traceability would be compromised.
- **Potential Impact**: Ambiguous transaction records, silent payload corruption.
- **Mitigation Strategy**: Strict SHA-256 fingerprint comparison. When an existing idempotency key is detected, the incoming fingerprint is compared with the stored `request_fingerprint`. If they do not match, `IdempotencyConflictException` is thrown, returning HTTP 409 Conflict with zero balance modification.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (lines 398-403)
  - `backend/ledgerline/src/main/java/com/vaultora/auth/ApiExceptionHandler.java` (HTTP 409 mapping)
- **Verification**: `Phase2FinancialEngineIntegrationTests$IdempotencyTests.sameKeyDifferentPayloadReturnsConflict()`
- **Status**: MITIGATED
- **Residual Risk**: None. Reusing keys across different operations is strictly blocked.

---

#### SEC-TX-005: Insecure Direct Object Reference (IDOR) on Financial Operations
- **Threat ID**: `SEC-TX-005`
- **Name**: Cross-Customer Account Debit/Withdrawal/Lookup IDOR
- **Category**: Financial Transaction Engine
- **Severity**: Critical (Unauthorized withdrawal from other customers' accounts)
- **Attack Scenario**: Customer A discovers Customer B's account UUID and attempts to submit a deposit, withdrawal, or transfer using Customer B's account ID as the source, or query Customer B's account balance directly.
- **Potential Impact**: Embezzlement from peer accounts, violation of financial privacy.
- **Mitigation Strategy**: Server-side principal resolution and explicit ownership verification in `resolveCustomerAccount(accountId, userId)`. Every financial service checks `account.getOwner().getId().equals(authenticatedUser.getId())`. If ownership does not match, `AccountAccessDeniedException` is thrown, returning HTTP 403 Forbidden.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/account/AccountService.java` (lines 90-100)
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (lines 430-438)
- **Verification**: `Phase2FinancialEngineIntegrationTests$AccountManagementTests.customerCannotAccessAnotherCustomerAccount()`, `Phase2FinancialEngineIntegrationTests$WithdrawalTests.withdrawalFromAnotherCustomerAccountIsRejected()`, `Phase2FinancialEngineIntegrationTests$DepositTests.depositToAnotherCustomerAccountIsRejected()`
- **Status**: MITIGATED
- **Residual Risk**: None. All account resolutions are validated against the authenticated Spring Security principal.

---

#### SEC-TX-006: System Cash Vault Extraction & Direct Injection
- **Threat ID**: `SEC-TX-006`
- **Name**: Customer Access or Direct Transfer to System Asset Accounts
- **Category**: Financial Transaction Engine
- **Severity**: Critical (Direct bank reserve drainage, ledger distortion)
- **Attack Scenario**: A customer attempts to query the internal cash vault (`SYS-INR-CASH-VAULT-001`), deposit directly into it, or transfer money directly into the system vault account.
- **Potential Impact**: Distorted bank asset balance sheet, unauthorized view of bank reserves.
- **Mitigation Strategy**: Explicit `AccountType.SYSTEM` checks in both `AccountService` and `TransactionService`. Any attempt by a customer to access an account with `account_type = 'SYSTEM'` or initiate a transfer with a destination system account immediately throws `SystemAccountAccessException`, returning HTTP 403 Forbidden. System accounts are strictly operational counterparts invoked server-side.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/account/AccountService.java` (lines 93-95)
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (lines 261-263, 428-430)
- **Verification**: `Phase2FinancialEngineIntegrationTests$AccountManagementTests.customerCannotAccessSystemAccount()`, `Phase2FinancialEngineIntegrationTests$TransferTests.transferToSystemAccountIsRejected()`
- **Status**: MITIGATED
- **Residual Risk**: None. System accounts are unreachable via customer-facing APIs.

---

#### SEC-TX-007: Circular Self-Transfers
- **Threat ID**: `SEC-TX-007`
- **Name**: Internal Self-Transfer Velocity Exploits
- **Category**: Financial Transaction Engine
- **Severity**: Low (Unnecessary ledger bloat, velocity filter evasion)
- **Attack Scenario**: A customer submits a transfer with identical source and destination accounts to generate artificial transaction volume.
- **Potential Impact**: Audit log pollution, wasted database storage.
- **Mitigation Strategy**: Service-level assertion `sourceAccount.getId().equals(destinationAccount.getId())` throwing `SelfTransferException`, returning HTTP 400 Bad Request.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/transaction/TransactionService.java` (lines 265-267)
- **Verification**: `Phase2FinancialEngineIntegrationTests$TransferTests.selfTransferIsRejected()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

#### SEC-TX-008: AUDITOR Privilege Escalation to Money Movement
- **Threat ID**: `SEC-TX-008`
- **Name**: Regulatory AUDITOR User Initiating Financial Transactions
- **Category**: Financial Transaction Engine
- **Severity**: High (Violation of regulatory segregation of duties)
- **Attack Scenario**: A user possessing the `AUDITOR` role attempts to call `POST /api/v1/transactions/deposit`, `POST /api/v1/transactions/withdraw`, or `POST /api/v1/transactions/transfer` to move funds.
- **Potential Impact**: Regulatory compliance violation, unauthorized money movement.
- **Mitigation Strategy**: Explicit method-level URL protection in `SecurityConfig`. `POST /api/v1/transactions/**` and `POST /api/v1/accounts/**` are strictly restricted to `hasAnyRole('CUSTOMER', 'ADMIN')`. Any request by an `AUDITOR` is rejected with HTTP 403 Forbidden.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/vaultora/security/SecurityConfig.java` (lines 38-42)
- **Verification**: `Phase2FinancialEngineIntegrationTests$SecurityAndAuthorizationTests.auditorCannotInitiateMoneyMovement()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

### Category E: Database Privilege, Idempotency & Privacy Hardening (Phase 2 Remediation)

#### SEC-DB-005: Runtime Database Privilege Abuse & TRUNCATE Destruction
- **Threat ID**: `SEC-DB-005`
- **Name**: Table Truncation and Data Destruction via Runtime Application Role
- **Category**: Database Security
- **Severity**: Critical (Irreversible data loss)
- **Attack Scenario**: If an attacker exploits an application-level vulnerability (e.g. SQL injection or remote code execution), having `TRUNCATE` or unrestricted `DELETE` privileges granted to `ledgerline_runtime` would allow wiping the general ledger, audit trails, and accounts table.
- **Potential Impact**: Complete catastrophic loss of transaction history and audit evidence.
- **Mitigation Strategy**: Flyway migration `V8__harden_runtime_database_privileges.sql` revokes `TRUNCATE` across all schema tables from `ledgerline_runtime`, revokes `DELETE` across all tables, and revokes `CREATE` on schema `public`.
- **Implementation Evidence**: `backend/ledgerline/src/main/resources/db/migration/V8__harden_runtime_database_privileges.sql` (lines 4-13)
- **Verification**: `Phase2FinancialEngineIntegrationTests$SecurityAndAuthorizationTests.runtimeRoleCannotTruncateTables()`
- **Status**: MITIGATED
- **Residual Risk**: Database administrator (`ledgerline_app`) credentials must remain tightly guarded.

---

#### SEC-DB-006: Direct Privilege Escalation via User Table UPDATE
- **Threat ID**: `SEC-DB-006`
- **Name**: User Role Escalation & Direct Password Tampering via Application Connection
- **Category**: Database Security / Authorization
- **Severity**: Critical (Administrative takeover)
- **Attack Scenario**: If the runtime application role has unrestricted `UPDATE` access to the `users` table, a query injection or malicious service could execute `UPDATE users SET role = 'ADMIN'` or directly replace `password_hash`.
- **Potential Impact**: Full administrative takeover bypassing Java-level RBAC and BCrypt verification.
- **Mitigation Strategy**: Migration `V8` revokes table-level `UPDATE` on `users` and grants column-level `UPDATE` strictly on `(status, updated_at)`. The runtime user cannot modify `role`, `password_hash`, `email`, or `id`.
- **Implementation Evidence**: `V8__harden_runtime_database_privileges.sql` (lines 14-19)
- **Verification**: `runtimeRoleCannotPromoteUserRole()`, `runtimeRoleCannotModifyPasswordHash()`
- **Status**: MITIGATED
- **Residual Risk**: None through application connection.

---

#### SEC-DB-007: Overdraft Policy Manipulation via `allow_negative`
- **Threat ID**: `SEC-DB-007`
- **Name**: Customer Overdraft Authorization via `allow_negative` Tampering
- **Category**: Financial Security
- **Severity**: High (Unauthorized credit creation)
- **Attack Scenario**: If `ledgerline_runtime` has permission to update `accounts.allow_negative`, an attacker could enable negative balances for an arbitrary customer account, subverting the non-negative balance trigger check.
- **Potential Impact**: Unauthorized money creation and extraction.
- **Mitigation Strategy**: Migration `V8` revokes `UPDATE (allow_negative)` from `ledgerline_runtime`, restricting account updates exclusively to `(status, updated_at)`.
- **Implementation Evidence**: `V8__harden_runtime_database_privileges.sql` (lines 20-26)
- **Verification**: `runtimeRoleCannotModifyAllowNegativeOrBalance()`
- **Status**: MITIGATED
- **Residual Risk**: None through runtime application connection.

---

#### SEC-DB-008: Privilege Escalation via User Table Direct INSERT
- **Threat ID**: `SEC-DB-008`
- **Name**: Administrative Privilege Escalation via Direct User Insertion
- **Category**: Database Security / Authorization Boundary
- **Severity**: Critical (Full administrative privilege escalation)
- **Attack Scenario**: Even though `UPDATE` on `users.role` is revoked in V8, the runtime database role (`ledgerline_runtime`) had unrestricted `INSERT` permissions on `users`. An attacker with direct database access or SQL injection could insert a new user row with `role = 'ADMIN'` or `role = 'AUDITOR'`, bypassing application-level registration controls.
- **Potential Impact**: Complete administrative compromise and bypass of application role barriers.
- **Mitigation Strategy**: Flyway migration `V9__prevent_privilege_escalation_and_balance_fabrication.sql` installs trigger `trg_protect_user_role_assignment`. Whenever `CURRENT_USER` or `SESSION_USER` is `ledgerline_runtime`, any `INSERT` with `role <> 'CUSTOMER'` is rejected with PostgreSQL permission error code `42501`.
- **Implementation Evidence**: `V9__prevent_privilege_escalation_and_balance_fabrication.sql` (lines 9-27)
- **Verification**: `runtimeRoleCannotInsertAdminOrAuditorRole()` (verifies 42501 for ADMIN and AUDITOR, allows CUSTOMER)
- **Status**: MITIGATED
- **Residual Risk**: None through application connection.

---

#### SEC-DB-009: Opening Balance Fabrication and Unauthorized SYSTEM Accounts via INSERT
- **Threat ID**: `SEC-DB-009`
- **Name**: Balance Fabrication and SYSTEM Account Injection via Direct Account Insertion
- **Category**: Financial Integrity / Accounting Invariants
- **Severity**: Critical (Unauthorized money creation)
- **Attack Scenario**: The balance protection trigger only protected `UPDATE OF balance`. The runtime database role has `INSERT` on `accounts`. An attacker could insert an account with a non-zero opening balance (e.g. `balance = 1000000.00`), create unbacked `SYSTEM` accounts, or insert accounts with `allow_negative = TRUE`.
- **Potential Impact**: Counterfeit opening balances created without corresponding ledger double-entries, bypassing zero-sum ledger invariants.
- **Mitigation Strategy**: Flyway migration `V9__prevent_privilege_escalation_and_balance_fabrication.sql` installs trigger `trg_protect_account_insertion`. Whenever `CURRENT_USER` or `SESSION_USER` is `ledgerline_runtime`, any `INSERT` on `accounts` is strictly validated: `account_type` must be `'CUSTOMER'`, `owner_user_id` cannot be null, initial `balance` must be `0.00`, `normal_balance` must be `'CREDIT'`, `allow_negative` must be `FALSE`, and `currency` must be `'INR'`. Violations raise PostgreSQL error `42501`.
- **Implementation Evidence**: `V9__prevent_privilege_escalation_and_balance_fabrication.sql` (lines 30-74)
- **Verification**: `runtimeRoleCannotInsertFabricatedBalanceOrSystemAccount()`
- **Status**: MITIGATED
- **Residual Risk**: None through application connection.

---

#### SEC-IDEM-005: Cross-Account Concurrent Idempotency Race Conditions
- **Threat ID**: `SEC-IDEM-005`
- **Name**: Cross-Account Parallel Double Spending with Identical Idempotency Key
- **Category**: Financial Engine / Concurrency
- **Severity**: Critical (Double spending)
- **Attack Scenario**: A user rapidly submits two simultaneous requests with the same `Idempotency-Key` targeting two *different* accounts owned by that user (e.g. Deposit to Account A, Deposit to Account B). Because account-level row locks lock different rows, both threads could check idempotency before either inserts a transaction, causing both to execute.
- **Potential Impact**: Double crediting or double money movement with a single idempotency key.
- **Mitigation Strategy**: PostgreSQL transaction-scoped advisory locking (`pg_advisory_xact_lock`) keyed by a 64-bit hash of `authenticated_user_id + ":" + normalized_idempotency_key`. The advisory lock is acquired inside the transaction before any account lookup or locking. The second request blocks on the advisory lock; once the first commits, the second discovers the existing transaction and rejects the differing payload with HTTP 409 Conflict.
- **Implementation Evidence**: `TransactionService.java` (`acquireIdempotencyAdvisoryLock`, `computeAdvisoryLockKey`)
- **Verification**: `Phase2FinancialEngineIntegrationTests$IdempotencyTests.sameUserSameKeyAcrossDifferentAccountsProducesOneOperation()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

#### SEC-IDEM-006: Aborted Transaction Rollback Recovery Poisoning
- **Threat ID**: `SEC-IDEM-006`
- **Name**: Broken Idempotency Recovery in Aborted PostgreSQL Transactions
- **Category**: Financial Engine / Database Integrity
- **Severity**: High (Server 500 errors, broken idempotent retries)
- **Attack Scenario**: In PostgreSQL, catching a unique-constraint violation (`DataIntegrityViolationException`) inside an active transaction leaves the transaction in an aborted state. Attempting a subsequent query (`findByInitiatedByUserIdAndIdempotencyKey`) within the same aborted transaction causes PostgreSQL to fail with `current transaction is aborted, commands ignored until end of transaction block`.
- **Potential Impact**: Unhandled server errors, failure to return the existing idempotent transaction.
- **Mitigation Strategy**: Advisory locking serializes identical concurrent requests upfront, preventing concurrent constraint violations entirely. In the event of any unexpected constraint violation, the transaction rolls back cleanly without issuing secondary queries in the aborted context.
- **Implementation Evidence**: `TransactionService.java` (advisory locking pre-reservation, removed post-collision query)
- **Verification**: `sameKeySamePayloadReturnsOriginalResult()`, `concurrentDuplicateRequestsExecuteOnlyOnce()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

#### SEC-SYS-002: Arbitrary System Counterpart Account Resolution
- **Threat ID**: `SEC-SYS-002`
- **Name**: Unauthorized System Counterpart Selection & Inversion
- **Category**: Financial Integrity
- **Severity**: High (Misallocation of system assets)
- **Attack Scenario**: If system counterpart resolution falls back to an arbitrary `SYSTEM` account of the same currency, or uses a system account with inverted normal balance (`CREDIT`), incorrect currency, or `allow_negative=true`, deposits and withdrawals could miscalculate bank assets or deplete unauthorized reserves.
- **Potential Impact**: General ledger corruption, miscalculated bank reserves.
- **Mitigation Strategy**: `TransactionService.resolveSystemCounterpartAccount()` strictly requires canonical `SYS-{currency}-CASH-VAULT-001`, eliminates arbitrary fallback selection, and verifies that the resolved account is `SYSTEM`, has `owner == null`, currency matches, `normal_balance == DEBIT`, `status == ACTIVE`, and `allow_negative == false`.
- **Implementation Evidence**: `TransactionService.java` (`resolveSystemCounterpartAccount`)
- **Verification**: `depositFailsIfSystemAccountMisconfigured()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

#### SEC-PRIV-002: System Cash Vault UUID & Counterpart Account Privacy Leakage
- **Threat ID**: `SEC-PRIV-002`
- **Name**: Internal System Identifier and Counterpart Account Number Disclosure
- **Category**: Data Privacy
- **Severity**: Medium (Internal infrastructure enumeration, customer privacy violation)
- **Attack Scenario**: Returning raw ledger entries exposes the internal UUID and full account number of the system cash vault, as well as the full account number and UUID of peer customers in transfers.
- **Potential Impact**: Unintended disclosure of counterpart account identifiers and system architecture.
- **Mitigation Strategy**: Viewer-aware DTO mapping in `TransactionEntryResponse.fromEntity(entry, viewerUserId)`. For system accounts, `accountId` is `null` and `accountNumber` is masked as `"Cash/Bank Counterpart"`. For peer customers, `accountId` is `null` and `accountNumber` is masked as `"****1234"`. Customer-facing `AccountResponse` also strips `normalBalance`.
- **Implementation Evidence**: `TransactionEntryResponse.java`, `AccountResponse.java`
- **Verification**: `depositResponseMasksSystemCounterpart()`, `transferResponseMasksCounterpartForSenderAndReceiver()`, `accountResponseDoesNotExposeNormalBalance()`
- **Status**: MITIGATED
- **Residual Risk**: None.

---

#### SEC-CFG-002: Hardcoded Database Credentials & Lack of Separation
- **Threat ID**: `SEC-CFG-002`
- **Name**: Hardcoded Fallback Database Passwords in Configuration Files
- **Category**: Configuration Security
- **Severity**: High (Credential leakage)
- **Attack Scenario**: Configuration files committing default fallback passwords allow attackers gaining read access to the source code to authenticate against databases using default credentials.
- **Potential Impact**: Unauthorized database access.
- **Mitigation Strategy**: Removed all hardcoded fallback passwords from `application.properties` and `application-test.properties`. Configured mandatory environment variables `APP_DB_*` for runtime JPA and `FLYWAY_DB_*` for schema migrations.
- **Implementation Evidence**: `application.properties`, `application-test.properties`, `.env.example`
- **Verification**: Tested runtime application startup and test suite execution with environment variables.
- **Status**: MITIGATED
- **Residual Risk**: `.env` file must be kept in `.gitignore` and excluded from repository commits.

---

## 3. Threat-to-Control Traceability Matrix

| Threat ID | Name | Category | Severity | Status | Mitigating Component | Test Verification |
|---|---|---|---|---|---|---|
| `SEC-AUTH-001` | Password Brute-Force & Stuffing | Authentication | High | MITIGATED | `LoginRateLimiter` (Atomic sliding window), `AuthController` | `loginRateLimiterBlocksExcessiveAttemptsWith429` |
| `SEC-AUTH-002` | BCrypt 72-Byte Truncation | Authentication | High | MITIGATED | `@MaxUtf8Bytes`, `MaxUtf8BytesValidator` | `passwordExceeding72Utf8BytesRejectedOnRegistration` |
| `SEC-AUTH-003` | Session Fixation | Authentication | Critical | MITIGATED | `SessionFixationProtectionStrategy` | `sessionFixationProtectionRotatesSessionId` |
| `SEC-AUTH-004` | Session Interception / Leakage | Authentication | High | MITIGATED | Hardened Cookie Config (`HttpOnly`, `SameSite`) | `unauthenticatedAccessToProtectedEndpointsFails` |
| `SEC-AUTH-005` | Disabled User Active Session | Authentication | High | MITIGATED | `SessionUserValidationFilter`, `UserEntity.isEnabled()`, `AuthController` | `disabledUserCannotAuthenticate` |
| `SEC-AUTH-006` | Cross-Site Request Forgery | Authentication | High | MITIGATED | `CookieCsrfTokenRepository`, `CsrfFilter` | `csrfIsRequiredForStateChangingEndpoints` |
| `SEC-AUTHZ-001` | Broken Object-Level Auth (IDOR) | Authorization | Critical | MITIGATED | `AccountEntity.owner`, `resolveCustomerAccount` ownership check | `customerCannotAccessAnotherCustomerAccount` |
| `SEC-AUTHZ-002` | Role Privilege Escalation | Authorization | Critical | MITIGATED | Fixed DTO, Hardcoded `CUSTOMER` Role | `publicRegistrationCreatesCustomerRoleOnly` |
| `SEC-AUTHZ-003` | AUDITOR Write Bypass | Authorization | High | MITIGATED | Read-only `/api/v1/audit/logs`, removed fake endpoints | `auditorRoleHasReadOnlyAccessAndCannotMutate` |
| `SEC-AUTHZ-004` | Admin Ledger Bypass | Authorization | Critical | MITIGATED | DB Trigger `forbid_ledger_entry_mutation` | `ledgerEntriesAreStrictlyAppendOnly` |
| `SEC-AUTHZ-005` | Account Ownership Ambiguity | Authorization | High | MITIGATED | DB Check `accounts_ownership_policy_chk`, `@PrePersist` | `customerAccountRequiresOwnerAndSystemAccountRequiresNullOwner` |
| `SEC-FIN-001` | Race Condition / Double Spending | Financial | Critical | MITIGATED | `FOR UPDATE` lock, `accounts_customer_policy_chk` | `customerAccountCannotBecomeNegative` |
| `SEC-FIN-002` | Accounting Inversion Flaw | Financial | Critical | MITIGATED | `normal_balance` (DEBIT/CREDIT) & V5 Trigger | `depositIncreasesBothCashAssetAndCustomerLiability` |
| `SEC-FIN-003` | Unbalanced Ledger Entries | Financial | Critical | MITIGATED | Deferred DB Trigger `check_transaction_integrity` | `unbalancedTransactionFailsAtCommit` |
| `SEC-FIN-004` | Incomplete/Pending Balance Pollution | Financial | Critical | MITIGATED | Deferred DB Trigger `check_transaction_integrity` | `pendingTransactionWithEntriesCannotCommit` |
| `SEC-FIN-005` | Currency Inconsistency | Financial | High | MITIGATED | DB Triggers `validate_ledger_entry`, commit check | `currencyMismatchBetweenEntryAndAccountRejected` |
| `SEC-FIN-006` | Stale JPA Balance Reads | Financial | Medium | MITIGATED | `entityManager.refresh()` within transaction | `jpaRefreshesBalanceUpdatedByDatabaseTriggers` |
| `SEC-FIN-007` | Ledger Immutability Violation | Financial | Critical | MITIGATED | DB Trigger `forbid_ledger_entry_mutation` | `ledgerEntriesAreStrictlyAppendOnly` |
| `SEC-FIN-008` | Direct Balance Manipulation | Financial | Critical | MITIGATED | `PG_CONTEXT` Trigger guard, role column privileges | `accountBalanceProjectionCannotBeChangedDirectly` |
| `SEC-FIN-009` | Post-Creation Classification Tampering | Financial | Critical | MITIGATED | DB Trigger `protect_account_classification` | `accountClassificationCannotBeChangedOnceLedgerEntriesExist` |
| `SEC-DATA-001` | SQL Injection | Database | Critical | MITIGATED | Spring Data JPA Parameterized Queries | `duplicateEmailRegistrationFailsGracefully` |
| `SEC-DATA-002` | Audit Log Tampering | Database | High | MITIGATED | DB Trigger `audit_logs_immutability_trg` | `auditLogsAreImmutableInDatabase` |
| `SEC-DATA-003` | Test DB Operations on Production | Database | High | MITIGATED | `TestDatabaseGuard`, `application-test.properties`| Verified in all test executions |
| `SEC-DATA-004` | Database Over-Privileged User | Database | High | MITIGATED | Role separation (`ledgerline_app` vs `ledgerline_runtime`, V8/V9) | `runtimeRoleCatalogAttributesAssertLeastPrivilege`, `runtimeRoleTableGrantsAreStrictlyLimitedToSelectAndInsert` |
| `SEC-APP-001` | Information Disclosure in Errors | Application | Low | MITIGATED | `@RestControllerAdvice` `ApiExceptionHandler` | `loginRateLimiterBlocksExcessiveAttemptsWith429` |
| `SEC-APP-002` | Insecure Mass Assignment | Application | High | MITIGATED | Strict Request Records (`RegisterRequest`) | `publicRegistrationCreatesCustomerRoleOnly` |
| `SEC-APP-003` | Credential Leakage in Audit Logs | Application | High | MITIGATED | Sanitized `AuditService` Metadata Builder | `authenticationAuditLogsPersistCorrectly` |
| `SEC-APP-004` | Password Hash Exposure in Audit API | Application | Critical | MITIGATED | `AuditLogResponse` DTO with pagination | `auditLogsEndpointDoesNotExposePasswordHash` |
| `SEC-TX-001` | Concurrent Overdraft Race Condition | Financial Engine | Critical | MITIGATED | Deterministic `FOR UPDATE` lock, balance validation | `concurrentWithdrawalsPreventOverdraft` |
| `SEC-TX-002` | Bilateral Transfer Deadlock | Financial Engine | High | MITIGATED | Natural UUID sorted lock acquisition | `concurrentTransfersInOppositeDirectionsPreventDeadlock` |
| `SEC-TX-003` | Duplicate Request Replay | Financial Engine | Critical | MITIGATED | Advisory lock `pg_advisory_xact_lock`, SHA-256 fingerprint, idempotency key | `concurrentDuplicateRequestsExecuteOnlyOnce`, `sameKeySamePayloadReturnsOriginalResult` |
| `SEC-TX-004` | Idempotency Key Reuse Conflict | Financial Engine | High | MITIGATED | Payload fingerprint matching, HTTP 409 Conflict | `sameKeyDifferentPayloadReturnsConflict` |
| `SEC-TX-005` | Financial IDOR / Account Hijacking | Financial Engine | Critical | MITIGATED | Server-side principal ownership assertion | `customerCannotAccessAnotherCustomerAccount` |
| `SEC-TX-006` | System Cash Vault Extraction | Financial Engine | Critical | MITIGATED | System account type isolation, HTTP 403 | `customerCannotAccessSystemAccount` |
| `SEC-TX-007` | Circular Self-Transfer | Financial Engine | Low | MITIGATED | Source == Destination validation, HTTP 400 | `selfTransferIsRejected` |
| `SEC-TX-008` | AUDITOR Money Movement Escalation | Financial Engine | High | MITIGATED | Method-level RBAC (`hasAnyRole('CUSTOMER', 'ADMIN')`) | `auditorCannotInitiateMoneyMovement` |
| `SEC-DB-005` | Table Truncation & Destruction | Database | Critical | MITIGATED | Flyway V8 `REVOKE TRUNCATE, DELETE` from runtime role | `runtimeRoleCannotTruncateTables` |
| `SEC-DB-006` | User Role & Password Tampering | Database | Critical | MITIGATED | Flyway V8 column-restricted UPDATE `(status, updated_at)` | `runtimeRoleCannotPromoteUserRole`, `runtimeRoleCannotModifyPasswordHash` |
| `SEC-DB-007` | Negative Policy Tampering | Financial | High | MITIGATED | Flyway V8 revoked `UPDATE (allow_negative)` | `runtimeRoleCannotModifyAllowNegativeOrBalance` |
| `SEC-DB-008` | User INSERT Privilege Escalation | Database | Critical | MITIGATED | Flyway V9 trigger `trg_protect_user_role_assignment` | `runtimeRoleCannotInsertAdminOrAuditorRole` |
| `SEC-DB-009` | Opening Balance Fabrication | Financial | Critical | MITIGATED | Flyway V9 trigger `trg_protect_account_insertion` | `runtimeRoleCannotInsertFabricatedBalanceOrSystemAccount` |
| `SEC-IDEM-005` | Cross-Account Idempotency Race | Financial Engine | Critical | MITIGATED | PostgreSQL advisory lock `pg_advisory_xact_lock` on user+key | `sameUserSameKeyAcrossDifferentAccountsProducesOneOperation` |
| `SEC-IDEM-006` | Aborted Transaction Recovery | Financial Engine | High | MITIGATED | Advisory locking upfront, clean rollback | `sameKeySamePayloadReturnsOriginalResult` |
| `SEC-SYS-002` | Arbitrary System Account Misuse | Financial Integrity | High | MITIGATED | Strict validation of canonical `SYS-INR-CASH-VAULT-001` | `depositFailsIfSystemAccountMisconfigured` |
| `SEC-PRIV-002` | Counterpart Privacy Leakage | Data Privacy | Medium | MITIGATED | Viewer-aware DTO masking (`****1234`, null UUIDs) | `transferResponseMasksCounterpartForSenderAndReceiver` |
| `SEC-CFG-002` | Hardcoded Fallback Credentials | Configuration | High | MITIGATED | Removed fallback passwords, separate `APP_DB_*` and `FLYWAY_DB_*` | Verified runtime & migration startup |

---

## 4. Architectural Defense-in-Depth Summary

Vaultora adheres to the principle that **no single software layer is solely responsible for system security or financial integrity**. A defect, misconfiguration, or vulnerability in any one layer is intercepted and nullified by subsequent layers.

```
Request Flow & Defense Layers:
┌────────────────────────────────────────────────────────────────────────┐
│ 1. HTTP FILTER LAYER                                                  │
│    - Origin Verification & CORS filter                                 │
│    - CSRF Token verification filter (`CsrfFilter`)                     │
│    - In-memory rate limiting check (`LoginRateLimiter`)                │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. SPRING SECURITY & CONTROLLER ADVICE LAYER                          │
│    - Session fixation protection & ID rotation                         │
│    - Role-based authorization (`CUSTOMER`, `ADMIN`, `AUDITOR`)         │
│    - Password UTF-8 72-byte max length validation                      │
│    - Exception sanitization (`ApiExceptionHandler`)                    │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. SERVICE & DOMAIN LAYER                                             │
│    - Identity & ownership verification (user-to-account mapping)       │
│    - Audit logging with sanitization (`AuditService`)                  │
│    - Concurrency & persistence context management (`refresh`)          │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. DATABASE ROW-LEVEL TRIGGER LAYER                                    │
│    - `validate_ledger_entry`: verifies account active, currency match  │
│    - `apply_ledger_entry_to_balance`: row locks (`FOR UPDATE`),        │
│      applies normal balance formula (DEBIT vs. CREDIT)                 │
│    - `protect_account_balance_projection`: call-stack (PG_CONTEXT)     │
│      guard blocking direct SQL balance modifications                   │
│    - `protect_account_classification`: prevents changing account type, │
│      normal balance, or currency once ledger entries exist             │
│    - `forbid_ledger_entry_mutation`: rejects UPDATE & DELETE           │
│    - `audit_logs_immutability_trg`: rejects UPDATE & DELETE            │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 5. DATABASE COMMIT-TIME CONSTRAINT & CHECK LAYER                       │
│    - Deferred constraint triggers: zero-sum balance check              │
│    - Status validation: entries only allowed in COMPLETED transactions │
│    - Ownership policy: CUSTOMER requires owner; SYSTEM requires NULL   │
│    - Check constraints: customer account balance >= 0                  │
│    - Foreign keys and currency consistency constraints                 │
│    - Role privilege boundaries: `ledgerline_runtime` has no balance    │
│      UPDATE or ledger/audit modification grants                        │
└────────────────────────────────────────────────────────────────────────┘
```

Even if an attacker bypasses application-level authentication, gains administrative access, or injects raw queries into the persistence context, **PostgreSQL triggers and deferred commit constraints render it mathematically impossible to create unbalanced transactions, overdraft customer accounts, alter historical entries, or delete audit trails**.
