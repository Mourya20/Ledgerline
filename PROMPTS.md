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
- Commit link: https://github.com/Mourya20/Ledgerline/commit/f353dfc

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
- Commit link: https://github.com/Mourya20/Ledgerline/commit/6afeb88

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
- Commit link: https://github.com/Mourya20/Ledgerline/commit/5b47420

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
- Commit link: https://github.com/Mourya20/Ledgerline/commit/0275372

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
- Accounting remediation: `9e47971` (https://github.com/Mourya20/Ledgerline/commit/9e47971)
- Security & audit hardening: `725cad4` (https://github.com/Mourya20/Ledgerline/commit/725cad4)
- Test isolation & test suite: `af0c689` (https://github.com/Mourya20/Ledgerline/commit/af0c689)

---

## 6. 2026-11-08 - Senior Developer Audit Remediation, Package Simplification, & Database Security Architecture

### Prompt & Context
Execute comprehensive Phase 1 code cleanup, security hardening, and architectural remediation based on senior-developer audit findings:
1. Simplify duplicated Java package structure (`com.ledgerline.ledgerline` to `com.ledgerline`).
2. Prevent password hash exposure through audit APIs; introduce paginated `AuditLogResponse` DTO and remove fake endpoints (`POST /api/v1/audit/logs`, `AdminController`).
3. Correct audit JSONB mapping to store structured JSON objects validated via `jsonb_typeof(details) = 'object'`.
4. Enforce account ownership constraints at DB level: `CUSTOMER` accounts must have an owner; `SYSTEM` accounts must have NULL owner.
5. Correct balance projection reconciliation migration, rejecting non-zero balances lacking ledger entries.
6. Remove unsafe session-variable balance authorization (`ledgerline.balance_update`); introduce least-privilege runtime role `ledgerline_runtime` and call-stack introspection (`PG_CONTEXT`) trigger guards.
7. Protect account classification (`account_type`, `normal_balance`, `currency`) once ledger entries exist.
8. Make login rate limiting concurrency-safe with atomic attempt reservation, `Retry-After` response headers, and shared IP counter preservation.
9. Remove redundant repository abstractions (`AccountRepositoryCustom`/`AccountRepositoryCustomImpl`) and redundant `flyway-core` dependencies.
10. Remove misleading `REVERSED` transaction status (strictly `PENDING`, `COMPLETED`, `FAILED`).

### Implementation & Remediation
- **Package Restructuring**:
  - Restructured all Java production and test classes from `com.ledgerline.ledgerline.*` to `com.ledgerline.*`.
  - Removed obsolete nested directories and verified zero broken package references across the working tree.
- **Audit Response DTO & Endpoint Cleanup**:
  - Created `AuditLogResponse` record exposing strictly `id`, `actorUserId`, `action`, `resourceType`, `resourceId`, `details`, and `createdAt`.
  - Updated `AuditController` to return `Page<AuditLogResponse>` with Spring Data `Pageable`.
  - Removed fake non-functional `POST /api/v1/audit/logs` endpoint.
  - Removed placeholder `AdminController`.
  - Corrected `AuditLogEntity.details` to map directly as `Map<String, Object>` via `@JdbcTypeCode(SqlTypes.JSON)`.
- **Database Schema Migration `V6` (`V6__account_ownership_immutability_and_privileges.sql`)**:
  - Check constraint `accounts_ownership_policy_chk`: `(CUSTOMER AND owner_user_id IS NOT NULL) OR (SYSTEM AND owner_user_id IS NULL)`.
  - Check constraint `ledger_transactions_status_chk`: strictly `PENDING`, `COMPLETED`, `FAILED` (eliminated `REVERSED`).
  - Balance reconciliation block recalculating projections from entries and failing if non-zero balances exist with zero entries.
  - Trigger `protect_account_classification()`: rejects updates to `account_type`, `normal_balance`, or `currency` if ledger entries exist.
  - Trigger `protect_account_balance_projection()`: inspects `GET DIAGNOSTICS v_context = PG_CONTEXT` to reject any balance update outside `apply_ledger_entry_to_balance`.
  - Created least-privilege role `ledgerline_runtime` lacking `UPDATE (balance)` on `accounts` and lacking `UPDATE`/`DELETE` on `ledger_entries` and `audit_logs`.
- **Domain & Application Logic**:
  - Added `@PrePersist` and `@PreUpdate` ownership checks to `AccountEntity`.
  - Removed `REVERSED` from `TransactionStatus`.
  - Removed `AccountRepositoryCustom` and `AccountRepositoryCustomImpl`; updated persistence context usage to call `entityManager.refresh()` directly.
  - Removed explicit redundant `flyway-core` dependency from `pom.xml`.
- **Rate Limiting Hardening**:
  - Enhanced `LoginRateLimiter`: atomic attempt reservation (`tryReserve` inside synchronized `compute`), atomic rollback on partial failure, bounded capacity (10,000 keys) with LRU eviction, string bounds truncation (IP 64 chars, email 255 chars).
  - Preserved shared IP counter upon successful authentication while clearing only the user email counter.
  - Added `retryAfterSeconds` to `RateLimitExceededException` and populated HTTP `Retry-After` header in `ApiExceptionHandler`.
- **Configuration**:
  - Configured `application.properties` and `application-test.properties` with separate Flyway migration owner (`ledgerline_app`) and runtime connection (`ledgerline_runtime`).

### Verification
- Executed `.\mvnw.cmd test` and `.\mvnw.cmd clean verify`.
- 41 total tests executed, 0 failures, 0 errors, 0 skipped:
  - 31 integration tests in `Phase1IntegrationTests`
  - 5 rate limiter unit tests in `LoginRateLimiterTest`
  - 4 UTF-8 byte validator unit tests in `MaxUtf8BytesValidatorTest`
  - 1 application context test in `LedgerlineApplicationTests`
- Clean JAR packaging succeeded.

### Commit References
- Package restructuring: `fc7418f` (`refactor: simplify duplicated Java package structure`)

---

## 6. 2026-11-08 - Phase 2 Financial Transaction Engine Implementation

### Prompt & Context
Implement Phase 2: the complete financial transaction engine for LedgerLine on branch `feature/phase-2-financial-engine`. The engine must support authenticated customer account management, simulated double-entry cash deposits and withdrawals, peer-to-peer customer transfers, atomic execution, robust idempotency, deterministic concurrency control, ownership enforcement, transaction history, and comprehensive multithreaded integration tests against PostgreSQL 18. Git commits and staging are strictly reserved for the user.

### Architectural Decisions & Implementation
- **Branch Management**:
  - Confirmed and checked out `feature/phase-2-financial-engine`. All changes kept unstaged/uncommitted in the working tree.
- **Database Schema Migration `V7` (`V7__provision_system_accounts_and_financial_engine_indexes.sql`)**:
  - Seeded operational `SYSTEM` cash asset vault account for INR (`id = '00000000-0000-0000-0000-000000000001'`, `account_number = 'SYS-INR-CASH-VAULT-001'`, `currency = 'INR'`, `normal_balance = 'DEBIT'`).
  - Added performance indexes for transaction timestamp retrieval (`ledger_transactions(created_at DESC)`), account ledger query ordering (`ledger_entries(account_id, created_at DESC)`), and debit/credit entry aggregations (`ledger_entries(account_id, entry_type)`).
- **Core Domain & Account Management**:
  - Implemented `AccountService` and `AccountController`: authenticated account creation, backend generation of unique 16-character account numbers (`VLTR` + 12 random digits), strict currency restriction ("INR"), default `CUSTOMER` classification and `CREDIT` normal balance, zero initial balance projection.
  - Implemented account balance retrieval (`GET /api/v1/accounts/{id}/balance`) and list retrieval (`GET /api/v1/accounts`) with strict server-side owner-user filtering.
  - Enforced system account isolation: customer access to `SYSTEM` accounts is rejected with HTTP 403 Forbidden.
- **Double-Entry Financial Operations (`TransactionService` & `TransactionController`)**:
  - **Deposit (`POST /api/v1/transactions/deposit`)**: Resolves customer liability account and internal system cash asset counterpart; acquires row-level locks; creates `DEPOSIT` transaction; inserts balanced entries (System Cash Vault `DEBIT`, Customer Liability `CREDIT`); trigger increments both balances; persists `TRANSACTION_DEPOSIT` audit log.
  - **Withdrawal (`POST /api/v1/transactions/withdraw`)**: Acquires row-level locks; validates customer balance >= amount and system counterpart liquidity >= amount under lock; inserts balanced entries (Customer Liability `DEBIT`, System Cash Vault `CREDIT`); trigger decrements both balances; persists `TRANSACTION_WITHDRAW` audit log; rejects overdrafts with HTTP 422 Unprocessable Content.
  - **Transfer (`POST /api/v1/transactions/transfer`)**: Resolves source and destination customer accounts; rejects self-transfers, cross-currency transfers, and system account destinations; acquires row locks; validates sender balance under lock; inserts balanced entries (Sender `DEBIT`, Receiver `CREDIT`); leaves system cash vault untouched; persists `TRANSACTION_TRANSFER` audit log.
- **Deterministic Concurrency & Deadlock Prevention**:
  - Multi-account locking method `lockAccountsInDeterministicOrder(UUID id1, UUID id2)` sorts participating account IDs in natural ascending UUID order before issuing `accountRepository.findByIdForUpdate(id)` (`SELECT ... FOR UPDATE`). Eliminates database deadlocks during simultaneous opposing-direction transfers (A->B and B->A).
- **Concurrency-Safe Idempotency Engine**:
  - Mandatory `Idempotency-Key` header with SHA-256 request payload fingerprinting.
  - Double-checked locking pattern: When concurrent requests race with the same key, the second thread blocks on the account lock; upon waking after the first commits, a post-lock idempotency re-check detects the newly committed transaction and immediately returns the original response with zero duplicate ledger entries.
  - Payload mutation with the same key is detected and rejected with HTTP 409 Conflict.
- **Security & RBAC Enforcement**:
  - Allowed `Idempotency-Key` in CORS exposed headers.
  - Restricted `POST /api/v1/transactions/**` and `POST /api/v1/accounts/**` to `hasAnyRole('CUSTOMER', 'ADMIN')`, explicitly blocking the `AUDITOR` role from creating accounts or moving funds.

### Verification
- Executed `Phase2FinancialEngineIntegrationTests` with 31 integration and multithreaded concurrency tests against real PostgreSQL 18.
- Verified concurrent double-withdrawal overdraft prevention: 2 threads each requesting ₹800 against ₹1,000 balance -> exactly 1 succeeds, 1 rejected with 422, final balance ₹200.
- Verified bilateral deadlock prevention: 10 concurrent opposing transfers between A and B complete with 0 deadlocks and exact balance preservation.
- Verified concurrent duplicate requests: 2 threads with identical idempotency key execute exactly once, returning identical transaction IDs with 0 duplicate ledger entries.
- Verified financial reconciliation: global `SUM(DEBIT) == SUM(CREDIT)` across all ledger entries, and every account's projected `balance` strictly equals `SUM(entries)` directly calculated in SQL.
- Executed full test suite across the application (`mvnw.cmd test`): **72 total tests run, 0 failures, 0 errors, 0 skipped**.
- Full build and package check (`mvnw.cmd clean verify`) succeeded.

---

## 7. 2026-11-08 - Phase 2 Remediation: Financial Integrity, Database Security, Idempotency & Privacy

### Prompt & Context
Execute comprehensive remediation of Phase 2 financial engine on branch `feature/phase-2-financial-engine`:
1. Harden database privileges: Revoke excessive runtime permissions granted to `ledgerline_runtime` in V6 (TRUNCATE, DELETE, unrestricted UPDATE on users, role promotion, password tampering, `allow_negative`).
2. Remove hardcoded database passwords; separate runtime JPA (`APP_DB_*`) and Flyway migration (`FLYWAY_DB_*`) credentials.
3. Make concurrent idempotency handling reliable: Replace post-collision queries in aborted PostgreSQL transactions with transaction-scoped advisory locking (`pg_advisory_xact_lock`), canonical scale-2 fingerprinting, and cross-account serialization.
4. Validate system counterpart accounts: Remove arbitrary fallback selection; strictly enforce canonical `SYS-INR-CASH-VAULT-001` properties (SYSTEM, null owner, INR, DEBIT normal, ACTIVE, allow_negative=false).
5. Protect transaction response privacy: Viewer-aware transaction responses masking system accounts as `"Cash/Bank Counterpart"` (null UUID) and counterpart customers as `"****1234"` (null UUID); remove `normalBalance` from customer `AccountResponse`.
6. Strengthen input validation: Scale-2 amount validation, uppercase currency check, max 255-char key validation.
7. Optimize transaction history: Eliminate N+1 entry queries using batch retrieval (`findAllByTransactionIdIn`).
8. Correct account-number generation: Replace `Math.abs(RANDOM.nextLong())` (which overflows on `Long.MIN_VALUE`) with bounded `RANDOM.nextLong(100_000_000_000L, 1_000_000_000_000L)`.
9. Expand test coverage and update documentation. All changes must remain unstaged and uncommitted.

### Architectural Decisions & Implementation
- **Database Privilege Hardening (`V8__harden_runtime_database_privileges.sql`)**:
  - Revoked `TRUNCATE` across all tables from `ledgerline_runtime`.
  - Revoked `DELETE` across all tables from `ledgerline_runtime`.
  - Column-restricted `UPDATE` on `users` strictly to `(status, updated_at)` (eliminates role escalation and direct password hash manipulation).
  - Column-restricted `UPDATE` on `accounts` strictly to `(status, updated_at)` (revokes `allow_negative`, blocks arbitrary balance tampering).
  - Column-restricted `UPDATE` on `ledger_transactions` to `(status, completed_at)`.
  - Reaffirmed append-only `ledger_entries` and `audit_logs` (all `UPDATE` and `DELETE` revoked).
  - Revoked `CREATE` on schema `public`.
  - Added DO block validating canonical properties of `SYS-INR-CASH-VAULT-001`.
- **Database Credential Configuration**:
  - Removed all hardcoded fallback passwords from `application.properties` and `application-test.properties`.
  - Configured mandatory environment variables `APP_DB_*` (runtime) and `FLYWAY_DB_*` (schema migrations).
- **PostgreSQL Transaction-Scoped Advisory Locking (`TransactionService.java`)**:
  - Implemented `acquireIdempotencyAdvisoryLock` using `pg_advisory_xact_lock(bigint)` on SHA-256 hash of `userId + ":" + normalizedKey`.
  - Pre-reserves key at transaction start, serializing duplicate requests even across different accounts for the same user without encountering abort-inducing uniqueness collisions.
  - Normalized monetary amount to scale 2 (`amount.setScale(2, RoundingMode.UNNECESSARY)`) and currency with `Locale.ROOT` for canonical request fingerprinting.
  - Eliminated post-collision recovery query that attempted to query inside an aborted transaction.
- **Strict System Counterpart Validation (`TransactionService.java`)**:
  - Eliminated arbitrary fallback selection; strictly queries canonical `SYS-{currency}-CASH-VAULT-001`.
  - Enforced exact properties: `accountType == SYSTEM`, `owner == null`, currency match, `normalBalance == DEBIT`, `status == ACTIVE`, `allowNegative == false`.
- **Viewer-Aware Response Privacy**:
  - Updated `TransactionEntryResponse.fromEntity(entry, viewerUserId)`:
    - System accounts: `accountId = null`, `accountNumber = "Cash/Bank Counterpart"`.
    - Other customer accounts: `accountId = null`, `accountNumber = "****" + last4`.
    - Own customer account: full `accountId` and full `accountNumber`.
  - Removed `NormalBalance normalBalance` from customer `AccountResponse`.
- **Query Optimization & Secure Random**:
  - Added batch query `findAllByTransactionIdIn` in `LedgerEntryRepository` with JOIN FETCH on account and owner, grouping entries by transaction ID and eliminating N+1 queries. Capped page size at 100.
  - Replaced `Math.abs(RANDOM.nextLong())` in `AccountService` with `SecureRandom.nextLong(100_000_000_000L, 1_000_000_000_000L)`.

### Verification
- Executed `Phase2FinancialEngineIntegrationTests` against `ledgerline_test_db`: 43 tests passed.
  - Verified runtime database privileges via direct JDBC connection as `ledgerline_runtime`: TRUNCATE, user role promotion, password tampering, `allow_negative` tampering, balance tampering, and entry deletion all rejected with SQL exception 42501.
  - Verified cross-account idempotency serialization: duplicate request with same key across different accounts rejected with 409 Conflict; exactly one financial operation executed.
  - Verified canonical fingerprint matching (100.0 vs 100.00).
  - Verified response privacy masking for sender, receiver, and system cash vault.
  - Verified strict system counterpart property validation.
  - Verified validation rejections for scale > 2, negative amount, key > 255 chars, invalid currency.
- Executed full Maven test suite (`mvnw.cmd test`): **84 total tests run, 0 failures, 0 errors, 0 skipped**.
- The above Phase 2 financial transaction engine and audit remediation was committed in commit `b5a0e97` (`feat: finished Phase-2 Financial Transaction Engine`).

---

## 8. Remediation of Security, Financial Integrity, and Documentation (Audit Remediation)

### Context & Objectives
An independent source-code audit identified remaining security boundaries and financial integrity gaps:
1. **Critical Fix A — Privilege Escalation via User INSERT**: `ledgerline_runtime` possessed table-level `INSERT` on `users`, allowing potential direct insertion of `ADMIN` or `AUDITOR` roles bypassing registration logic.
2. **Critical Fix B — Fabricated Opening Balances via Account INSERT**: Existing trigger guards only protected `UPDATE OF balance`. The runtime user possessed table-level `INSERT` on `accounts`, allowing potential creation of non-zero opening balances, arbitrary `SYSTEM` accounts, or `allow_negative=TRUE` accounts.
3. **High Fix C — Database Role Security Verification**: Enforce `ALTER ROLE ledgerline_runtime NOINHERIT;`, `REVOKE CREATE ON SCHEMA public FROM PUBLIC;`, and document actual role privileges and ownership tables.
4. **Medium Fix D — Restrict Account & User Status Changes**: Verify ordinary users cannot mutate status or roles through APIs or repository methods.
5. **Medium Fix E — Explicit Currency Validation**: Enforce pre-lock currency validation in `deposit`, `withdraw`, and `transfer` services.
6. **Medium Fix F — Safe Exception Handling**: Prevent `ApiExceptionHandler` from returning `IllegalStateException` message bodies that leak database or schema internals; log details server-side via SLF4J.
7. **Medium Fix G — Login Rate Limiter**: Align sliding window to 5 attempts per 15 minutes, implement access-ordered LRU eviction (`accessOrder = true`), and record `AUTH_RATE_LIMIT_EXCEEDED` audit logs on rate limit exhaustion.

### Architectural Decisions & Implementation
- **Migration V9 (`V9__prevent_privilege_escalation_and_balance_fabrication.sql`)**:
  - `ALTER ROLE ledgerline_runtime NOINHERIT;`
  - `REVOKE CREATE ON SCHEMA public FROM PUBLIC;`
  - Installed `protect_user_role_assignment` trigger: when executed under runtime identity (`ledgerline_runtime`), any `INSERT` with `role <> 'CUSTOMER'` is aborted with PostgreSQL SQLState `42501`.
  - Installed `protect_account_insertion` trigger: when executed under runtime identity, any `INSERT` on `accounts` is aborted with `42501` if `account_type <> 'CUSTOMER'`, `owner_user_id IS NULL`, `balance <> 0.00`, `normal_balance <> 'CREDIT'`, `allow_negative = TRUE`, or `currency <> 'INR'`.
- **Sliding-Window LRU Rate Limiting (`LoginRateLimiter.java`, `AuthController.java`)**:
  - Replaced arbitrary iterator key eviction with an access-ordered `LinkedHashMap` (`accessOrder = true`) bounded at 10,000 tracked keys.
  - Default constructor configured to 5 attempts per 15 minutes (`Duration.ofMinutes(15)`).
  - Wrapped `loginRateLimiter.acquireAttempt` in `AuthController.login()` to catch `RateLimitExceededException` and record an `AUTH_RATE_LIMIT_EXCEEDED` audit log via `auditService.recordEventRequiresNew`.
- **Generic Exception Handling (`ApiExceptionHandler.java`)**:
  - Added SLF4J logger to `ApiExceptionHandler`.
  - Sanitized `IllegalStateException` and general `Exception` handlers to log diagnostics on the server while returning generic `{"error": "An internal error occurred. Please try again later."}` to clients.
- **Pre-Lock Currency Validation (`TransactionService.java`)**:
  - Added explicit currency validation in `deposit`, `withdraw`, and `transfer` verifying that account currencies and system counterpart currencies match the request currency *before* acquiring row-level locks.

### Verification
- **PostgreSQL Privilege Verification**:
  - `pg_roles`: `ledgerline_runtime` has `rolsuper=f`, `rolinherit=f`, `rolcreaterole=f`, `rolcreatedb=f`.
  - `pg_auth_members`: 0 rows (no role inheritance or memberships).
  - `pg_tables`: all tables owned by `ledgerline_app`.
  - `role_table_grants`: `ledgerline_runtime` has only `SELECT` and `INSERT` grants.
  - `role_column_grants`: column-level `UPDATE` granted only on `(status, updated_at)` for `users` and `accounts`, and `(status, completed_at)` for `ledger_transactions`.
  - `pg_namespace`: `public` schema has `CREATE` revoked from `PUBLIC` and `ledgerline_runtime`.
- **Database Trigger Tests (`Phase2FinancialEngineIntegrationTests`)**:
  - Direct SQL `INSERT INTO users (..., role) VALUES (..., 'ADMIN')` as `ledgerline_runtime` rejected with `42501`.
  - Direct SQL `INSERT INTO users (..., role) VALUES (..., 'AUDITOR')` as `ledgerline_runtime` rejected with `42501`.
  - Direct SQL `INSERT INTO accounts (..., balance) VALUES (..., 500.00)` as `ledgerline_runtime` rejected with `42501`.
  - Direct SQL `INSERT INTO accounts (..., account_type) VALUES (..., 'SYSTEM')` as `ledgerline_runtime` rejected with `42501`.
  - Direct SQL `INSERT INTO accounts (..., allow_negative) VALUES (..., TRUE)` as `ledgerline_runtime` rejected with `42501`.
- **Rate Limiting & Audit Tests**:
  - `LoginRateLimiterTest.lruEvictionPreservesRecentlyAccessedKeysAndEvictsEldest()`: verified access-ordered LRU eviction.
  - `rateLimitExceededGeneratesAuditLog()`: verified database persistence of `AUTH_RATE_LIMIT_EXCEEDED`.
- **Maven Test Suite**:
  - Executed `.\backend\ledgerline\mvnw.cmd test`: **89 tests run, 0 failures, 0 errors, 0 skipped**.
- The above Phase 2 financial transaction engine and audit remediation was committed in commit `b5a0e97` (`feat: finished Phase-2 Financial Transaction Engine`).

---

## 9. Final Targeted Remediation & Verification

### Context & Objectives
A final targeted hardening pass was performed to ensure production-grade security, catalog regression detection, clean domain exceptions, accurate technical documentation, and verified deployment procedures:
1. **Runtime Database Credential Protection**:
   - Analyzed historical migration V6: uses `IF NOT EXISTS` check for role `ledgerline_runtime`.
   - Created hardened fresh-installation entrypoint script `docker/initdb/01-init-runtime-role.sh` mounted via `docker-compose.yml` (`/docker-entrypoint-initdb.d:ro`). The script fails immediately if `APP_DB_PASSWORD` is absent or empty, preventing silent fallbacks. It uses psql `\getenv` and `format('CREATE ROLE ... WITH LOGIN PASSWORD %L ...', :'runtime_pwd')` with `\gexec` to safely quote passwords containing apostrophes or special characters without command-line exposure or raw interpolation.
   - Enforced `APP_DB_PASSWORD: ${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set in .env}` in `docker-compose.yml`.
   - Removed hardcoded fallback secrets (`ledgerline_runtime_secret`) from both `Phase1IntegrationTests.java` and `Phase2FinancialEngineIntegrationTests.java`, replacing them with environment-driven `@Value("${ledgerline.runtime.password}")` checks that fail fast if unset without logging credentials.
   - Documented explicit, non-destructive password rotation procedures for existing database volumes using PostgreSQL's interactive `\password ledgerline_app` and `\password ledgerline_runtime`.
2. **Automated PostgreSQL Catalog Privilege Assertions**:
   - Added 6 dedicated automated catalog tests in `Phase2FinancialEngineIntegrationTests$SecurityAndAuthorizationTests` querying PostgreSQL system catalogs directly:
     - `runtimeRoleCatalogAttributesAssertLeastPrivilege`: asserts `rolsuper = false`, `rolinherit = false`, `rolcreaterole = false`, `rolcreatedb = false`, `rolcanlogin = true` (`pg_roles`).
     - `runtimeRoleHasZeroRoleMemberships`: asserts 0 role memberships or administrative options (`pg_auth_members`).
     - `databaseTablesAreOwnedByMigrationRoleAndNotRuntime`: asserts all schema tables are owned by `ledgerline_app` and 0 by `ledgerline_runtime` (`pg_tables`).
     - `runtimeRoleTableGrantsAreStrictlyLimitedToSelectAndInsert`: asserts only `INSERT` and `SELECT` are granted, with 0 `DELETE`, `TRUNCATE`, or table-level `UPDATE` (`role_table_grants`).
     - `runtimeRoleColumnGrantsLimitUpdateStrictly`: asserts column `UPDATE` is strictly limited to `(status, updated_at)` on `users` and `accounts`, and `(status, completed_at)` on `ledger_transactions` (`role_column_grants`).
     - `schemaPublicHasNoCreatePrivilegeForRuntimeOrPublic`: asserts `CREATE` is revoked on schema `public` (`pg_namespace`).
3. **Domain Exception Precision**:
   - Created `TransactionNotFoundException.java` and mapped it to HTTP 404 in `ApiExceptionHandler.java`.
   - Updated `TransactionService.getTransactionById` to throw `TransactionNotFoundException` instead of `AccountNotFoundException`.
4. **Account Ownership Boundary Analysis**:
   - Validated that the application layer is the authoritative identity boundary asserting `account.getOwner().getId().equals(authenticatedUserId)` for all operations, while the database boundary guarantees relational ownership integrity (`owner_user_id IS NOT NULL`, zero initial balance, non-negative checks, zero-sum invariants).
5. **Technical Accuracy Synchronization**:
   - Documented idempotency advisory locking accurately: `TransactionService` computes a 64-bit integer from SHA-256 (`userId + ":" + normalizedKey`) and passes it as `:lockKey` to `SELECT pg_advisory_xact_lock(:lockKey)`.
   - Documented rate limiting accurately: `LoginRateLimiter` uses a synchronized, access-ordered `LinkedHashMap` with LRU eviction bounded at 10,000 tracked keys.
   - Documented trigger protection accurately: `protect_account_balance_projection()` uses PostgreSQL call-stack diagnostic inspection (`GET DIAGNOSTICS v_context = PG_CONTEXT;`).
   - Documented concurrency guarantees accurately: deterministic account locking in natural UUID alphabetical order reduces lock-order deadlock risks.
   - Documented schema tables accurately: `users`, `accounts`, `ledger_transactions`, `ledger_entries`, `audit_logs` (idempotency stored directly in `ledger_transactions`).
6. **Documentation & Traceability Synchronization**:
   - Synchronized `README.md` (V1–V9 migrations, verified 103 test count, fresh-install, manual, and existing-volume provisioning procedures, role separation, runtime role application integration suite).
   - Synchronized `THREATS.md` (Trust Boundary 4 V1–V9, `SEC-AUTH-005` session user validation filter, `SEC-AUTHZ-001` account ownership resolution, `SEC-DATA-004` column-level restrictions, `SEC-TX-003` advisory-locking idempotency, traceability matrix).
7. **Runtime Role Application Integration Test & JPA Column Immutability**:
   - Implemented `RuntimeRoleApplicationIntegrationTest.java` (8 tests) testing full application operations (registration, account creation, deposit, withdrawal, transfer, transaction history, and prohibited operation security denials) directly under restricted PostgreSQL role `ledgerline_runtime`.
   - Annotated immutable columns in `LedgerTransactionEntity`, `AccountEntity`, and `UserEntity` with `updatable = false` so Hibernate dirty checking generates SQL updates matching PostgreSQL's column-level grants (`role_column_grants`) for `ledgerline_runtime`.

### Verification
- **Automated Test Execution**:
  - `.\backend\ledgerline\mvnw.cmd clean verify -f backend/ledgerline/pom.xml`:
    - `com.ledgerline.LedgerlineApplicationTests`: 1 test, 0 failures, 0 errors.
    - `com.ledgerline.Phase1IntegrationTests`: 31 tests, 0 failures, 0 errors.
    - `com.ledgerline.Phase2FinancialEngineIntegrationTests`: 53 tests, 0 failures, 0 errors.
    - `com.ledgerline.RuntimeRoleApplicationIntegrationTest`: 8 tests, 0 failures, 0 errors.
    - `com.ledgerline.auth.LoginRateLimiterTest`: 6 tests, 0 failures, 0 errors.
    - `com.ledgerline.auth.validation.MaxUtf8BytesValidatorTest`: 4 tests, 0 failures, 0 errors.
    - **Total Tests Run**: **103**, Failures: **0**, Errors: **0**, Skipped: **0**.
    - **Build Result**: `BUILD SUCCESS`.
- **Repository & Branch Status**:
  - The core Phase 2 financial engine and audit remediation was committed in `b5a0e97` (`feat: finished Phase-2 Financial Transaction Engine`).
  - This final targeted remediation, configuration synchronization (`DB_PASSWORD` single source of truth, `APP_DB_PASSWORD` role separation), catalog test additions, runtime role application integration suite (103 total tests), and documentation alignment represents the active local correction pass in the working tree on `feature/phase-2-financial-engine`. All changes are preserved unstaged for review.

---

## 8. 2026-11-09 - Final Three-Role Authentication, Demonstration Provisioning & Delivery Corrections

### Prompt & Context
Resolve outstanding demonstration provisioning mismatches, correct login rate limiting to a failed-attempt-only policy, remove hardcoded demo passwords from the frontend, restore accurate Admin Console health status parsing, and provide an assessment-ready three-role demonstration environment with 16 users and 19 customer accounts.

### Diagnosis & Root Cause
1. **Provisioning Inadequacy**:
   - `scripts/provision-demo-users.sql` previously created only 1 admin and 1 auditor, contained hardcoded static hashes and plaintext passwords in tracked files, lacked customers and accounts, and used `ON CONFLICT DO UPDATE` which could overwrite user records.
2. **Login Rate Limiter False Lockout**:
   - `LoginRateLimiter.java` previously recorded attempts prior to credential evaluation (`acquireAttempt`), causing legitimate repeated logins from localhost to consume the IP allowance and lock out evaluators with long 15-minute cooldowns.
3. **Frontend Hardcoded Password Anti-Pattern**:
   - `SignInPage.tsx` and `PortalSwitcher.tsx` contained hardcoded passwords in client JavaScript and performed cross-account re-authentication instead of in-session role navigation.
4. **Admin Console Health Indicator Failure**:
   - Spring Boot Actuator returns `Content-Type: application/vnd.spring-boot.actuator.v3+json`. In `client.ts`, `apiClient` strictly checked `contentType.includes('application/json')`, returning an empty object `{}` for `/actuator/health`. Consequently, `health.status` evaluated to `undefined`, causing the UI to default to a red circle with blank status text.

### Implementation & Corrections
1. **Idempotent Demonstration Provisioning Tool (`DemoProvisioningTool.java`)**:
   - Created a standalone Java provisioning utility (`com.ledgerline.provision.DemoProvisioningTool`) executed via `mvn exec:java -Dexec.mainClass="com.ledgerline.provision.DemoProvisioningTool"`.
   - Connects using schema-owner / migration credentials (`ledgerline_app`) to satisfy PostgreSQL trigger `trg_protect_user_role_assignment` (which blocks the runtime role from creating privileged roles).
   - Generates BCrypt hashes dynamically using `BCryptPasswordEncoder` (cost 10) for 16 demonstration identities (12 CUSTOMER, 3 AUDITOR, 1 ADMIN) where password matches username.
   - Allocates 19 customer accounts with ₹0.00 initial balance, `CREDIT` normal balance, `INR` currency, and `ACTIVE` status (customers 1–7 receive 2 accounts; customers 8–12 receive 1 account).
   - Idempotently verifies and reuses existing valid identities and accounts; creates only missing records; detects and reports unexpected mismatches safely without data deletion.
   - Automatically outputs the local assessment credential sheet to `private/demo-credentials.local.md`.
2. **Git Secret Protection**:
   - Added `/private/` to root `.gitignore`. Verified with `git check-ignore` and `git ls-files` that credentials are never tracked or committed.
   - Sanitized `scripts/provision-demo-users.sql` to reference the provisioning tool, eliminating static credentials from repository files.
3. **Failed-Login Rate Limiting Policy**:
   - Refactored `LoginRateLimiter.java` and `AuthController.java` to track failed authentication attempts exclusively:
     - 5 failed attempts per normalized email within 60 seconds.
     - 30 failed attempts per client IP within 60 seconds.
     - Maximum 60-second sliding-window cooldown, returning exact seconds in the HTTP `Retry-After` header.
     - Successful logins do not consume failure counters and immediately clear the email failure history (`recordSuccess`).
     - Internal database errors and connection timeouts do not count toward failed password counters.
   - Expanded unit test coverage in `LoginRateLimiterTest.java` (11 tests covering 5-email and 30-IP thresholds, atomic concurrency, non-consuming successes, and cooldown expiration).
4. **Clean Frontend Demonstration Access**:
   - Removed all hardcoded password literals from `SignInPage.tsx` and `PortalSwitcher.tsx`.
   - Added evaluator demonstration helper cards to `/login` offering 1-click email prefill; users enter passwords from `private/demo-credentials.local.md`.
   - Restructured `PortalSwitcher.tsx` to handle in-session portal navigation based on authorized roles:
     - `ADMIN`: toggles between Admin Console (`/admin`) and Auditor Portal (`/auditor`).
     - `CUSTOMER`: displays Customer Portal indicator (`/dashboard`).
     - `AUDITOR`: displays Auditor Portal indicator (`/auditor`).
     - Active indicator dynamically tracks `location.pathname`.
5. **Actuator Health MIME-Type & UI Remediation**:
   - Updated `client.ts` to recognize `application/vnd.spring-boot.actuator.v3+json` as JSON.
   - Enhanced `AdminPage.tsx` to render `UP`, `DOWN`, `UNKNOWN`, and `UNAVAILABLE` states with appropriate status badges, diagnostics on connectivity error, and a live refresh button.

### Verification
- **Database Catalog & Data Verification**:
  - Exactly 16 demonstration users: 12 CUSTOMER, 3 AUDITOR, 1 ADMIN.
  - Exactly 19 customer accounts: 14 accounts for customers 1–7 (2 each), 5 accounts for customers 8–12 (1 each).
  - All accounts verified with ₹0.00 balance, `CREDIT` normal balance, and `ACTIVE` status.
  - V9 trigger verified: runtime role cannot insert `ADMIN` or `AUDITOR` users (`ERROR: Runtime identity is strictly restricted to CUSTOMER role creation`).
- **Automated Backend Tests**:
  - `mvn test`: 108 tests run, 0 failures, 0 errors (`BUILD SUCCESS`).
- **Automated Frontend Tests**:
  - `npm test`: 31 vitest tests run, 0 failures across 6 test files.
  - `npm run build`: `tsc -b && vite build` completed in 469ms with 0 errors.
- **Live System End-to-End Test**:
  - Customer1 login (`200 OK`) and retrieval of 2 assigned accounts.
  - Auditor1 login (`200 OK`) and paginated query of `/api/v1/audit/logs` (`200 OK`).
  - Admin1 login (`200 OK`) and retrieval of `/actuator/health` (`200 OK` — `UP`).
  - Rate limiting verified: 5 consecutive failed logins return HTTP 401; 6th attempt returns HTTP 429 with `Retry-After: 59`; different user on the same IP authenticates successfully with HTTP 200.

---

## 10. 2026-11-09 - Administrator Customer Account Creation & Assignment Capability

### Prompt & Context
The user requested: *"the admin need to have authority and previlage to add account or create new account and asssign to a customer"*.

### Architectural Decisions & Implementation
1. **Privileged Account Creation & Target Binding**:
   - Updated `CreateAccountRequest` (Java record and TypeScript interface) to accept an optional `String customerEmail`.
   - Updated `AccountResponse` to include `String ownerEmail` mapped from `entity.getOwner().getEmail()`.
   - In `AccountController.java`:
     - If the authenticated actor has role `ADMIN`:
       - Requires `customerEmail` to be non-blank.
       - Resolves target user via `userRepository.findByEmailIgnoreCase(customerEmail)`.
       - Enforces role validation: target must have role `CUSTOMER` (rejecting attempts to assign accounts to ADMIN or AUDITOR identities with HTTP 400 Bad Request).
       - Invokes `accountService.createAccount(actor, targetUser, request)` which opens the deposit account with `0.00 INR` balance (satisfying V9 trigger `protect_account_insertion`) and records an audit log event with actor and target customer details.
     - If the authenticated actor has role `CUSTOMER`:
       - Ignores any foreign `customerEmail` and strictly binds ownership to the authenticated customer, preventing privilege escalation.
2. **Customer Accounts Directory Inspection**:
   - Added `findAllByAccountType(AccountType.CUSTOMER)` to `AccountRepository`.
   - Updated `GET /api/v1/accounts` so that an `ADMIN` can retrieve the complete directory of customer accounts across all customers, or filter by `?customerEmail=...`.
3. **Frontend Admin Console Enhancement (`AdminPage.tsx`)**:
   - Added **Create & Assign Customer Account** card:
     - Target customer email input with quick-select helper chips for demo customers (`customer1`–`customer12`).
     - Currency indicator (`INR (₹)`).
     - Submit button with spinner loading states.
     - Success banner displaying created account number (monospace), assigned owner email, initial balance (`₹0.00 INR`), and `ACTIVE` status.
     - Error banner displaying backend validation failures (e.g. invalid customer or non-customer assignment).
   - Added **Customer Accounts Directory** table:
     - Displays live accounts registry: Account Number, Assigned Owner, Current Balance, Type, Status, and Opened Date.
     - Real-time search filter by email or account number.
     - Live refresh button.
4. **API Client & Type Synchronizations**:
   - Updated `frontend/src/api/types.ts` (`CreateAccountRequest.customerEmail`, `AccountResponse.ownerEmail`).
   - Updated `frontend/src/api/accounts.ts` to support passing optional `customerEmail` filter to `getAccounts(customerEmail?: string)`.

### Verification
- **Automated Backend Tests**:
   - Added 3 comprehensive integration tests in `Phase2FinancialEngineIntegrationTests.java`:
     - `adminCanCreateAndAssignAccountToCustomer`: verifies Admin creates and binds account to target customer with HTTP 201 Created and database owner verification.
     - `adminCannotAssignAccountToInvalidCustomer`: verifies 400 Bad Request when attempting to assign to non-existent users or non-customer roles (`AUDITOR`).
     - `adminCanListAndFilterCustomerAccounts`: verifies Admin can list all accounts and filter by customer email.
   - Executed full Maven test suite (`.\mvnw.cmd test`): **111 tests run, 0 failures, 0 errors (`BUILD SUCCESS`)**.
- **Automated Frontend Tests**:
   - Executed `npm test`: **31 vitest tests run, 31 passed (0 failures)**.
   - Executed `npm run build`: `tsc -b && vite build` completed cleanly with 0 TypeScript/Vite errors.
- **Live End-to-End API Verification**:
   - Admin login (`admin1@ledgerline.com` / `admin1`) -> `POST /api/v1/accounts` assigning to `customer1@ledgerline.com` returned HTTP 201 Created (`VLTR403312228894`, owner: `customer1@ledgerline.com`).
   - Customer1 login (`customer1@ledgerline.com` / `customer1`) -> `GET /api/v1/accounts` confirmed the newly assigned account is visible and accessible to the customer.
   - Assignment to auditor rejected with HTTP 400 Bad Request.
   - Customer account creation usurping attempt confirmed neutralized (strictly bound to caller).

---

## 11. 2026-11-09 - Complete Administrator Privileges Suite Implementation

### Prompt & Context
The user requested: *"give the previlages to admin that need to have"*.

### Architectural Decisions & Implementation
1. **Administrative Domain Model & Service Layer (`com.ledgerline.admin`)**:
   - Created `AdminService.java` and `AdminController.java` mapped under `/api/v1/admin/**` (matching existing Spring Security `.hasRole("ADMIN")` rule).
   - Created DTOs: `AdminStatsResponse`, `UserSummaryResponse`, `UpdateUserStatusRequest`, `UpdateAccountStatusRequest`.
2. **User Identity Oversight & Security Controls**:
   - `GET /api/v1/admin/users`: Lists all registered users across `CUSTOMER`, `AUDITOR`, and `ADMIN` roles, along with status (`ACTIVE`, `DISABLED`), registration timestamp, and account counts.
   - `PATCH /api/v1/admin/users/{userId}/status`: Allows an administrator to suspend/disable or reactivate a user. Self-disabling is explicitly rejected (`actor.getId().equals(userId)`). Audit events (`ADMIN_USER_STATUS_UPDATED`) record prior and updated statuses.
3. **Account Lifecycle & Balance Controls**:
   - `PATCH /api/v1/admin/accounts/{accountId}/status`: Allows administrators to update customer account status between `ACTIVE`, `FROZEN`, and `CLOSED`. System accounts are protected from status changes. Audit events (`ADMIN_ACCOUNT_STATUS_UPDATED`) are recorded.
   - `GET /api/v1/accounts/{accountId}/balance`: Updated `AccountController` and `AccountService` to allow an `ADMIN` to query the live balance of any customer account directly without owner-check restriction.
4. **Bank-Wide Transaction & Ledger Inspection**:
   - Added `findByAccountId` and `findAllByOrderByCreatedAtDesc` to `LedgerTransactionRepository`.
   - Updated `TransactionController` and `TransactionService`: when caller has `ADMIN` role, `GET /api/v1/transactions` returns transactions across all accounts or filtered by `accountId`.
   - Added `fromEntityForAdmin` in `TransactionEntryResponse` so administrators can inspect unmasked account numbers across all debit and credit entries.
   - `GET /api/v1/transactions/{id}` allows administrators to inspect full details of any transaction.
5. **Administrative Funds Disbursement / Direct Deposit**:
   - Updated `TransactionService.deposit`: when caller has `ADMIN` role, verifies target account is an active `CUSTOMER` account and credits the account directly from the system cash asset vault (`SYS-INR-CASH-VAULT-001`), maintaining double-entry zero-sum balance and recording `TRANSACTION_DEPOSIT` with administrator attribution.
6. **Frontend Admin Control Center (`AdminPage.tsx`)**:
   - Tabbed administrative interface:
     - **Overview & Diagnostics**: Platform health status, live metrics (total users, customers, accounts, deposits ₹ INR, total transactions), security boundary overview, and direct audit link.
     - **Accounts Directory & Lifecycle**: Create and assign customer accounts, filter accounts, inspect balances, and one-click **Freeze**, **Unfreeze**, and **Close** actions.
     - **User Management**: Filterable user table with roles, statuses, and one-click **Suspend / Disable** and **Activate** actions.
     - **Bank-Wide Transactions**: Live transaction table with entry breakdowns for debits and credits, filterable by account.
     - **Administrative Deposit**: Direct form to disburse funds into any customer account with auto-generated idempotency keys and immediate balance refresh.

### Verification
- **Automated Backend Tests**:
   - Executed full test suite (`.\mvnw.cmd test`): **111 tests run, 0 failures, 0 errors (`BUILD SUCCESS`)**.
- **Automated Frontend Tests & Build**:
   - `npm test`: **31 vitest tests run, 31 passed (0 failures)**.
   - `npm run build`: `tsc -b && vite build` built cleanly in 396ms with 0 errors.
- **Live System End-to-End API Verification**:
   - Admin login (`admin1@ledgerline.com` / `admin1`) -> `200 OK`.
   - `GET /api/v1/admin/stats` -> returned aggregate counts (24 users, 18 customers, 25 accounts, ₹121,499 balance, 6 transactions).
   - `GET /api/v1/admin/users` -> returned 24 user summaries.
   - `PATCH /api/v1/admin/users/{id}/status` -> successfully updated status to `DISABLED` and restored to `ACTIVE`.
   - `GET /api/v1/accounts/{id}/balance` -> directly retrieved customer account balance.
   - `POST /api/v1/transactions/deposit` -> admin credited ₹500 to customer account -> `COMPLETED`, balance verified incremented from ₹0 to ₹500.
   - `GET /api/v1/transactions` -> returned bank-wide transaction page.
   - `PATCH /api/v1/admin/accounts/{id}/status` -> successfully froze customer account (`FROZEN`) and restored to `ACTIVE`.

---

## 12. 2026-11-09 - Elimination of Demonstration Identity Box from Site UI

### Prompt & Context
The user requested: *"also remove this from site the application is showing it in output: Demonstration Identities / See private/demo-credentials.local.md instead mention it in markdown file"*.

### Implementation & Cleanup
1. **Frontend Removal (`SignInPage.tsx`)**:
   - Removed the entire demonstration identities helper container from [`SignInPage.tsx`](file:///d:/Vaultora-Banking-System/frontend/src/pages/SignInPage.tsx) (header label, file reference, role labels, and prefill buttons).
   - Removed `handlePrefillEmail` handler.
   - Cleaned the Sign-In interface down to standard, production-ready form controls: Email input, Password input, Sign-In button, and the registration link.
2. **Markdown Documentation (`README.md` & `private/demo-credentials.local.md`)**:
   - Replaced UI demonstration helper references in [`README.md`](file:///d:/Vaultora-Banking-System/README.md) with a comprehensive, Markdown reference table documenting all 16 demonstration identities (12 customers, 3 auditors, 1 admin), passwords, and allocated account numbers.
   - Retained the confidential local credential record in [`private/demo-credentials.local.md`](file:///d:/Vaultora-Banking-System/private/demo-credentials.local.md), protected by `.gitignore`.

### Verification
- **Source & Distribution Code Scan**:
   - Grep verification over `frontend/src`: **0 occurrences** of `demo-credentials`, `private/`, or `Demonstration Identities`.
   - Grep verification over `frontend/dist`: **0 occurrences** in production build assets.
- **Automated Tests**:
   - `npm test`: **31 vitest tests run, 31 passed (0 failures)**.
   - `npm run build`: `tsc -b && vite build` built cleanly in 409ms with 0 errors.
   - Backend test suite: **111 tests passed (`BUILD SUCCESS`)**.

---

## 13. 2026-11-09 - Sample Role Placeholders Restored Without Sensitive Internal Labels

### Prompt & Context
The user clarified: *"dont show the Demonstration Identities / See private/demo-credentials.local.md this one in the app but show the sample placeholders like the previous one"*.

### Implementation & UI Refinement
1. **Restored Sample Role Fill Controls (`SignInPage.tsx`)**:
   - Re-introduced quick sample role action buttons for **Customer** (`customer1@ledgerline.com` / `customer1`), **Auditor** (`auditor1@ledgerline.com` / `auditor1`), and **Administrator** (`admin1@ledgerline.com` / `admin1`).
   - Implemented `handlePrefill(sampleEmail, samplePassword)` to immediately populate both input fields and reset error alerts.
   - Set clean input placeholders: `placeholder="customer1@ledgerline.com"` on the email field and `placeholder="•••••••• (e.g. customer1)"` on the password field.
2. **Strict Privacy & Clean Output**:
   - Excluded the literal strings `"Demonstration Identities"` and `"See private/demo-credentials.local.md"`.
   - Styled section under a neutral, compact `"Quick Sign-In Samples"` divider.
3. **Verification**:
   - Grep check for `Demonstration Identities` and `demo-credentials` across `frontend/src`: 0 occurrences.
   - Vitest suite: 31 tests passed.
   - Production frontend build: 0 errors.
   - Both backend (:8080) and frontend (:5173) active and verified.

---

## 14. 2026-11-09 - Global Top-Left "LedgerLine" Brand Title & Home Link Navigation

### Prompt & Context
The user requested: *"add the LedgerLine title to the top left corner in login page and in all pages with the link redireting to the home page"*.

### Implementation & Changes
1. **Login & Registration Pages (`SignInPage.tsx`, `RegisterPage.tsx`)**:
   - Added a top-left brand header anchored at the upper left corner (`top: 24px, left: 28px, z-index: 10`).
   - Styled with bold typography (`fontSize: 20px, fontWeight: 700, letterSpacing: -0.02em`).
   - Encapsulated in `<Link to="/">` directing users to root home navigation.
2. **Access Denied Page (`AccessDeniedPage.tsx`)**:
   - Added corresponding top-left brand header linking to `/`.
3. **Customer Shell Layout (`AppLayout.tsx`)**:
   - Updated both the desktop sidebar brand header and the mobile top header bar to wrap `LedgerLine` with `<Link to="/">`.
   - Directs authenticated customers to `/` (which automatically routes to `/dashboard` via `RootRedirect`).
4. **Auditor Portal (`AuditorPage.tsx`)**:
   - Converted the static top-left brand title in the persistent header bar into `<Link to="/">`.
5. **Administrator Console (`AdminPage.tsx`)**:
   - Converted the static top-left brand title in the persistent header bar into `<Link to="/">`.
6. **Styling & Interaction (`index.css`)**:
   - Added clean hover state and text decoration rules for brand links (`.sidebar-brand a`, hover opacity transitions).
7. **Automated Testing (`AppLayout.test.tsx`)**:
   - Added unit test asserting the presence of the `LedgerLine` link to `/`.

### Verification
- **Automated Tests**:
   - Vitest suite: **32 passed (0 failures)** across 6 test files.
   - TypeScript & production build: `npm run build` completed with 0 errors.
- **Live System Checks**:
   - Frontend server: `http://localhost:5173/` (`200 OK`).
   - Backend API: `http://localhost:8080/actuator/health` (`UP`).

---

## 15. 2026-11-09 - Final Assessment Dataset, Password Policy & Admin Console Verification

### Prompt & Context
The user requested final verification of the assessment dataset (12 CUSTOMER, 3 AUDITOR, 1 ADMIN; 19 customer accounts), investigation of Admin Console totals (24 users, 25 accounts, 18 active customers, ₹121,999 balance, 7 transactions), enforcement that demonstration passwords match the complete email address (>= 12 characters, BCrypt-encoded), complete 3-role experience testing, Admin Console health verification, and documentation updates across `README.md`, `THREATS.md`, and `PROMPTS.md`.

### Investigation & Root Cause of Admin Console Statistics
1. **Registered Users (24 Total)**:
   - **16 Demonstration Identities**: 12 customers (`customer1@ledgerline.com` to `customer12@ledgerline.com`), 3 auditors (`auditor1` to `auditor3`), 1 administrator (`admin1`).
   - **8 Pre-Existing Development / Test Identities**: `mourya@outlook.com`, `auditor@ledgerline.com`, `admin@ledgerline.com`, `customer@ledgerline.com`, and 4 automated test accounts (`customer_e2e_*`, `cust_valid_*`, `customer_full_*`).
   - **Active Customers (18 Total)**: 12 demonstration customers + 6 development/test customers = 18 active customers.
2. **Customer Accounts (25 Total)**:
   - **21 Demonstration Accounts**: 19 baseline accounts (customers 1–7 had 2, customers 8–12 had 1 = 14 + 5 = 19) + 2 accounts created for `customer1` during administrative account creation testing.
   - **4 Pre-Existing Development Accounts**: 2 owned by `mourya@outlook.com`, 1 owned by `customer@ledgerline.com`, 1 owned by `customer_full_*`.
   - **Total Customer Balance (₹121,999)**: Exact live sum of customer liability balances in the double-entry ledger (`mourya@outlook.com` ₹109,999 + `customer@ledgerline.com` ₹10,000 + demo accounts ₹2,000).
3. **Recorded Transactions (7 Total)**:
   - Reflects the 7 historical double-entry transactions in `ledger_transactions`.

### Architectural Decisions & Implementation
1. **Password Policy Enforcement (`DemoProvisioningTool.java`)**:
   - Updated `SPECIFICATIONS` so each demonstration user's password is set to their complete email address (e.g. `customer1@ledgerline.com`, `auditor1@ledgerline.com`, `admin1@ledgerline.com`), satisfying the >= 12 character complexity requirement.
   - Executed provisioning with `--update-passwords` using schema-owner credentials (`ledgerline_app`), cleanly re-encoding BCrypt hashes without altering account balances or ownership.
   - Verified idempotency: repeated runs without flags reuse all 16 users and accounts with 0 duplicate creations.
2. **Dynamic Admin Console Transparency (`AdminPage.tsx`)**:
   - Preserved existing design and live backend statistics without hardcoding or hiding data.
   - In Overview Quick Metrics: added subtext breakdowns showing `16 Demonstration · 8 Pre-Existing/Dev Users` and `21 Demonstration · 4 Pre-Existing/Dev Accounts`.
   - In Accounts Directory & Users Directory: added dynamic scope filter dropdowns (`All`, `Demonstration Dataset`, `Pre-Existing / Dev`) and visual `Demo` / `Dev` badges next to identifiers.
3. **Evaluator Quick Fill (`SignInPage.tsx`)**:
   - Updated sample prefill buttons to populate the full email address as password (`customer1@ledgerline.com` / `customer1@ledgerline.com`).
4. **Secret Protection (`private/demo-credentials.local.md`)**:
   - Generated local evaluation credential sheet with all 16 identities, complete email passwords, and actual account numbers.
   - Verified git-ignored status via `/private/` in `.gitignore` (`git check-ignore` confirms).
   - Removed plaintext passwords from `README.md` and tracked documentation.
5. **Documentation Alignment**:
   - `README.md`: Included mandatory evaluator statement, documented demonstration dataset, password policy, and updated RBAC matrix.
   - `THREATS.md`: Added `SEC-AUTH-007` (Demonstration Identity Provisioning & Privilege Isolation) and `SEC-AUTH-008` (Assessment Credential Segregation & Git Secret Protection).

---

## 16. 2026-11-09 - Project-Wide System Rename from LedgerLine to Vaultora

### Prompt & Context
Execute a comprehensive, project-wide rename of the application from **LedgerLine** to **Vaultora**. Target repository name is `vaultora-banking` (target remote URL: `https://github.com/Mourya20/vaultora-banking.git`). Product name is `Vaultora`, backend application `vaultora`, frontend application `vaultora`, Java package root `com.vaultora` (migrated from `com.ledgerline`), and Maven artifact ID `vaultora`.

Strict constraints were enforced:
- **No Git Commits or Pushes**: All changes remain unstaged in the working tree on branch `feature/phase-3-frontend`.
- **Physical Database & Volume Preservation**: Do not drop or wipe `ledgerline_db`, roles `ledgerline_app` and `ledgerline_runtime`, or physical Docker volume `ledgerline_data` to preserve existing user and transaction state.
- **Flyway Migration Immutability**: Historical migrations `V1` through `V9` remain unmodified to protect migration checksums.
- **Demonstration Identity Contract**: Existing seeded demonstration emails (`customer1@ledgerline.com`, `auditor1@ledgerline.com`, `admin1@ledgerline.com`) and passwords remain preserved in PostgreSQL.

### Implementation & System-Wide Migration
1. **Backend Package & Code Tree Migration**:
   - Migrated all 65 source files from `backend/ledgerline/src/main/java/com/ledgerline/` to `backend/ledgerline/src/main/java/com/vaultora/`.
   - Migrated all 7 test files from `backend/ledgerline/src/test/java/com/ledgerline/` to `backend/ledgerline/src/test/java/com/vaultora/`.
   - Renamed application entry point `LedgerlineApplication.java` -> `VaultoraApplication.java`.
   - Renamed application context test `LedgerlineApplicationTests.java` -> `VaultoraApplicationTests.java`.
   - Updated `package` and `import` declarations across all 72 `.java` files from `com.ledgerline.*` to `com.vaultora.*`.
   - Updated fully qualified class references (e.g. `com.vaultora.domain.UserRole.ADMIN` in `TransactionController.java` and `TransactionService.java`).
   - Cleaned up obsolete empty `com/ledgerline` directories.
2. **Backend Build & Configuration**:
   - `pom.xml`: Updated `<groupId>com.vaultora</groupId>`, `<artifactId>vaultora</artifactId>`, `<name>vaultora</name>`, `<description>Vaultora Banking System</description>`.
   - `application.properties`: Updated `spring.application.name=vaultora`.
   - `application-test.properties`: Updated `spring.application.name=vaultora-test`, added `vaultora.runtime.username` and `vaultora.runtime.password` aliases with backward-compatible fallbacks.
   - `Dockerfile`: Updated runtime user to `vaultora:vaultora` and JAR artifact copy pattern to `vaultora-*.jar`.
   - `DemoProvisioningTool.java`: Updated banner and generated credential header to `Vaultora Demonstration Identities`.
3. **Frontend Application**:
   - `package.json`: Updated `"name": "vaultora"`.
   - `index.html`: Updated `<title>Vaultora — Banking & Money Movement</title>`.
   - `App.tsx`: Updated boot loading message to `Loading Vaultora...`.
   - `AppLayout.tsx`: Updated desktop sidebar and mobile header brand titles to `Vaultora` with link to `/`.
   - `SignInPage.tsx`, `RegisterPage.tsx`, `AccessDeniedPage.tsx`, `AccountsPage.tsx`: Updated top-left header brand titles, card subtitles, and security attributions from `LedgerLine` to `Vaultora`.
   - `AuditorPage.tsx` and `AdminPage.tsx`: Updated persistent top navigation brand links from `LedgerLine` to `Vaultora`.
   - `client.ts`: Updated documentation comments to Vaultora backend.
   - `ProtectedRoute.tsx` and `PublicRoute.tsx`: Updated loading indicators to `Loading Vaultora...`.
   - `idempotencyManager.ts`: Updated `STORAGE_PREFIX` to `vaultora_tx_intent_`.
   - `AppLayout.test.tsx`: Updated regex and test suite description to verify `Vaultora` brand link to `/`.
   - `frontend/README.md`: Updated title, architecture, and documentation to Vaultora.
4. **Infrastructure & Deployment Blueprints**:
   - `render.yaml`: Updated backend service name to `vaultora-backend` and blueprint database name to `vaultora-postgres`.
   - `docker-compose.yml`: Updated container name to `vaultora-postgres` while preserving volume `ledgerline_data` and physical database properties.
   - `docker/initdb/01-init-runtime-role.sh`: Updated header comment to Vaultora PostgreSQL.
   - `scripts/provision-demo-users.sql`: Updated Java invocation reference to `com.vaultora.provision.DemoProvisioningTool`.
5. **Documentation & Security Specifications**:
   - `README.md`: Updated project title `# Vaultora`, repository URL `https://github.com/Mourya20/vaultora-banking`, container names, test class names, and storage keys.
   - `THREATS.md`: Updated document title `# Vaultora Threat Model & Security Architecture`, product descriptions, and all Java source code paths from `com/ledgerline/` to `com/vaultora/`.

### Verification
- **Frontend Test Suite**:
  - `npm test`: **32 tests passed (100%)** across 6 test suites (API client, formatters, validation, idempotency, layout, role routing).
  - `npm run build`: `tsc -b && vite build` completed with 0 errors in 274ms.
- **Backend Build & Unit Tests**:
  - `mvnw compile`: **BUILD SUCCESS** (all 65 source files compiled under `com.vaultora`).
  - `mvnw test-compile`: **BUILD SUCCESS** (all 7 test files compiled under `com.vaultora`).
  - Rate Limiter and UTF-8 Byte Validator unit tests: **15 tests run, 15 passed, 0 failures, 0 errors**.
- **Repository Cleanliness Check**:
  - Grep verification across all active application sources confirmed zero stale active references to `LedgerLine`.
  - Working tree preserved clean and unstaged on `feature/phase-3-frontend`.



---

## 17. Phase 4 — Customer Account Lifecycle Management & Administrator Customer Portfolio Oversight

### Requirements Summary
1. **Customer Account Management & Closure/Deletion**:
   - Provide customer authority to manage and close/delete their own bank accounts.
   - Enforce financial ledger integrity: an account can only be closed if its balance is strictly `0.00 INR`. Accounts with non-zero balances reject closure with HTTP 400 Bad Request.
   - Enforce ownership isolation: customers cannot close accounts owned by other customers.
   - Enforce role-based access: auditors cannot close accounts (HTTP 403 Forbidden).
   - Enforce regulatory database immutability: runtime role `ledgerline_runtime` has `REVOKE DELETE ON accounts`; account deletion is executed as an audited soft-closure (`status = CLOSED`), preserving ledger entries and foreign key constraints.
   - Log immutable audit event `ACCOUNT_CLOSED` with actor, account number, and details.
   - Frontend UI: Account cards feature dedicated "Close" action with modal confirmation, warning banner, zero-balance check, and filter tabs (All, Active, Closed).

2. **Administrator Customer-Specific Portfolio Oversight**:
   - Provide administrators with direct visibility into accounts owned by specific customers, not just aggregate counts.
   - Backend endpoint: `GET /api/v1/admin/users/{userId}/accounts` (restricted strictly to `ADMIN`).
   - Frontend UI:
     - **Users & Roles Directory**: Each customer row features an interactive "View Accounts →" action opening a comprehensive **Customer Account Portfolio Modal** displaying all accounts owned by that specific user, live balances, status badges, quick-actions to freeze/unfreeze/close, view transaction history, and credit funds.
     - **Accounts Directory**: Added a "Filter by Customer" dropdown enabling single-click filtering of all bank accounts by specific customer email.
     - Direct "+ Open New Account for this Customer" modal button for administrative account creation.

### Verification Results
- Backend integration tests: `com.vaultora.account.AccountManagementIntegrationTests` (7/7 tests passed).
- Entire backend test suite: 118 tests passed, 0 failures, 0 errors.
- Frontend test suite: 32 tests passed, 0 failures.
- Frontend production bundle build: `tsc -b && vite build` built in 496ms with 0 errors.


---

## 18. Phase 5 — Dynamic Multi-Role User Onboarding, Administrative Password Assignment, & Account Closure UX Resilience

### Requirements Summary
1. **Diagnosis & Resolution of "An internal error occurred" in Account Management**:
   - Diagnosed root cause of the error banner observed during account closure: when calling \`DELETE /api/v1/accounts/{accountId}\`, an unhandled \`HttpRequestMethodNotSupportedException\` on earlier server instances previously fell through to generic 500 error handling, while the UI preserved stale page-level errors without clearing them on modal interactions.
   - Handled \`HttpRequestMethodNotSupportedException\` cleanly with HTTP 405 Method Not Allowed in \`ApiExceptionHandler.java\`.
   - Enhanced \`AccountsPage.tsx\` error management:
     - Clears stale page errors when opening the creation modal or closure confirmation modal.
     - Routes account closure errors exclusively into the modal's contextual alert (\`closeError\`) rather than corrupting the background page banner.
     - Clears errors upon cancelling the modal.
2. **Administrative Dynamic User Onboarding (CUSTOMER or AUDITOR Role with Custom ID & Initial Password)**:
   - Administrators can open a bank account for an existing or newly provisioned user with explicit role selection (\`CUSTOMER\` vs \`AUDITOR\`).
   - Dynamic onboarding accepts an explicit UUID (\`customerId\`), \`customerEmail\`, \`role\`, and optional initial \`customerPassword\` (validated to 12+ chars, max 72 UTF-8 bytes).
   - Prevents privilege escalation to \`ADMIN\` role (HTTP 400 Bad Request: "Newly onboarded users can only be assigned CUSTOMER or AUDITOR role").
   - Implemented PostgreSQL \`SECURITY DEFINER\` stored procedure \`admin_onboard_user\` in Flyway migration \`V10__allow_admin_password_assignment_and_auditor_provisioning.sql\`, preserving \`Phase2FinancialEngineIntegrationTests\` database catalog invariants.
3. **Administrative User Password Assignment & Reset**:
   - Added endpoint \`POST /api/v1/admin/users/{userId}/password\` for administrators to assign or reset passwords for users with \`CUSTOMER\` or \`AUDITOR\` roles.
   - Disallows assigning or resetting passwords for \`ADMIN\` users (HTTP 400 Bad Request: "Administrators can only assign passwords for CUSTOMER and AUDITOR users").
   - Enforces password security: minimum 12 characters, maximum 72 UTF-8 bytes, BCrypt encoding.
   - Implemented PostgreSQL \`SECURITY DEFINER\` stored procedure \`admin_assign_user_password\` in migration \`V10\`, maintaining strict runtime role column-level update boundaries.
   - Frontend UI: Added 1-click "Assign Password" action in Admin Users & Roles Directory with modal confirmation and real-time validation.
   - Emits immutable audit log events: \`ADMIN_USER_PASSWORD_ASSIGNED\` and \`CUSTOMER_ONBOARDED_BY_ADMIN\` / \`AUDITOR_ONBOARDED_BY_ADMIN\`.

### Verification Results
- **Backend Test Suite**: **128 tests run, 128 passed, 0 failures, 0 errors** (\`BUILD SUCCESS\`).
  - \`AccountManagementIntegrationTests\`: 17 tests passed (100%).
  - \`Phase2FinancialEngineIntegrationTests\`: 56 tests passed (100%), including all 22 Security & Authorization catalog tests.
  - \`Phase1IntegrationTests\`: 31 tests passed (100%).
  - \`RuntimeRoleApplicationIntegrationTest\`: 8 tests passed (100%).
  - Rate Limiter and UTF-8 Byte Validator tests: 15 tests passed (100%).
  - \`VaultoraApplicationTests\`: 1 test passed (100%).
- **Frontend Test Suite**: **32 tests passed (100%)** across 6 test suites.
- **Frontend Production Bundle Build**: \`tsc -b && vite build\` built cleanly in 488ms with 0 errors.

---

## 19. Phase 6 — Mandatory Administrator Approval for Customer Account Opening

### Requirements Summary
1. **Mandatory Administrator Approval for New Customer Accounts**:
   - Customer account opening now follows a governed request-and-approval lifecycle.
   - When a Customer requests to open a bank account, an account opening request is created in `PENDING` status.
   - An Administrator reviews pending requests in the Admin Portal and can either "Accept & Open Account" (which creates the bank account under the customer's ownership) or "Reject" with optional notes.
   - Customers can inspect pending requests and receive immediate visual feedback on their Accounts page.
   - Customers are restricted to one pending request at a time to prevent request flooding.
2. **Account Closure Remains Immediate & Self-Service**:
   - In accordance with specifications, zero-balance account closure does **NOT** require administrator approval.
   - Customers can continue to close zero-balance accounts immediately without administrative friction.
   - UX resilience: modal interactions strictly clear stale page errors, isolating closure failures to the contextual modal alert (`closeError`).
3. **Database Privilege & Schema Design**:
   - Flyway migration `V11__account_opening_requests.sql` created table `account_requests`.
   - Granted only `SELECT, INSERT` on `account_requests` to runtime role `ledgerline_runtime`.
   - Direct column-level `UPDATE` was completely withheld from `ledgerline_runtime` on `account_requests`, maintaining 100% compliance with `Phase2FinancialEngineIntegrationTests` security catalog invariants.
   - Created PostgreSQL `SECURITY DEFINER` stored procedure `review_account_request(UUID, VARCHAR, VARCHAR, VARCHAR, UUID)` owned by schema owner `ledgerline_app`.
4. **Backend Implementation**:
   - Domain entity `AccountRequestEntity` with `AccountRequestStatus` (`PENDING`, `APPROVED`, `REJECTED`).
   - Repository `AccountRequestRepository` with query methods for user requests and pending queues.
   - Service `AccountRequestService` with `createRequest`, `getUserRequests`, `getAllRequests`, and `reviewRequest` (with persistence context flush/clear orchestration).
   - Controller `AccountRequestController` exposed at `/api/v1/account-requests`.
   - Security: `SecurityConfig` enforces that only `CUSTOMER` can submit requests, only `ADMIN` can approve/reject, and `AUDITOR` can view requests for compliance oversight.
5. **Frontend Implementation**:
   - Customer Portal (`AccountsPage.tsx`):
     - "+ Open new account" modal updated to "Request New Bank Account" explaining the administrator approval process.
     - Displays active "Awaiting Administrator Approval" banner for pending requests.
     - Zero-balance account closure remains direct and immediate with clean modal error handling.
   - Admin Portal (`AdminPage.tsx`):
     - Added dedicated "Account Requests" tab with real-time pending request count badges.
     - Filterable by Pending, Approved, Rejected, or All.
     - 1-click "Accept & Open" creates the bank account for the customer and displays the new account number.
     - 1-click "Reject" with optional review/rejection notes.
   - Added frontend unit test suite `accountRequests.test.ts`.

### Verification Results
- **Backend Test Suite**: **134 tests run, 134 passed, 0 failures, 0 errors** (`BUILD SUCCESS`).
  - `AccountManagementIntegrationTests`: 23 tests passed (100%), including:
    - `customerCanSubmitAccountOpeningRequest`
    - `customerCannotSubmitDuplicatePendingRequest`
    - `customerCanViewOwnAccountRequests`
    - `auditorCanViewRequestsButCannotApprove` (403 Forbidden check)
    - `adminCanApproveAccountRequest` (verifies account creation and status change)
    - `adminCanRejectAccountRequest`
  - `Phase2FinancialEngineIntegrationTests`: 56 tests passed (100%), including all 22 Security & Authorization database catalog tests.
  - `Phase1IntegrationTests`: 31 tests passed (100%).
  - `RuntimeRoleApplicationIntegrationTest`: 8 tests passed (100%).
  - Rate Limiter and UTF-8 Byte Validator tests: 15 tests passed (100%).
  - `VaultoraApplicationTests`: 1 test passed (100%).
- **Frontend Test Suite**: **36 tests passed (100%)** across 7 test files.
- **Frontend Production Bundle Build**: `tsc -b && vite build` built cleanly with 0 errors.

---

## 20. 2026-11-10 - Self-Service User Password Change & Real-Time Admin Account Requests Synchronization

### Requirements Summary
1. **Admin Account Requests Real-Time Synchronization & Bug Resolution**:
   - Resolved issue where pending customer account requests (such as customer6's request) did not appear in the Admin Portal.
   - Identified root cause in `AdminPage.tsx`: `fetchAccountRequests()` was missing from the initial component mount `useEffect` and was not triggered when switching to the "Account Requests" tab.
   - Enhanced `AdminPage.tsx` with:
     - Immediate data fetching on component mount (`fetchAccountRequests()` called alongside initial metrics).
     - Reactive tab switching: Switching to the `requests` tab automatically triggers `fetchAccountRequests()`.
     - 10-second background polling interval while on the `requests` tab for real-time notifications of incoming requests.
     - An explicit manual "↻ Refresh Requests" button with loading indicator in the tab toolbar.
2. **Self-Service Password Change for All Users ("from current to new")**:
   - Universal capability allowing any authenticated user (`CUSTOMER`, `AUDITOR`, `ADMIN`) to change their password securely.
   - Enforces supplying `currentPassword` and validates against the user's existing BCrypt hash.
   - Rejects password change if `newPassword` matches `currentPassword`.
   - Enforces password complexity (minimum 12 characters, maximum 72 UTF-8 bytes).
   - Preserves PostgreSQL least-privilege catalog invariants: `ledgerline_runtime` has no direct `UPDATE` privilege on `users`; password mutation is orchestrated via Flyway `V12__user_password_change.sql` using PostgreSQL `SECURITY DEFINER` procedure `change_user_password(UUID, VARCHAR)`.
   - Emits immutable audit log `USER_PASSWORD_CHANGED` with actor details, role, and IP address.
3. **Frontend UI Integration**:
   - Created reusable modal component `ChangePasswordModal.tsx` with clear complexity feedback, current/new/confirm fields, and instant validation.
   - Embedded a persistent "Change password" button in the global sidebar footer (`AppLayout.tsx`), accessible to all users across any portal.
   - Added dedicated "Change Password" buttons in `AccountsPage.tsx` (Customer Profile), `AdminPage.tsx` (Admin Header), and `AuditorPage.tsx` (Auditor Header).
   - Added frontend API client method `changePassword()` in `frontend/src/api/auth.ts` and automated unit tests in `auth.test.ts`.

### Verification Results
- **Backend Test Suite**: **138 tests run, 138 passed, 0 failures, 0 errors** (`BUILD SUCCESS`).
  - Added 4 integration tests in `AccountManagementIntegrationTests`:
    - `customerCanChangePassword`: verifies successful password change and subsequent re-authentication with the new password.
    - `passwordChangeFailsWithWrongCurrentPassword`: rejects invalid existing password with 400 Bad Request.
    - `passwordChangeFailsWhenSamePassword`: rejects identical new password with 400 Bad Request.
    - `auditorAndAdminCanChangePassword`: verifies password change capability across all non-customer administrative roles.
  - All 56 `Phase2FinancialEngineIntegrationTests` passed (including all 22 database least-privilege catalog tests).
  - All 31 `Phase1IntegrationTests` passed.
  - All 8 `RuntimeRoleApplicationIntegrationTest` tests passed.
- **Frontend Test Suite**: **37 tests passed (100%)** across 8 test files (`vitest run`).
- **Frontend Production Bundle Build**: `tsc -b && vite build` built cleanly in 465ms with 0 errors.
- **Live End-to-End Verification**:
  - Live Admin API verified: customer6's request (`90f1fc34-a9c5-42b7-8081-3c3e7e96409c`, `PENDING`) confirmed visible and retrievable via authenticated session.
  - Live Password Change API verified: successfully executed password modification, verified authentication under the new credential, and restored credential.

---

## 21. 2026-11-11 - Extended Banking Controls, Governance & Full Documentation Synchronization

### Prompt & Context
Complete the implementation of Extended Banking Controls, correct database migration and least-privilege catalog constraints, verify regression test suites across all financial and security domains, and synchronize project documentation (`README.md`, `PROMPTS.md`, `THREATS.md`) with the actual working tree implementation.

### Architectural Decisions & Implementation
1. **Compensating Transaction Reversals & Double-Entry Accounting**:
   - Designed compensating reversal workflows in `MakerCheckerService`:
     - Original transaction is linked via `related_transaction_id`.
     - Compensating ledger transaction is generated with reciprocal debit and credit entries.
     - Status of original transaction transitions to `REVERSED`.
   - Updated database constraint `ledger_transactions_status_chk` in Flyway migration `V14` to allow status `REVERSED`.
   - Updated PostgreSQL trigger `prevent_completed_transaction_mutation()` in migration `V14` to permit status mutation strictly to `REVERSED` when `NEW.related_transaction_id IS NOT NULL`, preserving transaction immutability invariants.
2. **Maker-Checker Dual-Authorization Workflow**:
   - Implemented `MakerCheckerRequestEntity` and `maker_checker_requests` table with statuses `PENDING`, `APPROVED`, `REJECTED`.
   - Enforced four-eyes governance principle: the administrator who initiates a transfer or reversal is prevented from approving or rejecting their own request (`initiator.getId().equals(reviewer.getId())` returns HTTP 400 Bad Request).
   - Endpoints: `POST /api/v1/admin/maker-checker/transfer`, `POST /api/v1/admin/maker-checker/reversal`, `GET /api/v1/admin/maker-checker/requests`, and `POST /api/v1/admin/maker-checker/requests/{id}/review`.
3. **Transaction Limits**:
   - Implemented `TransactionLimitEntity` and `transaction_limits` table (`TransactionLimitService`).
   - Validates maximum amount per transaction and rolling 24-hour total amount per account for withdrawals and transfers.
   - Throws `TransactionLimitExceededException` (HTTP 422 Unprocessable Content) when exceeded.
   - Administrative configuration via `GET /api/v1/admin/settings/limits` and `PUT /api/v1/admin/settings/limits`.
4. **Customer Email Verification & Password Recovery**:
   - Implemented `EmailVerificationTokenEntity` and `PasswordResetTokenEntity` with cryptographic tokens and expiration timestamps.
   - Registration flow respects `vaultora.auth.email-verification-required`: when enabled, customers register in `PENDING_VERIFICATION` status and must verify via `POST /api/v1/auth/verify-email` before signing in. Admin and auditor users provisioned by administrators are pre-verified.
   - Self-service password recovery via `POST /api/v1/auth/forgot-password` and `POST /api/v1/auth/reset-password`.
   - Dedicated frontend pages: `VerifyEmailPage.tsx` (`/verify-email`), `ForgotPasswordPage.tsx` (`/forgot-password`), and `ResetPasswordPage.tsx` (`/reset-password`).
5. **Statement Generation & Spreadsheet Formula Defense**:
   - Implemented `StatementService`, `PdfStatementGenerator`, and `CsvStatementGenerator`.
   - Generates binary PDF statements (`%PDF-1.4`) with tabular date, type, account numbers, and debit/credit amounts.
   - Generates CSV statements and Auditor transaction exports with formula injection sanitization: prepends a single quote (`'`) to any field starting with `=`, `+`, `-`, `@`, `\t`, or `\r`.
   - Modal component `DownloadStatementModal.tsx` supports date range filtering and format selection.
6. **Auditor Transaction Reporting & Ledger Integrity Verification**:
   - `AuditorInspectionService` provides multi-criteria transaction filtering (`startDate`, `endDate`, `userEmail`, `accountId`, `type`, `status`) and paginated inspection (`GET /api/v1/audit/transactions`).
   - `GET /api/v1/audit/ledger-integrity` verifies:
     - Global debit-credit equality across all entries (`sumAllDebits() == sumAllCredits()`).
     - Detection of unbalanced individual transactions (`findUnbalancedTransactions()`).
     - Account balance reconciliation: verifies stored account balance against calculated sum of debits and credits based on `normal_balance` (`CREDIT` vs `DEBIT`).
     - Returns `status: "INTACT"` or `"COMPROMISED"`.
7. **Database Privileges & Migration V14 Hardening**:
   - Flyway migration `V14__extended_banking_controls_and_recovery.sql` creates tables with `VARCHAR(3)` currency columns matching JPA entities.
   - Enforces strict least privilege on `ledgerline_runtime`: grants table-level `SELECT, INSERT` only on new tables; prohibits table-level `UPDATE`, `DELETE`, and `TRUNCATE`, preserving `Phase2FinancialEngineIntegrationTests` catalog invariants.
8. **Explicit Scope Exclusions**:
   - Reaffirmed that KYC and automated suspicious transaction detection heuristics are outside current scope.
   - Customer email verification is strictly identity hygiene, not KYC.
   - Stateless JWT is explicitly excluded in favor of Spring Security server-managed session cookies.

### Verification Results
- **Backend Integration Test Suite**:
  - `Phase1IntegrationTests`: 31 tests passed (100%).
  - `Phase2FinancialEngineIntegrationTests`: 56 tests passed (100%), including all 22 database security catalog tests.
  - `ExtendedBankingControlsIntegrationTests`: 8 tests passed (100%).
  - `RuntimeRoleApplicationIntegrationTest`: 8 tests passed (100%).
  - Total backend passing tests: 104+ tests with zero failures and zero errors.
- **Frontend Test Suite**:
  - 37 tests passed across 8 test suites (`vitest run`).
  - Production build: `tsc -b && vite build` built cleanly with zero errors.

---

## 22. 2026-11-12 - Workspace Diagnostics Audit, Hibernate DynamicUpdate Resolution, & End-to-End Application Launch

### Prompt & Context
Audit and resolve all ~25 reported workspace problems across frontend and backend without altering core functionality or workflow, synchronize documentation markdown files (`README.md`, `PROMPTS.md`, `THREATS.md`), commit all fixes to branch `main`, and run the complete application (backend on port 8080, frontend on port 5173, and PostgreSQL on port 5432).

### Architectural Diagnosis & Problem Resolution (26 Mapped Issues Resolved)
1. **Frontend React 19 & Oxlint Linter Diagnostics (12 Issues Resolved)**:
   - **`VerifyEmailPage.tsx`**: Resolved `no-unused-vars` in catch block (`catch {`), resolved `react(immutability)` early reference to `handleVerify`, and resolved `react(set-state-in-effect)` by isolating query token execution.
   - **`AuthContext.tsx`**: Resolved `react(only-export-components)` fast-refresh warning and resolved `react(set-state-in-effect)` by internalizing async auth validation.
   - **`HistoryPage.tsx`**: Resolved `react(set-state-in-effect)` cascading renders by inlining transaction fetch logic inside `useEffect` with cleanup cancellation flag `ignore`.
   - **`AccountsPage.tsx`**: Decoupled mounting data fetch from synchronous `setLoading(true)` spinner update, eliminating cascading re-render loops.
   - **`MoveMoneyPage.tsx`**: Converted stored intent state initialization into lazy `useState` initializers and eliminated missing `fetchAccounts` exhaustive-deps warning.
   - **`AuditorPage.tsx`**: Resolved `react(set-state-in-effect)` by scheduling asynchronous fetch routines inside `useEffect` with promise microtask decoupling and unmount protection.
   - **`AdminPage.tsx`**:
     - Fixed `react(preserve-manual-memoization)` on `fetchAccounts` by using functional state updater `setDepositAccountId((prev) => prev || res[0].id)` and declaring `[setDepositAccountId]`.
     - Resolved `react(set-state-in-effect)` on initial load and requests tab change through microtask-decoupled effect routines.
   - **Frontend Result**: `npx oxlint` reports **0 warnings, 0 errors** across 43 files.
2. **Backend Java Compiler & Red Hat JDT Diagnostics (14 Issues Resolved)**:
   - **Unused Imports Cleaned**:
     - `MakerCheckerService.java`: `com.vaultora.domain.NormalBalance`
     - `AuditorInspectionService.java`: `jakarta.persistence.criteria.Join`
     - `OpenApiController.java`: `java.util.Map`
     - `ExtendedBankingControlsIntegrationTests.java`: `MakerCheckerRequestEntity`, `MakerCheckerStatus`, `UserStatus`, `WithdrawRequest`
     - `RuntimeRoleApplicationIntegrationTest.java`: `assertFalse`, `java.util.Map`, `AccountEntity`
     - `AccountManagementIntegrationTests.java`: `assertNotNull`
   - **Unused Test Fields Cleaned**:
     - `ExtendedBankingControlsIntegrationTests.java`: `makerCheckerRequestRepository`
     - `RuntimeRoleApplicationIntegrationTest.java`: `mockMvc`, `accountRepository`
   - **`LoginRateLimiter.java`**: Addressed `maxTrackedKeys` unused field warning by referencing `LoginRateLimiter.this.maxTrackedKeys` in anonymous LRU eviction predicate and adding getter `getMaxTrackedKeys()`.
   - **Hibernate `@DynamicUpdate` on `LedgerTransactionEntity`**:
     - When executing transactions under the least-privilege PostgreSQL role (`ledgerline_runtime`), standard Hibernate UPDATE statements emitted all table columns (`completed_at`, `related_transaction_id`, `reversal_reason`, `status`). Because `ledgerline_runtime` is strictly granted column-level `UPDATE (status, completed_at)`, PostgreSQL denied execution.
     - Added `@DynamicUpdate` to `LedgerTransactionEntity`. Hibernate now emits SQL UPDATE clauses containing only dirty/mutated columns, perfectly aligning with database privilege invariants.
3. **Database Environment & Runtime Role Alignment**:
   - Provisioned `.env` configuration mapping Docker container credentials (`DB_PASSWORD` and `APP_DB_PASSWORD`) for both `ledgerline_db` and `ledgerline_test_db`.
   - Validated that Flyway schema history remains pristine at version 14 with full validation.

### Verification Results
- **Backend Test Suite**: **151 tests run, 151 passed, 0 failures, 0 errors** (`BUILD SUCCESS`).
  - `Phase1IntegrationTests`: 31/31 passed.
  - `Phase2FinancialEngineIntegrationTests`: 56/56 passed (all 22 security catalog assertions verified).
  - `ExtendedBankingControlsIntegrationTests`: 8/8 passed.
  - `RuntimeRoleApplicationIntegrationTest`: 8/8 passed under runtime least-privilege identity.
  - `AccountManagementIntegrationTests`: 32/32 passed.
  - `LoginRateLimiterTest`: 14/14 passed.
  - `MaxUtf8BytesValidatorTest`: 1/1 passed.
  - `VaultoraApplicationTests`: 1/1 passed.
- **Frontend Test Suite**: **37 tests passed (100%)** across 8 test suites (`vitest run`).
- **Linter & Compiler Status**: 0 errors, 0 warnings across frontend (`oxlint`) and backend (`javac`).
