# Investigation & Development Interaction Log

## 1. 2026-11-08 - LedgerLine PostgreSQL and Spring Boot Build Failure

### Prompt & Context
Investigate build and startup failures for LedgerLine on Windows when attempting to connect Spring Boot to PostgreSQL 18.

### Diagnosis & Root Cause
- Investigated Git status, Maven configuration, Spring datasource and Flyway settings, Docker Compose configuration, environment files, test sources, and surefire reports.
- Verified that `ledgerline-postgres` was healthy, listening on host port 5432, using the persistent `ledgerline_data` volume, and serving `ledgerline_db` with the `ledgerline_app` role.
- Verified direct TCP authentication against PostgreSQL. The role credentials succeeded; the initial suspicion of an invalid password was ruled out.
- The actual root cause was PostgreSQL 18 rejecting the JVM timezone alias `Asia/Calcutta` transmitted by the PostgreSQL JDBC driver. The container environment uses `Etc/UTC`.

### Implementation & Corrections
- Configured the application to load the root `.env` file for local runs.
- Harmonized Docker Compose environment interpolation for `DB_PASSWORD`.
- Configured Maven to force UTC timezone for JVM processes and set default JVM timezone to UTC in `LedgerlineApplication.main()` prior to Spring Boot startup.

### Verification
- Direct TCP authentication passed.
- Complete Maven test suite passed.
- `.\mvnw.cmd clean package` passed.
- Packaged application launched successfully, connecting to PostgreSQL and completing Flyway validation. `/actuator/health` returned HTTP 200 `UP`.

### Commit Reference
- Commit: `f353dfc` (`chore: established Spring Boot backend and PostgreSQL setup`)
- Commit link: https://github.com/Mourya20/Vaultora-Banking-System/commit/f353dfc

---

## 2. 2026-11-08 - Phase 1 Database Foundation and Authentication

### Prompt & Context
Implement Phase 1 database foundation and session authentication for LedgerLine. The scope included Flyway migrations for core tables (`users`, `accounts`, `ledger_transactions`, `ledger_entries`, `audit_logs`), JPA entities, BCrypt password hashing, session authentication, CSRF protection, and integration tests.

### Implementation
- Added initial schema in `V1__initial_schema.sql`.
- Added forward-only migrations `V2__widen_currency_columns.sql` and `V3__widen_audit_fingerprint.sql` to resolve PostgreSQL `CHAR` vs. Hibernate string mapping constraints.
- Added `V4__ledger_database_protections.sql` to enforce audit log immutability.
- Implemented `UserEntity`, `AccountEntity`, `LedgerTransactionEntity`, `LedgerEntryEntity`, and `AuditLogEntity`.
- Implemented `AuthController` with `/register`, `/login`, `/me`, `/csrf`, and `/logout`.
- Configured Spring Security session management with HTTP-only cookies and CSRF repository.

### Verification
- Verified schema generation against PostgreSQL 18 container without deleting persistent volume data.
- Corrected account number length in test fixtures.
- Test suite executed 14 tests covering user registration, BCrypt hashing, duplicate email handling, login, CSRF rejection, disabled session rejection, and basic ledger checks.

### Commit Reference
- Commit: `6afeb88` (`feat: add database foundation and authentication`)
- Commit link: https://github.com/Mourya20/Vaultora-Banking-System/commit/6afeb88

---

## 3. 2026-11-08 - Authorization & Role-Based Access Control Architecture

### Prompt & Context
Design and verify role-based access control (RBAC) to ensure unauthenticated users and regular customers cannot perform administrative actions or escalate privileges.

### Architectural Decisions & Implementation
- Defined distinct user roles: `CUSTOMER`, `ADMIN`, `AUDITOR`.
- Enforced that public user registration strictly defaults to `UserRole.CUSTOMER`, preventing role specification in `RegisterRequest`.
- Configured Spring Security authorization rules in `SecurityConfig`:
  - Public access: `/api/v1/auth/register`, `/api/v1/auth/login`, `/api/v1/auth/csrf`, `/actuator/health`.
  - Authenticated access: `/api/v1/auth/me`, `/api/v1/auth/logout`.
  - Administrative endpoints (`/api/v1/admin/**`): Restricted exclusively to `ADMIN`.
  - Auditor access (`/api/v1/audit/**`): Read-only (`GET`) allowed for `AUDITOR` and `ADMIN`; mutations restricted to `ADMIN`.
- Built integration tests asserting that customers and auditors attempting to perform administrative actions receive HTTP 403 Forbidden.

### Verification
- Integration tests verified that registration payloads containing elevated role fields are ignored and strictly persisted as `CUSTOMER`.
- Verified HTTP 403 Forbidden on unauthorized endpoint access.

### Commit Reference
- Commit: `5b47420` (`test: cover public role escalation attempts`)
- Commit link: https://github.com/Mourya20/Vaultora-Banking-System/commit/5b47420

---

## 4. 2026-11-08 - Audit Trail Architecture & Immutability Enforcement

### Prompt & Context
Establish an immutable audit logging subsystem to satisfy financial auditability, compliance requirements, and operational monitoring.

### Architectural Decisions & Implementation
- Designed `audit_logs` schema table capturing `user_id`, `action`, `resource_type`, `resource_id`, `ip_address`, `metadata` (JSONB), and `fingerprint`.
- Enforced database-level immutability in migration `V4__ledger_database_protections.sql`:
  - Created trigger `audit_logs_immutability_trg` executing `forbid_audit_log_mutation()`.
  - Any SQL `UPDATE` or `DELETE` on `audit_logs` raises an immediate exception.
- Designed `AuditService` with transactional propagation `REQUIRES_NEW` to guarantee audit persistence even when the parent business transaction fails or rolls back.
- Enforced sensitive data sanitization: passwords, session cookies, and authentication tokens are filtered out before metadata serialization.

### Verification
- Executed direct database `UPDATE` and `DELETE` queries against `audit_logs` in integration tests, confirming PostgreSQL throws an exception and preserves records.
- Verified test fixture isolation and cleanup rules.

### Commit Reference
- Commit: `0275372` (`test: isolate PostgreSQL integration fixtures`)
- Commit link: https://github.com/Mourya20/Vaultora-Banking-System/commit/0275372

---

## 5. 2026-11-08 - Phase 1 Remediation: Accounting Model, Ledger Integrity, & Security Hardening

### Prompt & Context
Perform a complete remediation of Phase 1 based on an independent senior-developer audit finding 10 critical defects:
1. Pending/failed transactions could affect account balances.
2. Balance projection treated credits as universally positive and debits as negative regardless of account nature.
3. Integration tests passed superficially or had invalid fixture assumptions.
4. Custom authentication omitted explicit session fixation protection.
5. AUDITOR permissions lacked explicit read-only method restrictions.
6. BCrypt input length was not validated against the 72-byte truncation boundary.
7. Login rate limiting was absent.
8. Application-level audit event recording was incomplete.
9. Integration tests risked operating against development/production databases.
10. Documentation did not accurately reflect the implementation.

### Implementation & Remediation
- **Database Accounting Remediation (`V5__remediate_accounting_model_and_ledger_integrity.sql`)**:
  - Added `normal_balance VARCHAR(6) NOT NULL DEFAULT 'CREDIT'` and `allow_negative BOOLEAN NOT NULL DEFAULT FALSE` to `accounts`.
  - Replaced universal non-negative balance constraint with `accounts_customer_policy_chk` and `accounts_balance_policy_chk`.
  - Rewrote `apply_ledger_entry_to_balance()`:
    - Row-level locking with `SELECT ... FOR UPDATE` on `accounts`.
    - Normal balance formula: for `CREDIT` normal accounts, credit adds and debit subtracts; for `DEBIT` normal accounts, debit adds and credit subtracts.
  - Rewrote `validate_ledger_entry()` to forbid `UPDATE`/`DELETE` on `ledger_entries` (strictly append-only), forbid adding entries to completed transactions, and ensure currency matches account.
  - Added commit-time deferred constraint triggers (`ledger_transactions_integrity_at_commit` and `ledger_entries_transaction_integrity_at_commit`) enforcing:
    1. Any transaction with entries must be `COMPLETED`.
    2. Must contain at least one debit and one credit.
    3. Sum of debits must equal sum of credits.
    4. Currencies must match between transaction, entries, and accounts.
- **Java Domain & Persistence Synchronization**:
  - Added `NormalBalance` enum (`DEBIT`, `CREDIT`).
  - Added `AccountRepositoryCustom` and `AccountRepositoryCustomImpl.refresh()` using `entityManager.refresh()` to reload trigger-updated entity balances.
- **Security Hardening**:
  - Implemented `@MaxUtf8Bytes(max = 72)` and `MaxUtf8BytesValidator` to reject inputs over 72 UTF-8 bytes at request validation.
  - Implemented `LoginRateLimiter` using a thread-safe sliding window cache tracking IP and email attempts with eviction, returning HTTP 429 when threshold exceeded.
  - Configured `SessionFixationProtectionStrategy` in `SecurityConfig` and invoked `sessionAuthenticationStrategy.onAuthentication()` in `AuthController.login()` to guarantee session ID rotation.
  - Enforced AUDITOR read-only permissions: `GET /api/v1/audit/**` permitted; mutating requests (`POST /api/v1/audit/**`) strictly restricted to `ADMIN`.
  - Integrated `AuditService` across registration, login, logout, rate-limiting, and disabled user attempts with sensitive credential sanitization.
- **Test Database Isolation**:
  - Configured `application-test.properties` pointing to `ledgerline_test_db`.
  - Implemented `TestDatabaseGuard` checking `SELECT current_database() == 'ledgerline_test_db'` before executing test cleanup.
- **Threat Model Deliverable**:
  - Authored comprehensive root-level `THREATS.md` covering 22 discrete threats across 5 categories with a Threat-to-Control Traceability Matrix.

### Verification
- Executed `mvnw.cmd test` and `mvnw.cmd clean verify`.
- 36 tests executed, 0 failures, 0 errors, 0 skipped:
  - 26 integration tests in `Phase1IntegrationTests`
  - 5 rate limiter unit tests in `LoginRateLimiterTest`
  - 4 UTF-8 byte validator unit tests in `MaxUtf8BytesValidatorTest`
  - 1 application context test in `LedgerlineApplicationTests`
- Normal-balance mathematics verified with real PostgreSQL transactions:
  - Deposit: Asset account balance increases; Customer liability balance increases.
  - Withdrawal: Customer liability balance decreases; Asset account balance decreases.
  - Transfer: Sender liability balance decreases; Receiver liability balance increases.
- Deferred commit triggers verified: unbalanced transactions and pending transactions with entries correctly rejected at commit.

### Commit References
- Accounting remediation: `9e47971` (https://github.com/Mourya20/Vaultora-Banking-System/commit/9e47971)
- Security & audit hardening: `725cad4` (https://github.com/Mourya20/Vaultora-Banking-System/commit/725cad4)
- Test isolation & test suite: `af0c689` (https://github.com/Mourya20/Vaultora-Banking-System/commit/af0c689)
