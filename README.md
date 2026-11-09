# Ledgerline

**Repository:** [https://github.com/Mourya20/Ledgerline](https://github.com/Mourya20/Ledgerline)

LedgerLine is a secure, double-entry banking and money-movement platform built with Java, Spring Boot, and PostgreSQL. It enforces immutable ledger accounting, mathematical zero-sum transaction invariants, defense-in-depth security, and database-level transactional integrity.

Phase 1 established the PostgreSQL schema, JPA domain model, normal-balance accounting engine, database-backed session authentication, fine-grained role-based authorization, rate limiting, and immutable audit logging.

**Phase 2 delivers the complete financial transaction engine and comprehensive remediation**, providing authenticated customer account management, simulated cash deposits and withdrawals, atomic peer-to-peer customer transfers, PostgreSQL transaction-scoped advisory locking (`pg_advisory_xact_lock`), deterministic deadlock prevention, robust idempotency with canonical SHA-256 fingerprinting, hardened least-privilege database roles, viewer-aware response privacy masking, and complete financial reconciliation test suites.

> [!NOTE]
> **Operational Disclaimer**: The deposit and withdrawal endpoints represent simulated external cash-in and cash-out operations against an internal system cash vault. They are not connected to real external payment gateways or banking rails. The system currently operates with INR (`INR`) as the authoritative currency.

---

## 1. Architecture & Design Principles

```
┌───────────────────────────────────────────────────────────┐
│               Spring Boot 4.1.2 Backend                  │
│  - REST Controllers (Auth, Account, Transaction, Audit)   │
│  - Spring Security (Sessions, CSRF, Strict RBAC)          │
│  - Login Rate Limiter (Atomic Reservation, Synchronized LinkedHashMap LRU)│
│  - AccountService (Secure Bounded Number Generation)      │
│  - TransactionService (Advisory Locks with SHA-256 Long Key, Canonical Fingerprint)│
│  - Deterministic Account Lock Ordering (Pessimistic Lock) │
│  - Viewer-Aware Response Masking (Privacy by Design)      │
│  - Batch Transaction History (Eliminates N+1 Queries)     │
│  - Audit Event Dispatcher (Sanitized Metadata)           │
│  - Spring Data JPA / Hibernate (ddl-auto=validate)        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                    JDBC / HikariCP Pool
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                 PostgreSQL 18 Database                   │
│  - Flyway Migrations (V1 — V9)                           │
│  - Operational System Cash Vault (`SYS-INR-CASH-VAULT-001`)│
│  - Normal Balance Modeling (DEBIT / CREDIT)               │
│  - Account Ownership & Classification Invariants         │
│  - Transaction-Scoped Advisory Locking (`pg_advisory_xact_lock`) │
│  - Row-Level Locking (`FOR UPDATE` on accounts)          │
│  - Trigger-Maintained Balance Projections                 │
│  - Call-Stack Balance Protection (PG_CONTEXT)             │
│  - Hardened Least-Privilege Role (`ledgerline_runtime`)  │
│    * No TRUNCATE across all schema tables                 │
│    * No DELETE across all schema tables                   │
│    * Column-restricted UPDATE on users (status, updated_at)│
│    * Column-restricted UPDATE on accounts (status, updated_at)│
│    * No UPDATE/DELETE on append-only ledger and audit logs│
│  - Append-Only Ledger Entries (UPDATE/DELETE Forbidden)   │
│  - Append-Only Audit Logs (UPDATE/DELETE Forbidden)       │
│  - Deferred Commit Invariant Triggers (Zero-Sum Debits=Credits) │
│  - Customer Balance Non-Negative Constraints             │
│  - Composite Idempotency Constraint (`user_id`, `key`)   │
└───────────────────────────────────────────────────────────┘
```

### Core Accounting Principles
1. **Ledger Primacy**: Ledger entries are the authoritative, immutable source of financial truth. The `accounts.balance` column is an optimized projection maintained automatically by database triggers. Direct modification of `balance` is strictly forbidden and blocked both by role privileges and call-stack trigger guards.
2. **Normal Balance Classification**: Accounts explicitly specify their normal balance (`normal_balance` column):
   - **Customer Liability Accounts (`CREDIT` normal)**: `Balance = Total Credits - Total Debits`
   - **Bank Asset / Vault Accounts (`DEBIT` normal)**: `Balance = Total Debits - Total Credits`
3. **Transaction Invariants**: Any transaction containing ledger entries must finish as `COMPLETED` at commit time, contain at least one debit and one credit, have equal total debits and credits (`Total Debits = Total Credits`), and maintain strict currency consistency across all entries and accounts. Allowed transaction statuses are strictly `PENDING`, `COMPLETED`, and `FAILED`.
4. **Account Ownership Rules**: Every `CUSTOMER` account must have an associated owner user (`owner_user_id IS NOT NULL`). Every `SYSTEM` account must have no owner user (`owner_user_id IS NULL`). Account classification (`account_type`, `normal_balance`, `currency`) is permanently immutable once ledger entries are recorded.
5. **Deterministic Concurrency Control**: Whenever multiple accounts are touched in a transaction (such as sender and receiver in a transfer, or customer and cash vault in deposits/withdrawals), all participating account IDs are sorted in ascending natural UUID order and locked using `SELECT ... FOR UPDATE` before balances are evaluated. This deterministic ordering substantially reduces lock-order deadlock risks under concurrent transfers between identical account pairs.
6. **Transaction-Scoped Idempotency Advisory Locking**: Every financial operation first acquires a PostgreSQL transaction-scoped advisory lock (`pg_advisory_xact_lock`) passing a 64-bit integer derived from a SHA-256 hash of `userId + ":" + normalizedKey`. This serializes concurrent duplicate requests from the same user across all accounts without incurring unique-constraint collision aborts. If a request is retried with the exact same payload, the original completed transaction is safely returned. If a different payload is supplied with an already-used key, HTTP 409 Conflict is returned.
7. **JPA Entity Synchronization**: Database triggers execute trigger-updated balance recalculations. The application executes `entityManager.flush()` followed by `entityManager.refresh(account)` within the active database transaction to ensure in-memory JPA entities match the database state.

---

## 2. Double-Entry Money Movement Workflows

### 2.1 Deposit Workflow (Simulated Cash In)
A deposit crediting a customer account creates balanced entries against the system operational cash asset account:

| Account | Classification | Normal Balance | Entry Type | Effect on Balance |
|---|---|---|---|---|
| System Cash Vault (`SYS-INR-CASH-VAULT-001`) | Asset | `DEBIT` | **DEBIT** | Increases asset balance |
| Customer Account (`VLTR...`) | Liability | `CREDIT` | **CREDIT** | Increases liability balance |

- **Invariants**: `Total Debits = Total Credits`. Customer balance increases. Cash vault balance increases.

### 2.2 Withdrawal Workflow (Simulated Cash Out)
A withdrawal debits the customer account and credits the system operational cash asset account:

| Account | Classification | Normal Balance | Entry Type | Effect on Balance |
|---|---|---|---|---|
| Customer Account (`VLTR...`) | Liability | `CREDIT` | **DEBIT** | Decreases liability balance |
| System Cash Vault (`SYS-INR-CASH-VAULT-001`) | Asset | `DEBIT` | **CREDIT** | Decreases asset balance |

- **Validation**: Customer balance is verified **after** acquiring the row lock. If `balance < requested_amount`, the transaction is aborted with HTTP 422 Unprocessable Content. Overdrafts are strictly prevented. System cash liquidity is also verified.

### 2.3 Peer-to-Peer Transfer Workflow
An internal transfer between two customer accounts moves liability directly between customers without affecting system cash reserves:

| Account | Classification | Normal Balance | Entry Type | Effect on Balance |
|---|---|---|---|---|
| Sender Account (`VLTR...`) | Liability | `CREDIT` | **DEBIT** | Decreases sender balance |
| Receiver Account (`VLTR...`) | Liability | `CREDIT` | **CREDIT** | Increases receiver balance |

- **Invariants**: `Total Debits = Total Credits`. System cash vault balance is unchanged. Self-transfers and transfers to system accounts are rejected with HTTP 400 Bad Request.

---

## 3. Technology Stack

- **Java**: 25 (Temurin / OpenJDK)
- **Spring Boot**: 4.1.2
- **Spring Security**: 6.x (Session-based, BCrypt, CSRF, Session Fixation Protection, Strict RBAC)
- **Database**: PostgreSQL 18
- **ORM**: Hibernate 7.4 / Spring Data JPA (`ddl-auto=validate`)
- **Database Migrations**: Flyway 11.x
- **Build Tool**: Maven Wrapper (`mvnw.cmd`)
- **Containerization**: Docker & Docker Compose

---

## 4. Database Setup & Least-Privilege Configuration

The application enforces strict credential separation between schema management (Flyway / `ledgerline_app`) and runtime query execution (JPA / `ledgerline_runtime`), using `DB_PASSWORD` as the single source of truth for the migration role and `APP_DB_PASSWORD` for the runtime role:

### Environment Variables (`.env`)
```properties
# PostgreSQL Container Administrator & Migration Identity (Schema Owner: ledgerline_app)
DB_PASSWORD=replace_with_a_strong_random_password

# Application Runtime Identity (Least Privilege: ledgerline_runtime)
APP_DB_URL=jdbc:postgresql://localhost:5432/ledgerline_db
APP_DB_USERNAME=ledgerline_runtime
APP_DB_PASSWORD=replace_with_runtime_password

# Flyway Migration Identity (Uses DB_PASSWORD as single source of truth for ledgerline_app)
FLYWAY_DB_URL=jdbc:postgresql://localhost:5432/ledgerline_db
FLYWAY_DB_USERNAME=ledgerline_app
```

### Docker Compose
```powershell
docker compose up -d
```

- **Host Port**: `5432`
- **Migration Role**: `ledgerline_app` (schema owner for Flyway migrations)
- **Runtime Application Role**: `ledgerline_runtime` (strictly least-privileged role for backend queries)
- **Database**: `ledgerline_db`
- **Data Volume**: `ledgerline_data` (Do not delete or run `docker compose down -v`)

### Dedicated Test Database (`ledgerline_test_db`)
The test suite executes exclusively against an isolated test database (`ledgerline_test_db`). When starting via Docker Compose on an empty volume, `docker/initdb/01-init-runtime-role.sh` creates `ledgerline_test_db` automatically. If initializing manually or connecting to an existing PostgreSQL instance without running the initialization script, create the database manually:

```powershell
docker exec ledgerline-postgres psql -U ledgerline_app -d postgres -c "CREATE DATABASE ledgerline_test_db OWNER ledgerline_app;"
```

- **Isolation Guard**: The test harness includes `TestDatabaseGuard`, a runtime safety check that asserts `current_database().equals("ledgerline_test_db")` before allowing test execution or fixture teardown. If misconfigured to point to `ledgerline_db` or production, tests abort immediately with an `IllegalStateException`.

---

## 5. Database Migrations (V1 — V9)

Flyway manages all schema changes sequentially. Existing migrations are strictly immutable:

- **`V1__initial_schema.sql`**: Baseline tables (`users`, `accounts`, `ledger_transactions`, `ledger_entries`, `audit_logs`), check constraints, and preliminary triggers.
- **`V2__widen_currency_columns.sql`**: Forward migration widening currency columns from `CHAR(3)` to `VARCHAR(3)` for Hibernate string-type compatibility.
- **`V3__widen_audit_fingerprint.sql`**: Forward migration widening audit log fingerprint column from `CHAR(64)` to `VARCHAR(64)`.
- **`V4__ledger_database_protections.sql`**: Audit log immutability trigger (`audit_logs_immutability_trg`) rejecting `UPDATE` and `DELETE`.
- **`V5__remediate_accounting_model_and_ledger_integrity.sql`**:
  - Adds `normal_balance VARCHAR(6) NOT NULL DEFAULT 'CREDIT'` and `allow_negative BOOLEAN NOT NULL DEFAULT FALSE` to `accounts`.
  - Replaces global non-negative constraint with `accounts_customer_policy_chk` and `accounts_balance_policy_chk`.
  - Rewrites `apply_ledger_entry_to_balance()` with `FOR UPDATE` row locking and normal-balance calculation rules.
  - Rewrites `validate_ledger_entry()` to forbid `UPDATE` and `DELETE` on `ledger_entries` (strictly append-only), forbid adding entries to completed transactions, and ensure currency matching.
  - Adds deferred commit constraint triggers (`ledger_transactions_integrity_at_commit` and `ledger_entries_transaction_integrity_at_commit`) calling `check_transaction_integrity_by_id()` (`INITIALLY DEFERRED`) to validate zero-sum balance and transaction status at commit.
- **`V6__account_ownership_immutability_and_privileges.sql`**:
  - Enforces account ownership check constraint: `CUSTOMER` accounts must have an owner (`owner_user_id IS NOT NULL`); `SYSTEM` accounts must not (`owner_user_id IS NULL`).
  - Restricts transaction statuses strictly to `PENDING`, `COMPLETED`, `FAILED`.
  - Reconciles account balance projections and asserts that accounts with zero ledger entries must possess zero balance.
  - Trigger `protect_account_classification()`: freezes `account_type`, `normal_balance`, and `currency` as permanently immutable once ledger entries exist for the account.
  - Trigger `protect_account_balance_projection()`: inspects `PG_CONTEXT` call-stack diagnostics to forbid direct SQL balance mutations outside `apply_ledger_entry_to_balance()`.
- **`V7__provision_system_accounts_and_financial_engine_indexes.sql`**:
  - Seeds operational `SYSTEM` cash asset vault account for INR (`id = '00000000-0000-0000-0000-000000000001'`, `account_number = 'SYS-INR-CASH-VAULT-001'`, `currency = 'INR'`, `normal_balance = 'DEBIT'`).
  - Provisions performance indexes on `ledger_transactions(created_at DESC)`, `ledger_entries(account_id, created_at DESC)`, and `ledger_entries(account_id, entry_type)`.
- **`V8__harden_runtime_database_privileges.sql`**:
  - Revokes `TRUNCATE` across all tables from `ledgerline_runtime`.
  - Revokes `DELETE` across all tables from `ledgerline_runtime`.
  - Column-restricts `UPDATE` on `users` strictly to `(status, updated_at)` (eliminates user role escalation and direct password_hash tampering).
  - Column-restricts `UPDATE` on `accounts` strictly to `(status, updated_at)` (revokes `allow_negative`, blocks arbitrary balance tampering).
  - Column-restricts `UPDATE` on `ledger_transactions` to `(status, completed_at)`.
  - Reaffirms append-only `ledger_entries` and `audit_logs` (all `UPDATE` and `DELETE` revoked).
  - Revokes `CREATE` on schema `public`.
  - Formally validates canonical properties of operational system vault `SYS-INR-CASH-VAULT-001`.
- **`V9__prevent_privilege_escalation_and_balance_fabrication.sql`**:
  - Enforces `ALTER ROLE ledgerline_runtime NOINHERIT;`.
  - Revokes `CREATE` on schema `public` from `PUBLIC` (`REVOKE CREATE ON SCHEMA public FROM PUBLIC;`).
  - Trigger `trg_protect_user_role_assignment` calling `protect_user_role_assignment()`: aborts `INSERT` on `users` under runtime identity if `role <> 'CUSTOMER'` with SQLState `42501`.
  - Trigger `trg_protect_account_insertion` calling `protect_account_insertion()`: aborts `INSERT` on `accounts` under runtime identity if `account_type <> 'CUSTOMER'`, `owner_user_id IS NULL`, `balance <> 0.00`, `normal_balance <> 'CREDIT'`, `allow_negative = TRUE`, or `currency <> 'INR'` with SQLState `42501`.

---

## 6. Role-Based Access Control (RBAC) Matrix

| Endpoint / Action | Method | Unauthenticated | CUSTOMER | AUDITOR | ADMIN |
|---|---|:---:|:---:|:---:|:---:|
| `/api/v1/auth/register` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/login` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/csrf` | `GET` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/me` | `GET` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/auth/logout` | `POST` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/accounts` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/accounts` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}/balance` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/transactions/deposit` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions/withdraw` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions/transfer` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/transactions/{id}` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/audit/logs` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/actuator/health` | `GET` | Permitted | Permitted | Permitted | Permitted |

*Key Security Guard: The `AUDITOR` role has strictly read-only capabilities over the audit subsystem and cannot create accounts or initiate money movements (deposits, withdrawals, transfers).*

---

## 7. REST API Reference & Response Privacy

All mutating financial endpoints require an active session cookie, a valid CSRF token header (`X-XSRF-TOKEN`), and a unique `Idempotency-Key` header.

### 7.1 Create Account
- **Endpoint**: `POST /api/v1/accounts`
- **Request Body**:
```json
{
  "currency": "INR"
}
```
- **Response (201 Created)**:
```json
{
  "id": "e6a147d3-f01e-450a-81a9-b69c7f1a3011",
  "accountNumber": "VLTR293847581920",
  "currency": "INR",
  "balance": 0.00,
  "status": "ACTIVE",
  "accountType": "CUSTOMER",
  "createdAt": "2026-11-08T19:15:00Z"
}
```
*(Note: `normalBalance` is stripped from customer-facing responses to avoid leaking internal accounting classification).*

### 7.2 Deposit Funds (Simulated Cash In)
- **Endpoint**: `POST /api/v1/transactions/deposit`
- **Headers**: `Idempotency-Key: 8f7c1d2e-4b6a-4d3f-91c8-0e1234567890`
- **Request Body**:
```json
{
  "accountId": "e6a147d3-f01e-450a-81a9-b69c7f1a3011",
  "amount": 1000.00,
  "currency": "INR"
}
```
- **Response (201 Created)**:
```json
{
  "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "transactionType": "DEPOSIT",
  "status": "COMPLETED",
  "currency": "INR",
  "createdAt": "2026-11-08T19:15:30Z",
  "completedAt": "2026-11-08T19:15:30Z",
  "entries": [
    {
      "id": "11111111-2222-3333-4444-555555555555",
      "accountId": null,
      "accountNumber": "Cash/Bank Counterpart",
      "entryType": "DEBIT",
      "amount": 1000.00,
      "currency": "INR"
    },
    {
      "id": "66666666-7777-8888-9999-000000000000",
      "accountId": "e6a147d3-f01e-450a-81a9-b69c7f1a3011",
      "accountNumber": "VLTR293847581920",
      "entryType": "CREDIT",
      "amount": 1000.00,
      "currency": "INR"
    }
  ]
}
```
*(Note: System cash vault UUID is `null` and account number is masked as `"Cash/Bank Counterpart"`).*

### 7.3 Peer-to-Peer Transfer (Viewer-Aware Masking)
- **Endpoint**: `POST /api/v1/transactions/transfer`
- **Headers**: `Idempotency-Key: 9c3e7f1a-5678-1234-bcde-fabc12345678`
- **Request Body**:
```json
{
  "sourceAccountId": "e6a147d3-f01e-450a-81a9-b69c7f1a3011",
  "destinationAccountNumber": "VLTR987654321012",
  "amount": 350.00,
  "currency": "INR"
}
```
- **Sender's View (201 Created or GET /api/v1/transactions/{id})**:
```json
{
  "id": "c1d2e3f4-5678-90ab-cdef-1234567890ab",
  "transactionType": "TRANSFER",
  "status": "COMPLETED",
  "currency": "INR",
  "createdAt": "2026-11-08T19:20:00Z",
  "completedAt": "2026-11-08T19:20:00Z",
  "entries": [
    {
      "id": "entry-1-uuid",
      "accountId": "e6a147d3-f01e-450a-81a9-b69c7f1a3011",
      "accountNumber": "VLTR293847581920",
      "entryType": "DEBIT",
      "amount": 350.00,
      "currency": "INR"
    },
    {
      "id": "entry-2-uuid",
      "accountId": null,
      "accountNumber": "****1012",
      "entryType": "CREDIT",
      "amount": 350.00,
      "currency": "INR"
    }
  ]
}
```
*(Note: Receiver account ID is `null` and account number is masked as `****1012`. When the receiver views the same transaction, they see their own account ID and full number, while the sender's details are masked as `****1920` with null account ID).*

---

## 8. Running the Application & Tests

### Start Backend
Run from the `backend/ledgerline` directory:

```powershell
cd backend\ledgerline
.\mvnw.cmd spring-boot:run
```

The application runs on `http://localhost:8080`.

### Run Test Suite
Execute the complete test suite against `ledgerline_test_db`:

```powershell
cd backend\ledgerline
.\mvnw.cmd clean test
```

Or run the full Maven build and verification lifecycle:

```powershell
cd backend\ledgerline
.\mvnw.cmd clean verify
```

### Test Suite Summary (95 Total Tests, 100% Passed)
- **`Phase2FinancialEngineIntegrationTests`** (53 tests):
  - **Account Management (6 tests)**: Account creation, unique 12-digit `VLTR` account numbers, currency validation, owner-isolated listing, cross-customer access rejection (403), system account access rejection (403).
  - **Deposit Operations (5 tests)**: Balanced double-entry creation, balance increments, zero/negative amount rejection (400), missing idempotency key rejection (400), cross-customer deposit rejection (403), inactive account rejection (400), cross-currency deposit rejection (400).
  - **Withdrawal Operations (3 tests)**: Balanced double-entry creation, balance decrements, insufficient funds rejection (422), cross-customer withdrawal rejection (403).
  - **Transfer Operations (5 tests)**: Zero-sum internal transfers, sender debit and receiver credit, cash vault balance preservation, self-transfer rejection (400), transfer to system account rejection (403), insufficient sender balance rejection (422), non-existent recipient rejection (404).
  - **Idempotency & Validation (7 tests)**:
    - Same key and payload returns identical completed transaction without duplicate ledger entries.
    - Same key and different payload returns 409 Conflict.
    - Failed validation does not consume idempotency key.
    - Same user with same idempotency key across different accounts serializes and rejects duplicate (409).
    - Canonical fingerprint matches monetary amounts regardless of trailing zeroes (100.0 vs 100.00).
    - Validation rejects amounts with >2 decimal places, negative amounts, keys >255 chars, and unsupported currencies (400).
    - System counterpart misconfiguration safely aborts transaction without partial effects.
  - **PostgreSQL Concurrency Tests (4 multithreaded tests)**:
    - *Simultaneous withdrawals exceeding balance*: 2 concurrent threads each attempt 800 INR withdrawal against 1000 INR balance -> exactly 1 succeeds, other fails 422, final balance 200 INR, zero overdraft.
    - *Bilateral opposing transfers*: Simultaneous transfers A->B and B->A across parallel threads -> reduced deadlock risk via deterministic natural UUID ordering, balances preserved.
    - *Simultaneous duplicate requests*: Identical deposit requests with same idempotency key in parallel -> exactly 1 execution, identical transaction returned, zero duplicate entries.
    - *Simultaneous transfers to single destination*: Multiple senders transferring simultaneously to one receiver -> all succeed with accurate balances.
  - **Financial Reconciliation (1 test)**: Global Debit == Credit check across all ledger entries, and per-account verification that projected `balance` strictly equals `SUM(entries)` directly calculated in SQL.
  - **Security, Privileges & Privacy (22 tests)**:
    - Unauthenticated requests rejected (401).
    - AUDITOR role blocked from money movement (403).
    - Cross-user transaction access blocked (403).
    - Transaction history pagination and owner-isolation.
    - Runtime database role (`ledgerline_runtime`) cannot `TRUNCATE` any table (SQLException 42501).
    - Runtime database role cannot promote users to `ADMIN` (SQLException 42501).
    - Runtime database role cannot directly update user `password_hash` (SQLException 42501).
    - Runtime database role cannot modify account `allow_negative` or projected `balance` (SQLException 42501).
    - Runtime database role cannot delete from `ledger_entries` or `audit_logs` (SQLException 42501).
    - Runtime database role cannot directly `INSERT` users with `ADMIN` or `AUDITOR` roles (Migration V9 trigger, SQLException 42501).
    - Runtime database role cannot directly `INSERT` accounts with non-zero opening balances, arbitrary `SYSTEM` accounts, or `allow_negative=TRUE` (Migration V9 trigger, SQLException 42501).
    - Rate limit exhaustion records `AUTH_RATE_LIMIT_EXCEEDED` audit log event.
    - Deposit response masks system counterpart as `"Cash/Bank Counterpart"` with `null` accountId.
    - Transfer response masks counterpart customer with `****1234` and `null` accountId for both sender and receiver.
    - Customer `AccountResponse` does not expose `normalBalance`.
    - PostgreSQL Catalog: runtime role attributes enforce non-superuser, NOINHERIT, and least privilege (`pg_roles`).
    - PostgreSQL Catalog: runtime role inherits zero memberships or administrative options (`pg_auth_members`).
    - PostgreSQL Catalog: public schema tables are owned by migration administrator (`ledgerline_app`) and not runtime role (`pg_tables`).
    - PostgreSQL Catalog: runtime table privileges strictly limited to `SELECT` and `INSERT` (`information_schema.role_table_grants`).
    - PostgreSQL Catalog: column `UPDATE` grants strictly limited to status and timestamp columns (`information_schema.role_column_grants`).
    - PostgreSQL Catalog: schema `public` does not grant `CREATE` to runtime role or `PUBLIC` (`pg_namespace`).
- **`RuntimeRoleApplicationIntegrationTest`** (8 integration tests): End-to-end application execution directly under the restricted `ledgerline_runtime` PostgreSQL role:
  - Role verification: confirms `CURRENT_USER` and `SESSION_USER` equal `ledgerline_runtime`.
  - User registration through `AuthService` under runtime credentials.
  - Customer account creation and balance retrieval under runtime credentials.
  - Cash deposit (1,000 INR), completed transaction status, and trigger-projected account balance update.
  - Cash withdrawal (400 INR) with balance reduction to 600 INR.
  - Customer-to-customer transfer (250 INR) with dual-account balance updates (350 INR sender, 250 INR receiver).
  - Paginated transaction history retrieval with counterpart privacy masking under runtime credentials.
  - Database privilege enforcement: directly asserts that prohibited operations (direct balance update, ledger entry deletion, audit log deletion, table truncation, user role escalation, and balance fabrication) are rejected with PostgreSQL error `42501 permission denied`.
- **`Phase1IntegrationTests`** (31 integration tests): Core accounting mathematics, trigger projections, call-stack protections, session fixation, CSRF, BCrypt byte boundaries, login rate limits, and audit trail.
- **`LoginRateLimiterTest`** (6 unit tests): Sliding window algorithm, atomic reservation, independent email/IP buckets, bounded capacity, and access-ordered LRU eviction.
- **`MaxUtf8BytesValidatorTest`** (4 unit tests): UTF-8 boundary checks for BCrypt 72-byte limit.
- **`LedgerlineApplicationTests`** (1 test): Application context initialization and actuator health check.

**Total automated test suite**: **103 passing tests** across 6 test classes with zero failures, zero errors, and zero skipped tests.

---

## 9. Database Provisioning & Credential Security

LedgerLine enforces strict role separation between schema migration management (`ledgerline_app`) and runtime application query execution (`ledgerline_runtime`):

1. **Migration Administrator (`ledgerline_app`)**:
   - In the official PostgreSQL Docker image environment (`docker-compose.yml`), `POSTGRES_USER=ledgerline_app` initializes that account as the PostgreSQL cluster superuser during initial bootstrap. This gives it the administrative authority needed to create the isolated test database (`ledgerline_test_db`) and manage role-level grants. In contrast, in a manually provisioned environment, `ledgerline_app` can be a standard non-superuser database/schema owner (`CREATE ROLE ledgerline_app WITH LOGIN CREATEDB;`).
   - Schema owner with DDL privileges to execute Flyway migrations V1 through V9.
   - `DB_PASSWORD` acts as the single source of truth for `ledgerline_app` across PostgreSQL initialization and Flyway connection settings (`spring.flyway.password=${DB_PASSWORD}`).
2. **Application Runtime Role (`ledgerline_runtime`)**:
   - Least-privilege identity used exclusively by the Spring Boot backend (`spring.datasource.username`, `spring.datasource.password=${APP_DB_PASSWORD}`).
   - Attributes: `NOINHERIT`, `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `LOGIN`.
   - Table-level `SELECT` and `INSERT` only.
   - Zero table-level `UPDATE`, `DELETE`, or `TRUNCATE`.
   - Column-level `UPDATE` restricted strictly to `(status, updated_at)` on `users` and `accounts`, and `(status, completed_at)` on `ledger_transactions`.
   - Zero `CREATE` privileges on schema `public`.
   - Database boundary triggers prevent direct insertion of `ADMIN`/`AUDITOR` roles or fabricated opening balances.

### Historical Migration V6 & Credential Provisioning Across Environments

> [!IMPORTANT]
> **Historical Migration Notice**: Flyway migration `V6__account_ownership_immutability_and_privileges.sql` contains a historical literal fallback password in its role-creation statement (`CREATE ROLE ledgerline_runtime WITH LOGIN PASSWORD 'ledgerline_runtime_secret' ...`). Because already-applied Flyway migrations are immutable by design and must never be altered retrospectively, this statement remains in the historical migration script. Active application and test configurations do not rely on this fallback.

The system handles role provisioning across different operational scenarios as follows:

#### 1. Fresh Docker Installation (Empty Volume)
On a fresh container deployment, `docker-compose.yml` mounts `docker/initdb/01-init-runtime-role.sh` to `/docker-entrypoint-initdb.d:ro`. When PostgreSQL initializes:
1. It provisions `ledgerline_app` using `POSTGRES_USER` and `DB_PASSWORD`.
2. The initialization script verifies that `APP_DB_PASSWORD` is present and non-empty (failing immediately if absent).
3. The script pre-creates `ledgerline_runtime` with the configured `APP_DB_PASSWORD` using psql's `\getenv` and safe parameter quoting (`format('%L', :'runtime_pwd') \gexec`), supporting special characters and avoiding command-line exposure.
4. When Flyway executes migration V6, its `IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledgerline_runtime')` check finds the role already exists and skips the historical statement.
5. The init script provisions the isolated test database `ledgerline_test_db` owned by `ledgerline_app`.

#### 2. Fresh Manual / Non-Docker Installation
When deploying against a standalone PostgreSQL instance without Docker entrypoint scripts:
- Pre-provision the `ledgerline_runtime` role without embedding cleartext passwords into SQL statements:
  ```sql
  CREATE ROLE ledgerline_runtime WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  ```
- Set its password interactively using PostgreSQL's `\password` command:
  ```text
  \password ledgerline_runtime
  ```
- This ensures Flyway migration V6's `IF NOT EXISTS` check detects the role and skips the historical fallback statement, while avoiding storing or logging cleartext passwords in SQL command histories.

#### 3. Existing Database Volume (Password Rotation)
PostgreSQL initialization scripts in `/docker-entrypoint-initdb.d/` only run once on fresh, empty volumes; they do **not** re-run on an already-initialized data volume. Changing credentials in `.env` alone does not alter an existing PostgreSQL role's password in the database. Never delete, recreate, or reset an existing volume to rotate credentials.

To rotate passwords on an existing volume without exposing credentials in shell command arguments or process listings, use PostgreSQL's interactive `\password` command for each role:

##### Rotating `ledgerline_app` (Migration Administrator / DB_PASSWORD):
```bash
docker exec -it ledgerline-postgres psql -U ledgerline_app -d ledgerline_db -c "\password ledgerline_app"
```
Enter and confirm the new migration password interactively when prompted. Then:
1. Update `.env`: set `DB_PASSWORD=new_admin_password`.
2. Any subsequent Flyway migration run or administrative connection will authenticate using the updated password.

##### Rotating `ledgerline_runtime` (Application Runtime / APP_DB_PASSWORD):
```bash
docker exec -it ledgerline-postgres psql -U ledgerline_app -d ledgerline_db -c "\password ledgerline_runtime"
```
Enter and confirm the new runtime password interactively when prompted. Then:
1. Update `.env`: set `APP_DB_PASSWORD=new_runtime_password`.
2. Restart the backend application so HikariCP reconnects using the updated credentials:
```powershell
cd backend\ledgerline
.\mvnw.cmd spring-boot:run
```

---

## 10. Implementation Status

### Implemented in Phase 1 & Phase 2
- [x] Immutable PostgreSQL schema with Flyway migrations (V1—V9).
- [x] Hardened least-privilege runtime role (`ledgerline_runtime`) with `NOINHERIT`, `TRUNCATE` and `DELETE` revoked, and schema `CREATE` revoked.
- [x] Database boundary triggers preventing privilege escalation on `users` and balance fabrication on `accounts`.
- [x] Normal balance classification (`DEBIT` vs. `CREDIT`) and trigger-based projection.
- [x] Operational system cash vault account (`SYS-INR-CASH-VAULT-001`) with strict property validation.
- [x] Customer account management with unique bounded `VLTR` number generation and server-side owner binding.
- [x] Simulated double-entry cash deposits and withdrawals.
- [x] Atomic customer-to-customer transfers with zero-sum ledger invariants.
- [x] Concurrency-safe row-locking (`FOR UPDATE`) with deterministic UUID ordering.
- [x] Deadlock-free bilateral concurrent transfers.
- [x] Overdraft prevention with post-lock balance validation.
- [x] Concurrency-safe idempotency via PostgreSQL transaction-scoped advisory locks (`pg_advisory_xact_lock`).
- [x] Canonical SHA-256 payload fingerprinting (scale-2 normalization without rounding).
- [x] Cross-account idempotency serialization for the same user.
- [x] Viewer-aware response privacy masking (system vault hidden, counterpart accounts masked).
- [x] Optimized transaction history query eliminating N+1 entries pattern.
- [x] Sliding-window login rate limiting with access-ordered LRU eviction and security audit logging.
- [x] Safe global exception handling suppressing internal database stack traces to clients.
- [x] Audit logging for account creation, deposits, withdrawals, transfers, and rate limit exhaustion.
- [x] Fine-grained RBAC: `AUDITOR` strictly blocked from money movement and account creation.
- [x] Isolated PostgreSQL test database (`ledgerline_test_db`) and runtime safety guard (`TestDatabaseGuard`).
- [x] Automated PostgreSQL catalog assertions detecting privilege regressions in integration tests.
- [x] Formal engineering threat model ([THREATS.md](THREATS.md)).
- [x] 100% test pass rate across 103 automated integration and unit tests.

### Planned for Phase 3 & Beyond (Deferred)
- [ ] Compensating transaction reversal endpoint (`POST /api/v1/transactions/{id}/reverse`).
- [ ] React / Vite frontend client application.
- [ ] Multi-currency foreign exchange (FX) settlement accounts.
- [ ] Distributed Redis session clustering and multi-node rate limiting.
- [ ] External banking rail / payment gateway integration.
