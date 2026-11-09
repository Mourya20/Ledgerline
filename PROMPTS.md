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
