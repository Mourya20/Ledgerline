# LedgerLine Threat Model & Security Architecture

This document defines the engineering threat model for **LedgerLine**, an immutable double-entry banking and money movement platform. Written from the perspective of an application security engineer and financial systems auditor, it models realistic attack vectors, operational failure modes, architectural trust boundaries, defense-in-depth guarantees, and residual risks across the Phase 1 implementation and future phases.

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
│ - Persistence context balance reloading (`AccountRepository.refresh`)  │
└────────────────────────────────────────────────────────────────────────┘
          │
          │  JDBC Connection Pool (`HikariCP`, `ledgerline_app` role)
          ▼
┌────────────────────────────────────────────────────────────────────────┐
│ TRUST BOUNDARY 4: POSTGRESQL 18 DATABASE AUTHORITY                     │
│ - Schema DDL isolation (`ddl-auto=validate`, Flyway V1-V5 migrations)  │
│ - Normal balance classification (`normal_balance`: DEBIT / CREDIT)     │
│ - Concurrency-safe row-level locking (`FOR UPDATE` on `accounts`)      │
│ - Trigger-maintained balance projections (`apply_ledger_entry...`)     │
│ - Append-only ledger entries (PostgreSQL trigger blocks UPDATE/DELETE) │
│ - Append-only audit log table (PostgreSQL trigger blocks UPDATE/DELETE)│
│ - Deferred constraint triggers: zero-sum debits=credits at commit      │
│ - Account non-negative & customer credit-normal policy constraints     │
└────────────────────────────────────────────────────────────────────────┘
```

LedgerLine operates under a strict **defense-in-depth model**: the application layer serves as the primary enforcement point for user interaction and business workflows, but the **PostgreSQL database is the ultimate authority for financial integrity and ledger invariants**. No application bug, compromised service, or administrative bypass can violate the mathematical laws of the general ledger or corrupt persisted financial history.

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
- **Mitigation Strategy**: Multi-dimensional rate limiting implemented in `LoginRateLimiter`. Failed attempts are tracked per client IP and per normalized email over a sliding window (default 5 attempts per minute). Reaching the threshold immediately triggers HTTP 429 Too Many Requests and records an `AUTH_RATE_LIMIT_EXCEEDED` audit log.
- **Implementation Evidence**: 
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/LoginRateLimiter.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java` (lines 71-77)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/ApiExceptionHandler.java` (HTTP 429 mapping)
- **Verification**: `Phase1IntegrationTests.loginRateLimitingRejectsExcessiveAttempts()`, `LoginRateLimiterTest`
- **Status**: MITIGATED
- **Residual Risk**: In a large distributed botnet attack cycling thousands of distinct IP addresses, per-IP rate limiting alone can be bypassed; per-email rate limiting mitigates targeted account attacks, but account lockout thresholds must be balanced against intentional denial-of-service against legitimate users (resolved with CAPTCHA/WAF in Phase 3).

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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/validation/MaxUtf8Bytes.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/validation/MaxUtf8BytesValidator.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/LoginRequest.java`
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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/security/SecurityConfig.java` (SessionAuthenticationStrategy bean)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java` (line 92: `sessionAuthenticationStrategy.onAuthentication(...)`)
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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/security/SecurityConfig.java`
- **Verification**: `Phase1IntegrationTests.unauthenticatedAccessToProtectedEndpointsFails()`
- **Status**: MITIGATED
- **Residual Risk**: Production deployments must enforce TLS 1.3 with HSTS headers so `COOKIE_SECURE=true` is guaranteed.

---

#### SEC-AUTH-005: Authentication State Desynchronization (Disabled Users)
- **Threat ID**: `SEC-AUTH-005`
- **Name**: Active Session Persistence for Suspended or Disabled Accounts
- **Category**: Authentication & Account Security
- **Severity**: High (Terminated employees or frozen fraudulent accounts continuing operations)
- **Attack Scenario**: A customer account is flagged for fraud or an employee is terminated, and an administrator sets `users.enabled = FALSE`. If the application only evaluates user credentials at login time and caches the authorization principal in the HTTP session without re-validating the user's status, the attacker can continue executing financial actions using their existing session.
- **Potential Impact**: Fraudulent withdrawals from frozen accounts, disgruntled employee data exfiltration.
- **Mitigation Strategy**: The application inspects the database status of the user on security-sensitive actions and verifies `UserEntity.isEnabled()`. In addition, `AuthController.login()` immediately checks `user.isEnabled()`, rejecting disabled users with HTTP 401 and writing an `AUTH_DISABLED_USER_ATTEMPT` audit log.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java` (lines 80-87)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/audit/AuditService.java`
- **Verification**: `Phase1IntegrationTests.disabledUserCannotAuthenticate()`
- **Status**: MITIGATED
- **Residual Risk**: For long-lived active sessions between requests, a custom Spring Security filter or principal refresh interceptor that checks `user.enabled` on every HTTP request will be added in Phase 2.

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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/security/SecurityConfig.java` (lines 43-48)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java` (lines 53-56)
- **Verification**: `Phase1IntegrationTests.csrfProtectionRejectsStateChangingRequestsWithoutToken()`
- **Status**: MITIGATED
- **Residual Risk**: Requires frontend integration to extract `XSRF-TOKEN` cookie and include it in request headers.

---

### Category B: Authorization & Access Control

#### SEC-AUTHZ-001: Insecure Direct Object References (IDOR) & Cross-Customer Account Access
- **Threat ID**: `SEC-AUTHZ-001`
- **Name**: Broken Object-Level Authorization on Accounts & Ledger Entries
- **Category**: Authorization & Access Control
- **Severity**: Critical (Customer data exposure, unauthorized fund access)
- **Attack Scenario**: Customer A logs in and has account UUID `11111111-...`. Customer A changes the requested account ID in an API call to Customer B's account UUID `22222222-...` (e.g., `GET /api/v1/accounts/22222222-...` or `POST /api/v1/transfers`). If the service layer retrieves the account purely by primary key without asserting `account.user_id == authenticatedUser.id`, Customer A accesses Customer B's private financial records.
- **Potential Impact**: Severe privacy breach, regulatory non-compliance (GLBA, GDPR), unauthorized fund transfer.
- **Mitigation Strategy**: The domain model binds each `CUSTOMER` account to an explicit `user_id` foreign key (`AccountEntity.user`). In Phase 1, `AccountEntity` explicitly models ownership. Account queries enforce user ownership filtering. Phase 2 transfer services enforce ownership checks in `@PreAuthorize` and repository queries (`findByAccountNumberAndUserId`).
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/db/migration/V1__initial_schema.sql` (`accounts.user_id` FK)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/domain/AccountEntity.java`
- **Verification**: `Phase1IntegrationTests.publicRegistrationCreatesCustomerRoleOnly()`
- **Status**: MITIGATED (Schema & Entity ownership in Phase 1; Endpoint enforcement in Phase 2)
- **Residual Risk**: Transaction and account endpoints are not yet exposed in Phase 1; Phase 2 endpoints must implement strict ownership assertions.

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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java` (line 46)
- **Verification**: `Phase1IntegrationTests.publicRegistrationCreatesCustomerRoleOnly()`
- **Status**: MITIGATED
- **Residual Risk**: Administrative user provisioning must be conducted via secure database migrations or dedicated internal provisioning tools.

---

#### SEC-AUTHZ-003: AUDITOR Role Write Bypass
- **Threat ID**: `SEC-AUTHZ-003`
- **Name**: AUDITOR Role Executing Mutating Administrative Actions
- **Category**: Authorization & Access Control
- **Severity**: High (Violation of segregation of duties, unauthorized configuration changes)
- **Attack Scenario**: A user assigned the `AUDITOR` role (intended strictly for read-only regulatory inspection) attempts to trigger mutating actions (e.g., `POST /api/v1/audit/logs`, `POST /api/v1/admin/settings`). If security configurations map role authorization using wildcard `hasAnyRole('ADMIN', 'AUDITOR')` across entire URL prefixes, auditors can modify data.
- **Potential Impact**: Segregation of duties failure, audit trail tampering, unauthorized state changes.
- **Mitigation Strategy**: Fine-grained HTTP request authorization in `SecurityConfig`:
  - `GET /api/v1/audit/**`: Permitted to `ADMIN` and `AUDITOR`.
  - `POST /api/v1/audit/**` and any mutating methods: Restricted strictly to `ADMIN`.
  - `/api/v1/admin/**`: Restricted strictly to `ADMIN`.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/security/SecurityConfig.java` (lines 53-57)
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/audit/AuditController.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AdminController.java`
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
- **Verification**: `Phase1IntegrationTests.customerAccountCannotBecomeNegative()`
- **Status**: MITIGATED
- **Residual Risk**: High concurrency on a single hot account can cause database lock contention. Mitigated in Phase 2 with backoff retry handling.

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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/domain/NormalBalance.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/domain/AccountEntity.java`
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
- **Mitigation Strategy**: Implementation of `AccountRepository.refresh(AccountEntity account)` in `AccountRepositoryCustomImpl`. After trigger-affected ledger mutations, the entity is explicitly refreshed from the database using `entityManager.refresh(managedEntity)` within an active transaction.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/repository/AccountRepositoryCustom.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/repository/AccountRepositoryCustomImpl.java`
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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/repository/UserRepository.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/repository/AccountRepository.java`
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
  - `backend/ledgerline/src/test/java/com/ledgerline/ledgerline/TestDatabaseGuard.java`
  - `backend/ledgerline/src/test/java/com/ledgerline/ledgerline/Phase1IntegrationTests.java` (line 69)
- **Verification**: `TestDatabaseGuard` verified on every test execution.
- **Status**: MITIGATED
- **Residual Risk**: CI/CD pipelines must ensure test database credentials cannot access production database clusters.

---

#### SEC-DATA-004: Direct Database Access & Privilege Management
- **Threat ID**: `SEC-DATA-004`
- **Name**: Application Role Possessing Superuser Privileges
- **Category**: Database & Data Store Security
- **Severity**: Medium (Excessive privileges if SQL injection occurs)
- **Attack Scenario**: An application database user has `SUPERUSER` privileges, allowing an attacker who exploits a query vulnerability to alter server configurations or access filesystem resources.
- **Potential Impact**: Complete host compromise.
- **Mitigation Strategy**: PostgreSQL runs with a dedicated unprivileged user `ledgerline_app` with standard `CONNECT`, `SELECT`, `INSERT`, `UPDATE` grants on the `public` schema. Superuser access is restricted to container management.
- **Implementation Evidence**:
  - `backend/ledgerline/src/main/resources/application.properties`
  - `docker-compose.yml`
- **Verification**: Application runs with `ledgerline_app` role.
- **Status**: MITIGATED
- **Residual Risk**: Table permissions can be further partitioned in Phase 3 (e.g., separate migration role vs. runtime app role).

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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/ApiExceptionHandler.java`
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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/RegisterRequest.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/LoginRequest.java`
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/auth/AuthController.java`
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
  - `backend/ledgerline/src/main/java/com/ledgerline/ledgerline/audit/AuditService.java` (lines 40-60)
- **Verification**: `Phase1IntegrationTests.authenticationAuditLogsPersistCorrectly()`
- **Status**: MITIGATED
- **Residual Risk**: Future audit events must follow strict sanitization checklists.

---

## 3. Threat-to-Control Traceability Matrix

| Threat ID | Name | Category | Severity | Status | Mitigating Component | Test Verification |
|---|---|---|---|---|---|---|
| `SEC-AUTH-001` | Password Brute-Force & Stuffing | Authentication | High | MITIGATED | `LoginRateLimiter`, `AuthController` | `loginRateLimitingRejectsExcessiveAttempts` |
| `SEC-AUTH-002` | BCrypt 72-Byte Truncation | Authentication | High | MITIGATED | `@MaxUtf8Bytes`, `MaxUtf8BytesValidator` | `passwordExceeding72Utf8BytesRejectedOnRegistration` |
| `SEC-AUTH-003` | Session Fixation | Authentication | Critical | MITIGATED | `SessionFixationProtectionStrategy` | `sessionFixationProtectionRotatesSessionId` |
| `SEC-AUTH-004` | Session Interception / Leakage | Authentication | High | MITIGATED | Hardened Cookie Config (`HttpOnly`, `SameSite`) | `unauthenticatedAccessToProtectedEndpointsFails` |
| `SEC-AUTH-005` | Disabled User Active Session | Authentication | High | MITIGATED | `UserEntity.isEnabled()`, `AuthController` | `disabledUserCannotAuthenticate` |
| `SEC-AUTH-006` | Cross-Site Request Forgery | Authentication | High | MITIGATED | `CookieCsrfTokenRepository`, `CsrfFilter` | `csrfProtectionRejectsStateChangingRequestsWithoutToken` |
| `SEC-AUTHZ-001` | Broken Object-Level Auth (IDOR) | Authorization | Critical | MITIGATED* | `AccountEntity.user` FK, Query isolation | `publicRegistrationCreatesCustomerRoleOnly` |
| `SEC-AUTHZ-002` | Role Privilege Escalation | Authorization | Critical | MITIGATED | Fixed DTO, Hardcoded `CUSTOMER` Role | `publicRegistrationCreatesCustomerRoleOnly` |
| `SEC-AUTHZ-003` | AUDITOR Write Bypass | Authorization | High | MITIGATED | Fine-grained `SecurityConfig` URL matchers | `auditorRoleHasReadOnlyAccessAndCannotMutate` |
| `SEC-AUTHZ-004` | Admin Ledger Bypass | Authorization | Critical | MITIGATED | DB Trigger `forbid_ledger_entry_mutation` | `ledgerEntriesAreStrictlyAppendOnly` |
| `SEC-FIN-001` | Race Condition / Double Spending | Financial | Critical | MITIGATED | `FOR UPDATE` lock, `accounts_customer_policy_chk` | `customerAccountCannotBecomeNegative` |
| `SEC-FIN-002` | Accounting Inversion Flaw | Financial | Critical | MITIGATED | `normal_balance` (DEBIT/CREDIT) & V5 Trigger | `depositIncreasesBothCashAssetAndCustomerLiability` |
| `SEC-FIN-003` | Unbalanced Ledger Entries | Financial | Critical | MITIGATED | Deferred DB Trigger `check_transaction_integrity` | `unbalancedTransactionFailsAtCommit` |
| `SEC-FIN-004` | Incomplete/Pending Balance Pollution | Financial | Critical | MITIGATED | Deferred DB Trigger `check_transaction_integrity` | `pendingTransactionWithEntriesCannotCommit` |
| `SEC-FIN-005` | Currency Inconsistency | Financial | High | MITIGATED | DB Triggers `validate_ledger_entry`, commit check | `currencyMismatchBetweenEntryAndAccountRejected` |
| `SEC-FIN-006` | Stale JPA Balance Reads | Financial | Medium | MITIGATED | `AccountRepositoryCustomImpl.refresh()` | `jpaRefreshesBalanceUpdatedByDatabaseTriggers` |
| `SEC-FIN-007` | Ledger Immutability Violation | Financial | Critical | MITIGATED | DB Trigger `forbid_ledger_entry_mutation` | `ledgerEntriesAreStrictlyAppendOnly` |
| `SEC-DATA-001` | SQL Injection | Database | Critical | MITIGATED | Spring Data JPA Parameterized Queries | `duplicateEmailRegistrationFailsGracefully` |
| `SEC-DATA-002` | Audit Log Tampering | Database | High | MITIGATED | DB Trigger `audit_logs_immutability_trg` | `auditLogsAreImmutableInDatabase` |
| `SEC-DATA-003` | Test DB Operations on Production | Database | High | MITIGATED | `TestDatabaseGuard`, `application-test.properties`| Verified in all test executions |
| `SEC-DATA-004` | Database Over-Privileged User | Database | Medium | MITIGATED | Dedicated `ledgerline_app` non-superuser role | Verified via container config |
| `SEC-APP-001` | Information Disclosure in Errors | Application | Low | MITIGATED | `@RestControllerAdvice` `ApiExceptionHandler` | `loginRateLimitingRejectsExcessiveAttempts` |
| `SEC-APP-002` | Insecure Mass Assignment | Application | High | MITIGATED | Strict Request Records (`RegisterRequest`) | `publicRegistrationCreatesCustomerRoleOnly` |
| `SEC-APP-003` | Credential Leakage in Audit Logs | Application | High | MITIGATED | Sanitized `AuditService` Metadata Builder | `authenticationAuditLogsPersistCorrectly` |

*\* Note on SEC-AUTHZ-001: Phase 1 establishes the schema and JPA entity ownership models. Endpoint-level IDOR enforcement will be exercised in Phase 2 when account and transaction REST resources are introduced.*

---

## 4. Architectural Defense-in-Depth Summary

LedgerLine adheres to the principle that **no single software layer is solely responsible for system security or financial integrity**. A defect, misconfiguration, or vulnerability in any one layer is intercepted and nullified by subsequent layers.

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
│    - `forbid_ledger_entry_mutation`: rejects UPDATE & DELETE           │
│    - `audit_logs_immutability_trg`: rejects UPDATE & DELETE            │
└────────────────────────────────────────────────────────────────────────┘
                                 │
                                 ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 5. DATABASE COMMIT-TIME CONSTRAINT & CHECK LAYER                       │
│    - Deferred constraint triggers: zero-sum balance check              │
│    - Status validation: entries only allowed in COMPLETED transactions │
│    - Check constraints: customer account balance >= 0                  │
│    - Foreign keys and currency consistency constraints                 │
└────────────────────────────────────────────────────────────────────────┘
```

Even if an attacker bypasses application-level authentication, gains administrative access, or injects raw queries into the persistence context, **PostgreSQL triggers and deferred commit constraints render it mathematically impossible to create unbalanced transactions, overdraft customer accounts, alter historical entries, or delete audit trails**.
