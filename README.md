# Vaultora

**Repository:** [https://github.com/Mourya20/vaultora-banking](https://github.com/Mourya20/vaultora-banking)

Vaultora is a secure, double-entry banking and money-movement platform built with Java, Spring Boot, and PostgreSQL. It enforces immutable ledger accounting, mathematical zero-sum transaction invariants, defense-in-depth security, and database-level transactional integrity.

Phase 1 established the PostgreSQL schema, JPA domain model, normal-balance accounting engine, database-backed session authentication, fine-grained role-based authorization, rate limiting, and immutable audit logging.

Phase 2 delivered the complete financial transaction engine and audit remediation, providing authenticated customer account management, simulated cash deposits and withdrawals, atomic peer-to-peer customer transfers, PostgreSQL transaction-scoped advisory locking (`pg_advisory_xact_lock`), deterministic deadlock prevention, robust idempotency with canonical SHA-256 fingerprinting, hardened least-privilege database roles, viewer-aware response privacy masking, and complete financial reconciliation test suites.

Phase 3 introduced the responsive React + Vite + TypeScript frontend, implementing the banking specification with session authentication, CSRF double-submit protection, and a robust transaction idempotency lifecycle.

**Phase 4 introduces Extended Banking Controls and Governance**:
- Maker-checker dual-authorization workflows for administrative transfers and transaction reversals.
- Compensating transaction reversals linking original transactions with mathematical zero-sum ledger entries.
- Configurable per-transaction and rolling 24-hour daily transaction limits.
- Customer onboarding email verification and self-service password recovery workflows.
- Universal self-service password updates across Customer, Auditor, and Admin roles.
- Customer account-opening approval queues with live admin synchronization and polling.
- Official account statement downloads in PDF (`%PDF-1.4`) and CSV (with spreadsheet formula injection defense).
- Real-time Auditor inspection portal with multi-criteria transaction filtering, formula-sanitized CSV export, and complete Ledger Integrity verification.
- Interactive OpenAPI / Swagger 3 documentation (`/swagger-ui.html`) with relative host detection.
- Customer Real-Time Transaction Alerts (floating confirmation toasts and detailed receipts for deposit, withdrawal, and transfer).
- Admin Real-Time Request Notification Center (interactive header bell, live pending count badge, dropdown summary, and background polling toasts).
- Enhanced Landing Page (home branding, architecture highlights, and invariant verification indicators).

> [!NOTE]
> **Operational Disclaimer**: Vaultora is a secure banking simulation engineered to model production-grade financial architecture. The deposit and withdrawal endpoints represent simulated external cash-in and cash-out operations against an internal system cash vault (`SYS-INR-CASH-VAULT-001`). They are not connected to real external payment gateways or banking rails. The system currently operates with Indian Rupee (`INR`) as the authoritative currency.

---

## 1. Architecture & Design Principles

```
┌───────────────────────────────────────────────────────────┐
│               React 19 + Vite 8 Frontend                  │
│  - Multi-Portal Banking (Customer, Admin, Auditor)        │
│  - Session Authentication & Double-Submit CSRF Integration│
│  - Idempotency-Key Lifecycle & Uncertain Response Recovery│
│  - Real-Time Account Requests & Approval Polling          │
│  - PDF / CSV Statement Downloads & Formula Defense        │
│  - Self-Service Password Change & Password Recovery       │
└─────────────────────────────┬─────────────────────────────┘
                              │ HTTP (credentials: include)
┌─────────────────────────────▼─────────────────────────────┐
│               Spring Boot 4.1.2 Backend                   │
│  - REST Controllers (Auth, Account, Transaction, Admin,   │
│    Audit, MakerChecker, Statement, OpenAPI)               │
│  - Spring Security (Sessions, CSRF, Strict RBAC)          │
│  - Login Rate Limiter (Atomic Reservation, Synchronized   │
│    LinkedHashMap LRU)                                     │
│  - Transaction Limits Enforcement (Per-Tx & Rolling 24h)  │
│  - Maker-Checker Dual Authorization Engine                │
│  - Compensating Transaction Reversals Engine              │
│  - Auditor Ledger Integrity Verification Engine           │
│  - Advisory Locks with SHA-256 Canonical Fingerprinting   │
│  - Deterministic Pessimistic Row Locking (SELECT FOR UPDATE)│
│  - Viewer-Aware Response Privacy Masking                  │
│  - OpenAPI 3.0 / Swagger Documentation                    │
│  - Spring Data JPA / Hibernate (ddl-auto=validate)        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                    JDBC / HikariCP Pool
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                 PostgreSQL 18 Database                   │
│  - Flyway Migrations (V1 — V14)                          │
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
│    * Column-restricted UPDATE on ledger_transactions      │
│      (status, completed_at)                               │
│    * Security Definer Procedures for Password Mutations   │
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
docker exec vaultora-postgres psql -U ledgerline_app -d postgres -c "CREATE DATABASE ledgerline_test_db OWNER ledgerline_app;"
```

- **Isolation Guard**: The test harness includes `TestDatabaseGuard`, a runtime safety check that asserts `current_database().equals("ledgerline_test_db")` before allowing test execution or fixture teardown. If misconfigured to point to `ledgerline_db` or production, tests abort immediately with an `IllegalStateException`.

---

## 5. Database Migrations (V1 — V14)

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
- **`V10__allow_admin_password_assignment_and_auditor_provisioning.sql`**:
  - Introduces `SECURITY DEFINER` procedure `admin_assign_user_password(UUID, VARCHAR)` to allow admin password updates for `CUSTOMER` and `AUDITOR` users without granting table-level `UPDATE` on `users` to `ledgerline_runtime`.
  - Introduces `SECURITY DEFINER` procedure `admin_create_user(UUID, VARCHAR, VARCHAR, VARCHAR, VARCHAR)` for dynamic provisioning.
- **`V11__account_opening_requests.sql`**:
  - Creates table `account_requests` for customer account opening workflows (`PENDING`, `APPROVED`, `REJECTED`).
  - Grants least-privilege `SELECT, INSERT` to `ledgerline_runtime`.
  - Implements `SECURITY DEFINER` procedure `review_account_request(UUID, VARCHAR, VARCHAR, VARCHAR, UUID)`.
- **`V12__user_password_change.sql`**:
  - Introduces `SECURITY DEFINER` procedure `change_user_password(UUID, VARCHAR)` enabling self-service password changes across all roles.
- **`V13__concurrency_and_account_lifecycle_guards.sql`**:
  - Adds lifecycle constraints, unique indexes, and audit metadata indexing.
- **`V14__extended_banking_controls_and_recovery.sql`**:
  - Creates table `maker_checker_requests` for dual-authorization transfer and reversal workflows.
  - Creates table `transaction_limits` for per-transaction and rolling 24h limits.
  - Creates tables `email_verification_tokens` and `password_reset_tokens` for identity recovery.
  - Updates `ledger_transactions_status_chk` constraint to allow status `REVERSED`.
  - Updates trigger `prevent_completed_transaction_mutation()` to permit transitions to `REVERSED` when `NEW.related_transaction_id IS NOT NULL` (reversal invariant).
  - Aligns currency columns to `VARCHAR(3)` and enforces least-privilege grants (`SELECT, INSERT` only) on new tables for `ledgerline_runtime`.

---

## 6. Role-Based Access Control (RBAC) Matrix

| Endpoint / Action | Method | Unauthenticated | CUSTOMER | AUDITOR | ADMIN |
|---|---|:---:|:---:|:---:|:---:|
| `/api/v1/auth/register` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/login` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/csrf` | `GET` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/me` | `GET` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/auth/logout` | `POST` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/auth/password` | `POST` | Denied (401) | **Allowed** | **Allowed** | **Allowed** |
| `/api/v1/auth/forgot-password` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/reset-password` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/verify-email` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/resend-verification` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/accounts` | `POST` | Denied (401) | **Denied (403)\*** | **Denied (403)** | Allowed |
| `/api/v1/accounts` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}/balance` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}/statement/pdf` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}/statement/csv` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/accounts/{id}` | `DELETE` | Denied (401) | **Allowed (Own Zero-Bal)** | **Denied (403)** | Allowed |
| `/api/v1/account-requests` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/account-requests/my` | `GET` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/account-requests` | `GET` | Denied (401) | Denied (403) | **Allowed** | Allowed |
| `/api/v1/account-requests/{id}/review` | `POST` | Denied (401) | Denied (403) | **Denied (403)** | Allowed |
| `/api/v1/transactions/deposit` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions/withdraw` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions/transfer` | `POST` | Denied (401) | **Allowed** | **Denied (403)** | Allowed |
| `/api/v1/transactions` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/transactions/{id}` | `GET` | Denied (401) | **Allowed (Own)** | **Denied (403)** | Allowed |
| `/api/v1/admin/users` | `GET` / `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/users/{id}/status` | `PATCH` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/users/{id}/password` | `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/accounts` | `GET` / `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/accounts/{id}/status` | `PATCH` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/settings/limits` | `GET` / `PUT` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/maker-checker/transfer` | `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/maker-checker/reversal` | `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/maker-checker/requests` | `GET` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/admin/maker-checker/requests/{id}/review` | `POST` | Denied (401) | Denied (403) | Denied (403) | **Allowed** |
| `/api/v1/audit/logs` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/api/v1/audit/transactions` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/api/v1/audit/transactions/export` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/api/v1/audit/ledger-integrity` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/swagger-ui.html` / `/v3/api-docs` | `GET` | Permitted | Permitted | Permitted | Permitted |
| `/actuator/health` | `GET` | Permitted | Permitted | Permitted | Permitted |

*\*Note: Under Phase 4 governance, customer account creation requires an administrative approval workflow (`POST /api/v1/account-requests`). Direct `POST /api/v1/accounts` is reserved for administrators.*

*Key Security Guard: The `AUDITOR` role has strictly read-only capabilities across the audit subsystem and cannot create accounts or initiate money movements (deposits, withdrawals, transfers).*
| `/actuator/health` | `GET` | Permitted | Permitted | Permitted | Permitted |

*Key Security Guard: The `AUDITOR` role has strictly read-only capabilities over the audit subsystem and cannot create accounts or initiate money movements (deposits, withdrawals, transfers).*

### 6.1 Application Role Routing & Portal Architecture

Authentication occurs through `POST /api/v1/auth/login`. After successful login, the application retrieves the authenticated user identity and role from `GET /api/v1/auth/me` and routes dynamically:

1. **CUSTOMER** (`/dashboard`):
   - Access to the 5 core customer banking views: Dashboard, Accounts, Move money, History.
   - Enforces strict owner isolation: customers can only view and transact against their own accounts.
   - Prohibited from accessing administrative or auditing portals.
2. **AUDITOR** (`/auditor`):
   - Dedicated, read-only Auditor Portal for reviewing security, authentication, and ledger events.
   - Live paginated table backed by `GET /api/v1/audit/logs`.
   - Displays event timestamp, action, resource type/ID, actor UUID, client IP, and raw JSON metadata inspector.
   - Zero modification, deletion, or transaction-creation controls.
   - Prohibited from accessing customer banking operations (`POST /api/v1/accounts`, `POST /api/v1/transactions/**` return HTTP 403 Forbidden).
3. **ADMIN** (`/admin`):
   - Central Administration Control Center with live platform health inspection (`GET /actuator/health`) and aggregate banking stats (`GET /api/v1/admin/stats`).
   - Privileged Customer Account Provisioning: Open new deposit accounts (`0.00 INR` normal `CREDIT` balance) and assign them directly to any customer, auditor, or provision a new customer on-the-fly with an assigned Customer ID (`POST /api/v1/accounts`).
   - Customer Account Lifecycle Management: Inspect all customer accounts across the bank, query individual balances, and freeze, unfreeze, or close accounts (`PATCH /api/v1/admin/accounts/{id}/status`).
   - User Identity & Security Management: Inspect all registered users across roles, view account counts, and suspend/disable or re-activate compromised users (`PATCH /api/v1/admin/users/{id}/status`).
   - Bank-Wide Transaction Inspection: Full visibility into double-entry ledger transactions across all accounts with debit/credit breakdown (`GET /api/v1/transactions`).
   - Administrative Deposit & Fund Disbursement: Authority to disburse funds directly from the system cash asset vault into any customer deposit account (`POST /api/v1/transactions/deposit`).
   - Full Audit Oversight: Direct access to the immutable audit trail (`/auditor`) for comprehensive compliance and security review (`GET /api/v1/audit/logs`).
   - SQL Security Enforcement: Visibility into database trigger protections (`trg_protect_user_role_assignment`, `trg_protect_account_insertion`) and runtime privileges.
4. **Access Denied Guard** (`/access-denied`):
   - When a user navigates to an unauthorized route, the client-side router redirects to `/access-denied`, displaying the user's role badge, explanation, and a redirect button to their authorized portal. Backend Spring Security acts as the authoritative security boundary.

### 6.2 Failed-Login Rate Limiting Policy

Vaultora implements an in-memory sliding-window failed-attempt rate limiter (`LoginRateLimiter.java`):
- **Email Threshold**: Maximum 5 failed attempts per normalized email within a 60-second sliding window.
- **IP Threshold**: Maximum 30 failed attempts per client IP address within a 60-second sliding window (thwarting distributed credential stuffing).
- **Cooldown**: Dynamic cooldown based on the oldest attempt timestamp within the window, strictly capped at 60 seconds. The exact remaining cooldown is returned in the HTTP `Retry-After` header.
- **Failures Only**: Only rejected credentials (`BadCredentialsException`, `DisabledException`) increment failure counters. Successful logins do **not** consume failure allowances.
- **Account Recovery**: Successful authentication immediately clears the email failure counter (`recordSuccess`), while preserving the shared IP history.
- **Bounded Storage**: Backed by a thread-safe access-ordered LRU `LinkedHashMap` bounded at 10,000 tracked keys to prevent heap exhaustion.

### 6.3 Demonstration Environment & Evaluation Dataset

Vaultora includes pre-seeded synthetic demonstration accounts and sample data so evaluators can explore the CUSTOMER, AUDITOR and ADMIN interfaces and verify application functionality. These are assessment demonstration records, not real customer or financial records.

The demonstration dataset comprises:
- **12 CUSTOMER users** (`customer1@ledgerline.com` through `customer12@ledgerline.com`)
- **3 AUDITOR users** (`auditor1@ledgerline.com` through `auditor3@ledgerline.com`)
- **1 ADMIN user** (`admin1@ledgerline.com`)
- **19 Initial Customer Accounts** (Customers 1–7 allocated 2 accounts each; Customers 8–12 allocated 1 account each; all with ₹0.00 initial balance, `CREDIT` normal balance, and `ACTIVE` status)

Provisioning is executed via an explicit, idempotent Java utility:

```powershell
cd backend\ledgerline
.\mvnw.cmd exec:java "-Dexec.mainClass=com.vaultora.provision.DemoProvisioningTool"
```

#### Password & Credential Security Policy

- In accordance with security requirements, plaintext passwords are never stored in PostgreSQL and never committed to tracked repository files.
- Each demonstration user's initial evaluation password is configured to match their username (e.g., `customer1`, `auditor1`, `admin1`), or can be updated dynamically via authenticated self-service password update (`POST /api/v1/auth/password`).
- Passwords are encrypted as BCrypt hashes in the PostgreSQL `users` table.
- A confidential local evaluation credential sheet with allocated account numbers is automatically generated at `private/demo-credentials.local.md` during provisioning, strictly protected by `.gitignore` (`/private/`).

#### Demonstration Identities Reference

| Identity Label | Role | Authentication Email | Password Baseline | Initial Accounts | Purpose / Portal |
| :--- | :--- | :--- | :--- | :---: | :--- |
| `customer1` | **CUSTOMER** | `customer1@ledgerline.com` | `customer1` | 2 | Customer Banking (`/dashboard`) |
| `customer2` | **CUSTOMER** | `customer2@ledgerline.com` | `customer2` | 2 | Customer Banking (`/dashboard`) |
| `customer3` | **CUSTOMER** | `customer3@ledgerline.com` | `customer3` | 2 | Customer Banking (`/dashboard`) |
| `customer4` | **CUSTOMER** | `customer4@ledgerline.com` | `customer4` | 2 | Customer Banking (`/dashboard`) |
| `customer5` | **CUSTOMER** | `customer5@ledgerline.com` | `customer5` | 2 | Customer Banking (`/dashboard`) |
| `customer6` | **CUSTOMER** | `customer6@ledgerline.com` | `customer6` | 2 | Customer Banking (`/dashboard`) |
| `customer7` | **CUSTOMER** | `customer7@ledgerline.com` | `customer7` | 2 | Customer Banking (`/dashboard`) |
| `customer8` | **CUSTOMER** | `customer8@ledgerline.com` | `customer8` | 1 | Customer Banking (`/dashboard`) |
| `customer9` | **CUSTOMER** | `customer9@ledgerline.com` | `customer9` | 1 | Customer Banking (`/dashboard`) |
| `customer10` | **CUSTOMER** | `customer10@ledgerline.com` | `customer10` | 1 | Customer Banking (`/dashboard`) |
| `customer11` | **CUSTOMER** | `customer11@ledgerline.com` | `customer11` | 1 | Customer Banking (`/dashboard`) |
| `customer12` | **CUSTOMER** | `customer12@ledgerline.com` | `customer12` | 1 | Customer Banking (`/dashboard`) |
| `auditor1` | **AUDITOR** | `auditor1@ledgerline.com` | `auditor1` | 0 | Read-Only Audit Portal (`/auditor`) |
| `auditor2` | **AUDITOR** | `auditor2@ledgerline.com` | `auditor2` | 0 | Read-Only Audit Portal (`/auditor`) |
| `auditor3` | **AUDITOR** | `auditor3@ledgerline.com` | `auditor3` | 0 | Read-Only Audit Portal (`/auditor`) |
| `admin1` | **ADMIN** | `admin1@ledgerline.com` | `admin1` | 0 | System Administration (`/admin`) |

- **Account Baseline**: All customer accounts open with `₹0.00 INR` balance, `CREDIT` normal balance, and `ACTIVE` status in the double-entry ledger.
- **Admin Console Statistics**: The live Admin Console reflects all records in the database (the 16 demonstration identities and 21 current demo accounts, alongside pre-existing development and automated integration test records). Dynamic dataset scope selectors allow evaluators to view all system records or inspect the demonstration dataset specifically.
- **Local Credential Sheet**: A duplicate reference is written to `private/demo-credentials.local.md`, which is ignored by Git in `.gitignore` to prevent credential commits.
- **In-Session Portal Switcher**: The `<PortalSwitcher />` component enables switching between authorized portals within the active session (e.g. ADMIN can toggle between the Admin Console and Auditor Portal; Customer and Auditor see their authorized portal badge).

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

### 8.1 Start Backend
Run from the `backend/ledgerline` directory:

```powershell
cd backend\ledgerline
.\mvnw.cmd spring-boot:run
```

The backend application starts on `http://localhost:8080`.

### 8.2 Start Frontend
The frontend is a React 19 + Vite 8 application located in `frontend/`. Run from the `frontend` directory:

```powershell
cd frontend
npm ci
npm run dev
```

The Vite development server runs on `http://localhost:5173` with Hot Module Replacement (HMR) enabled.

#### Frontend Environment Variables
Configured in `frontend/.env` (see `frontend/.env.example`):
- `VITE_API_BASE_URL`: Optional custom backend base URL. When omitted (default during development), Vite's proxy automatically routes `/api/*` and `/actuator/*` requests directly to `http://localhost:8080`.

### 8.3 Running Backend and Frontend Together
1. **Start PostgreSQL**: Ensure the database container is running:
   ```powershell
   docker compose up -d
   ```
2. **Start Backend**: In one terminal window:
   ```powershell
   cd backend\ledgerline
   .\mvnw.cmd spring-boot:run
   ```
3. **Start Frontend**: In a second terminal window:
   ```powershell
   cd frontend
   npm run dev
   ```
4. Access the web application at `http://localhost:5173`. All `/api` requests are proxied to `http://localhost:8080`, preserving a single-origin context for cookies and CSRF protection.

### 8.4 Frontend Authentication & Security Mechanics
1. **Session Cookie Authentication**:
   - Authentication relies on Spring Security's server-managed session (`JSESSIONID`) with `SameSite=Lax` and `HttpOnly` attributes.
   - The frontend API client invokes `fetch` with `credentials: "include"`, ensuring session cookies are attached across all authenticated requests.
   - Session tokens or credentials are never stored in client-side Web Storage (`localStorage` or `sessionStorage`).
2. **Double-Submit CSRF Protection**:
   - Mutating state requests (`POST`, `PUT`, `DELETE`) require a valid CSRF token.
   - The frontend issues an initial `GET /api/v1/auth/csrf` to initialize the `XSRF-TOKEN` cookie, and subsequent mutating requests read the cookie and set the `X-XSRF-TOKEN` HTTP header.
3. **Transaction Idempotency Lifecycle & Uncertain Response Recovery**:
   - All financial mutations (`deposit`, `withdraw`, `transfer`) mandate an `Idempotency-Key` header with a client-generated UUID.
   - **Intent Preparation**: When a user begins a transaction, an intent is prepared with a unique key and the exact transaction payload.
   - **Uncertain State Persistence**: If a network timeout, server 5xx error, or connection disruption occurs before a definitive response is received, the intent is flagged as uncertain and persisted in `sessionStorage` (`vaultora_tx_intent`).
   - **Refresh Recovery**: Reloading the page detects the pending intent and presents a high-visibility warning banner offering:
     - **Retry Pending Transaction**: Re-submits the exact identical payload with the preserved original idempotency key. PostgreSQL advisory locking and SHA-256 fingerprint deduplication ensure the transaction executes at most once.
     - **Discard Intent**: Allows the user to abandon the intent explicitly, clearing storage and enabling new transactions.
   - **Payload Immutability**: The user is prevented from submitting an altered payload while an intent remains unresolved.
   - **Success Resolution**: Upon receiving an HTTP `201 Created` response, the intent is immediately cleared from `sessionStorage`, and the next transaction generates a fresh idempotency key.

### 8.5 Automated Test Suites

#### Backend Test Suite
Execute the complete backend test suite against `ledgerline_test_db`:

```powershell
cd backend\ledgerline
.\mvnw.cmd clean test
```

Or run the full Maven build and verification lifecycle:

```powershell
cd backend\ledgerline
.\mvnw.cmd clean verify
```

#### Frontend Test Suite & Production Build
Execute Vitest test suite and production build from `frontend/`:

```powershell
cd frontend
npm test          # Runs 37 automated tests across 8 test suites
npm run build     # Runs tsc -b and vite build (verifies strict types and production bundle)
```

### 8.6 Test Suite Summary (100% Passed)
- **Backend Test Suite (104+ tests, 100% passed)**:
  - **`Phase2FinancialEngineIntegrationTests`** (56 tests): Account management, deposits, withdrawals, transfers, idempotency, PostgreSQL advisory locks, concurrency, reconciliation, RBAC, least privilege, privacy masking.
  - **`ExtendedBankingControlsIntegrationTests`** (8 tests): Maker-checker dual authorization, compensating reversals, per-transaction and rolling limits, PDF/CSV statement generation with formula defense, auditor transaction CSV export, auditor ledger integrity verification, customer email verification, and self-service password recovery.
  - **`Phase1IntegrationTests`** (31 tests): Core accounting mathematics, trigger projections, call-stack protections, session fixation, CSRF, BCrypt byte boundaries, login rate limits, and audit trail.
  - **`RuntimeRoleApplicationIntegrationTest`** (8 tests): End-to-end application execution directly under the restricted `ledgerline_runtime` PostgreSQL role: role identity, registration, account creation, deposits, withdrawals, transfers, history retrieval, database privilege rejection assertions.
  - **Unit and Helper Suites**: `LoginRateLimiterTest` (6 tests), `MaxUtf8BytesValidatorTest` (4 tests), `VaultoraApplicationTests` (1 test).
- **Frontend Test Suite (37 tests across 8 test files, 100% passed)**:
  - **`src/api/client.test.ts`** (2 tests): Base URL routing, JSON headers, CSRF double-submit token attachment, API error parsing.
  - **`src/api/auth.test.ts`** (1 test): Authenticated password change integration.
  - **`src/api/accountRequests.test.ts`** (4 tests): Account opening request submission, listing, review actions.
  - **`src/utils/formatters.test.ts`** (10 tests): Currency formatting, date formatting, account masking, deposit/withdrawal/external transfer parsing, and own-account transfer parsing distinguishing sender and receiver legs with `focusAccountId`.
  - **`src/utils/validation.test.ts`** (7 tests): Registration password length (min 12 chars), UTF-8 byte boundary calculation (`TextEncoder`), 72-byte rejection, and multibyte character handling.
  - **`src/utils/idempotencyManager.test.ts`** (6 tests): Intent generation, payload matching, uncertain state flagging, `sessionStorage` persistence and restoration, and intent discarding.
  - **`src/components/layout/AppLayout.test.tsx`** (4 tests): Desktop sidebar rendering, mobile hamburger button toggle, accessibility attributes (`aria-expanded`, `aria-label`), and navigation links.
  - **`src/pages/RoleRouting.test.tsx`** (3 tests): Role-aware route guard verification, AccessDeniedPage rendering with role badge, and role authorization enforcement.

**Total automated test suite**: **141+ passing tests** with zero failures, zero errors, and zero skipped tests.

---

### 8.7 Production Deployment Guide (Render + Vercel + Managed PostgreSQL)

The platform is engineered for cloud deployment across the following topology:

| Component | Target Platform | Runtime / Framework | Configuration Artifacts |
|---|---|---|---|
| **Frontend** | **Vercel** | React 19 + Vite 8 (Node.js 20+) | `frontend/vercel.json`, `frontend/package.json` |
| **Backend** | **Render** | Java 25 Spring Boot 4.1.2 (Docker) | `backend/ledgerline/Dockerfile`, `render.yaml` |
| **Database** | **Render / Supabase / Neon** | PostgreSQL 16+ | Flyway Migrations V1–V9, `scripts/provision-demo-users.sql` |

#### Step 1: Provision Managed PostgreSQL Database
1. Provision a PostgreSQL 16+ instance on Render, Supabase, Neon, or Aiven.
2. Note the database connection string (`postgres://...`).
3. Flyway migrations V1 through V9 will automatically initialize the database schema on first backend launch.
4. Execute the secure demonstration provisioning script under administrative credentials:
   ```bash
   # Connect to your managed PostgreSQL using psql:
   psql "<MANAGED_POSTGRES_CONNECTION_STRING>" -f scripts/provision-demo-users.sql
   ```
   This provisions active `ADMIN` (`admin@ledgerline.com` / `Admin123456!`) and `AUDITOR` (`auditor@ledgerline.com` / `Auditor123456!`) accounts with verified BCrypt hashes without exposing cleartext passwords or bypassing DB trigger controls.

#### Step 2: Deploy Backend to Render
1. Push your branch to GitHub.
2. In Render, select **New > Web Service** (or use the Blueprint in `render.yaml`).
3. Connect your repository:
   - **Environment**: `Docker`
   - **Dockerfile Path**: `backend/ledgerline/Dockerfile`
   - **Docker Context**: `backend/ledgerline`
   - **Health Check Path**: `/actuator/health`
4. Configure Production Environment Variables in Render:
   - `PORT`: `8080`
   - `APP_DB_URL`: `jdbc:postgresql://<HOST>:<PORT>/<DATABASE>`
   - `APP_DB_USERNAME`: `<YOUR_RUNTIME_OR_ADMIN_USER>`
   - `APP_DB_PASSWORD`: `<YOUR_DB_PASSWORD>`
   - `FLYWAY_DB_URL`: `jdbc:postgresql://<HOST>:<PORT>/<DATABASE>`
   - `FLYWAY_DB_USERNAME`: `<YOUR_MIGRATION_ADMIN_USER>`
   - `DB_PASSWORD`: `<YOUR_DB_PASSWORD>`
   - `COOKIE_SECURE`: `true`
   - `COOKIE_SAME_SITE`: `none` (or `lax` if using Vercel API proxy)
   - `FRONTEND_ORIGIN`: `https://<YOUR_APP>.vercel.app`
5. Deploy the service. Verify that `https://<YOUR_RENDER_SERVICE>.onrender.com/actuator/health` returns `{"status": "UP"}`.

#### Step 3: Deploy Frontend to Vercel
1. In Vercel, select **Add New > Project** and import the repository (`https://github.com/Mourya20/vaultora-banking.git`).
2. Configure project settings:
   - **Root Directory**: `frontend`
   - **Framework Preset**: `Vite`
   - **Build Command**: `npm run build`
   - **Output Directory**: `dist`
3. Environment Variables:
   - `VITE_API_BASE_URL`: `https://vaultora-banking.onrender.com` (Render production API URL)
4. Deploy. Vercel automatically builds and deploys the SPA with client-side routing handled by `frontend/vercel.json` (`"rewrites": [{ "source": "/(.*)", "destination": "/" }]`) and `frontend/public/_redirects`.
5. **Continuous Deployment Workflow**:
   - Every `git push origin main` to GitHub automatically triggers a new deployment build on Vercel within ~30 seconds.
   - Alternatively, to trigger a redeploy from the Vercel Dashboard: Go to **Deployments** &rarr; select the active deployment &rarr; click **...** &rarr; **Redeploy**.
   - If using the Vercel CLI locally: `cd frontend && npx vercel --prod`.

#### Step 4: Production Environment Variable Reference Matrix

| Variable | Required By | Production Recommendation | Description |
|---|---|---|---|
| `PORT` | Backend | `8080` | Server listening port (automatically populated by Render). |
| `APP_DB_URL` | Backend | `jdbc:postgresql://.../ledgerline_db` | JDBC URL for application queries. |
| `APP_DB_USERNAME` | Backend | `ledgerline_runtime` | Least-privilege runtime PostgreSQL role. |
| `APP_DB_PASSWORD` | Backend | Strong secret | Application runtime database password. |
| `FLYWAY_DB_URL` | Backend | `jdbc:postgresql://.../ledgerline_db` | JDBC URL for Flyway migration execution. |
| `FLYWAY_DB_USERNAME` | Backend | `ledgerline_app` | Migration schema-owner PostgreSQL role. |
| `DB_PASSWORD` | Backend | Strong secret | Password for migration administrator account. |
| `COOKIE_SECURE` | Backend | `true` | Enforces HTTPS-only transmission for session cookies. |
| `COOKIE_SAME_SITE` | Backend | `none` (cross-origin) or `lax` (proxied) | Browser cookie partition policy. |
| `FRONTEND_ORIGIN` | Backend | `https://<app>.vercel.app` | Allowed CORS origin for credentials. |
| `VITE_API_BASE_URL` | Frontend | `https://<api>.onrender.com` | Backend origin for direct browser fetch calls. |

---

## 9. Database Provisioning & Credential Security

Vaultora enforces strict role separation between schema migration management (`ledgerline_app`) and runtime application query execution (`ledgerline_runtime`):

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
docker exec -it vaultora-postgres psql -U ledgerline_app -d ledgerline_db -c "\password ledgerline_app"
```
Enter and confirm the new migration password interactively when prompted. Then:
1. Update `.env`: set `DB_PASSWORD=new_admin_password`.
2. Any subsequent Flyway migration run or administrative connection will authenticate using the updated password.

##### Rotating `ledgerline_runtime` (Application Runtime / APP_DB_PASSWORD):
```bash
docker exec -it vaultora-postgres psql -U ledgerline_app -d ledgerline_db -c "\password ledgerline_runtime"
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

### Implemented Functionality
- [x] Immutable PostgreSQL schema with Flyway migrations (V1—V14).
- [x] Hardened least-privilege runtime role (`ledgerline_runtime`) with `NOINHERIT`, table-level `TRUNCATE` and `DELETE` revoked, schema `CREATE` revoked, and security-definer procedures.
- [x] Database boundary triggers preventing privilege escalation on `users` and balance fabrication on `accounts`.
- [x] Normal balance classification (`DEBIT` vs. `CREDIT`) and trigger-based projection.
- [x] Operational system cash vault account (`SYS-INR-CASH-VAULT-001`) with strict property validation.
- [x] Customer account management with unique bounded `VLTR` number generation and server-side owner binding.
- [x] Simulated double-entry cash deposits and withdrawals against operational system cash vault.
- [x] Atomic customer-to-customer transfers with zero-sum ledger invariants.
- [x] Concurrency-safe row-locking (`FOR UPDATE`) with deterministic UUID ordering.
- [x] Overdraft prevention with post-lock balance validation.
- [x] Concurrency-safe idempotency via PostgreSQL transaction-scoped advisory locks (`pg_advisory_xact_lock`).
- [x] Canonical SHA-256 payload fingerprinting (scale-2 normalization without rounding).
- [x] Viewer-aware response privacy masking (system vault hidden, counterpart accounts masked).
- [x] Sliding-window login rate limiting with access-ordered LRU eviction and security audit logging.
- [x] Three-role application architecture with role-aware routing, dedicated Auditor Portal (`/auditor`), Admin Console (`/admin`), and Access Denied handling (`/access-denied`).
- [x] Customer account-opening requests (`POST /api/v1/account-requests`) with administrative approval queue, status badges, and real-time polling synchronization.
- [x] Universal self-service password update (`POST /api/v1/auth/password`) across Customer, Auditor, and Admin roles with UTF-8 byte boundary checks and procedure-level DB security.
- [x] Customer email verification (`POST /api/v1/auth/verify-email`, `POST /api/v1/auth/resend-verification`) with dedicated `/verify-email` frontend route.
- [x] Self-service password recovery (`POST /api/v1/auth/forgot-password`, `POST /api/v1/auth/reset-password`) with `/forgot-password` and `/reset-password` frontend routes.
- [x] Compensating transaction reversals linking original transactions via `related_transaction_id` and setting status to `REVERSED`.
- [x] Configurable per-transaction and rolling 24-hour daily transaction limits (`GET/PUT /api/v1/admin/settings/limits`).
- [x] Maker-checker dual-authorization workflows (`/api/v1/admin/maker-checker/**`) enforcing the four-eyes principle (initiators cannot self-approve).
- [x] Official account statement downloads in PDF (`%PDF-1.4`) and CSV formats with date range filtering and spreadsheet formula injection sanitization.
- [x] Auditor transaction inspection with multi-criteria filtering, pagination, and formula-sanitized CSV export.
- [x] Auditor Ledger Integrity verification (`/api/v1/audit/ledger-integrity`) asserting global debit=credit equality, zero unbalanced transactions, and zero account balance discrepancies.
- [x] OpenAPI 3.0 / Swagger documentation endpoints (`/swagger-ui.html`, `/v3/api-docs`).
- [x] 100% automated test pass rate across unit, integration, and frontend suites (151 backend integration tests and 37 frontend vitest tests).
- [x] Zero-warning, zero-error workspace code hygiene: complete resolution of 26 mapped workspace diagnostics across React Compiler / Oxlint rules and Java Red Hat JDT compiler warnings.
- [x] Hibernate `@DynamicUpdate` entity alignment ensuring SQL `UPDATE` statements emit only mutated columns, preserving PostgreSQL runtime least-privilege column grants (`role_column_grants`).

### Explicitly Excluded & Deferred Scope
- [ ] Multi-currency foreign exchange (FX) settlement accounts (currently single-currency `INR`).
- [ ] Distributed Redis session clustering and multi-node rate limiting (currently in-memory with session cookies).
- [ ] External banking rail / payment gateway integration (ACH, SEPA, UPI).
- [ ] **Excluded from Current Scope**: KYC (Know Your Customer) identity verification and automated suspicious transaction detection heuristics are outside the simulation scope. Customer email address ownership verification is identity-hygiene only and is not KYC. Token-based stateless authentication (JWT) is explicitly excluded in favor of Spring Security server-managed session cookies.
