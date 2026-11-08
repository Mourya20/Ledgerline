# LedgerLine

LedgerLine is a secure, double-entry banking and money-movement platform built with Java, Spring Boot, and PostgreSQL. It enforces immutable ledger accounting, mathematical zero-sum transaction invariants, defense-in-depth security, and database-level transactional integrity.

Phase 1 establishes the PostgreSQL schema, JPA domain model, normal-balance accounting engine, database-backed session authentication, fine-grained role-based authorization, rate limiting, and immutable audit logging. End-user money movement APIs (deposits, withdrawals, transfers) and the frontend application are scheduled for Phase 2.

---

## 1. Architecture & Design Principles

```
┌───────────────────────────────────────────────────────────┐
│               Spring Boot 4.1.2 Backend                  │
│  - REST Controllers (Auth, Audit, Admin)                  │
│  - Spring Security (Sessions, CSRF, RBAC)                │
│  - Login Rate Limiter (Sliding Window, Bounded Cache)     │
│  - Audit Event Dispatcher (Sanitized Metadata)           │
│  - Spring Data JPA / Hibernate (ddl-auto=validate)        │
└─────────────────────────────┬─────────────────────────────┘
                              │
                    JDBC / HikariCP Pool
                              │
┌─────────────────────────────▼─────────────────────────────┐
│                 PostgreSQL 18 Database                   │
│  - Flyway Migrations (V1 — V5)                           │
│  - Normal Balance Modeling (DEBIT / CREDIT)               │
│  - Row-Level Locking (`FOR UPDATE` on accounts)          │
│  - Trigger-Maintained Balance Projections                 │
│  - Append-Only Ledger Entries (UPDATE/DELETE Forbidden)   │
│  - Append-Only Audit Logs (UPDATE/DELETE Forbidden)       │
│  - Deferred Commit Invariant Triggers (Zero-Sum Debits=Credits) │
│  - Customer Balance Non-Negative Constraints             │
└───────────────────────────────────────────────────────────┘
```

### Core Accounting Principles
1. **Ledger Primacy**: Ledger entries are the authoritative, immutable source of financial truth. The `accounts.balance` column is an optimized projection maintained automatically by database triggers.
2. **Normal Balance Classification**: Accounts explicitly specify their normal balance (`normal_balance` column):
   - **Customer Liability Accounts (`CREDIT` normal)**: `Balance = Total Credits - Total Debits`
   - **Bank Asset / Vault Accounts (`DEBIT` normal)**: `Balance = Total Debits - Total Credits`
3. **Transaction Invariants**: Any transaction containing ledger entries must finish as `COMPLETED` at commit time, contain at least one debit and one credit, have equal total debits and credits, and maintain strict currency consistency across all entries and accounts.
4. **Pessimistic Concurrency**: Balance projection updates execute `SELECT ... FOR UPDATE` row locks on the affected accounts to prevent race conditions and concurrent double-spending.
5. **JPA Entity Synchronization**: Services reload trigger-updated entity balances using `AccountRepository.refresh(account)` within active transactions to avoid reading stale in-memory balances.

---

## 2. Technology Stack

- **Java**: 25 (Temurin / OpenJDK)
- **Spring Boot**: 4.1.2
- **Spring Security**: 6.x (Session-based, BCrypt, CSRF, Session Fixation Protection)
- **Database**: PostgreSQL 18
- **ORM**: Hibernate 7.4 / Spring Data JPA (`ddl-auto=validate`)
- **Database Migrations**: Flyway 11.x
- **Build Tool**: Maven Wrapper (`mvnw.cmd`)
- **Containerization**: Docker & Docker Compose

---

## 3. Database Setup & Isolation

### Development Database (`ledgerline_db`)
The application uses Docker Compose to run PostgreSQL 18. Copy `.env.example` to `.env` and set `DB_PASSWORD`.

```powershell
docker compose up -d
```

- **Host Port**: `5432`
- **Application Role**: `ledgerline_app`
- **Database**: `ledgerline_db`
- **Data Volume**: `ledgerline_data` (Do not delete or run `docker compose down -v`)

### Dedicated Test Database (`ledgerline_test_db`)
To protect operational data from accidental modification during test runs, the test suite executes exclusively against a dedicated test database:

```powershell
# Create test database within the PostgreSQL container
docker exec ledgerline-postgres psql -U ledgerline_app -d postgres -c "CREATE DATABASE ledgerline_test_db OWNER ledgerline_app;"
```

- **Isolation Guard**: The test harness includes `TestDatabaseGuard`, a runtime safety check that asserts `current_database().equals("ledgerline_test_db")` before allowing test execution or fixture teardown. If misconfigured to point to `ledgerline_db` or production, tests abort immediately with an `IllegalStateException`.

---

## 4. Database Migrations (V1 — V5)

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

---

## 5. Security Controls

LedgerLine implements a comprehensive defense-in-depth security model:

1. **BCrypt 72-Byte Truncation Defense**: BCrypt silently discards password bytes beyond 72 bytes. LedgerLine enforces `@MaxUtf8Bytes(max = 72)` on registration and login DTOs, rejecting oversized inputs with HTTP 400 before they reach the hashing algorithm.
2. **Session Fixation Protection**: Spring Security's `SessionFixationProtectionStrategy` is explicitly invoked upon login, invalidating the pre-auth session and issuing a newly generated session ID.
3. **Login Rate Limiting**: An in-memory sliding window rate limiter (`LoginRateLimiter`) throttles login attempts per IP address and per email address (5 attempts/min), mitigating brute-force and credential stuffing attacks (HTTP 429).
4. **Disabled User Session Revocation**: Inactive or suspended accounts (`enabled = false`) are rejected at authentication and during active sessions.
5. **CSRF Protection**: Enabled with `CookieCsrfTokenRepository(CookieHttpOnly = false)` and `CsrfTokenRequestAttributeHandler`. Requires `X-XSRF-TOKEN` for mutating HTTP requests.
6. **Hardened Cookies**: Session cookies are configured with `HttpOnly=true` and `SameSite=lax` (or `None` with `Secure=true` in production).
7. **Append-Only Immutability**: PostgreSQL triggers prevent modifying or deleting rows in `ledger_entries` and `audit_logs`.
8. **Sanitized Audit Trail**: `AuditService` records authentication lifecycle events (`AUTH_REGISTER_SUCCESS`, `AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILURE`, `AUTH_LOGOUT`, `AUTH_DISABLED_USER_ATTEMPT`, `AUTH_RATE_LIMIT_EXCEEDED`) without ever storing passwords, session tokens, or raw credentials in metadata.

For a complete threat analysis, see [THREATS.md](THREATS.md).

---

## 6. Role-Based Access Control (RBAC) Matrix

| Endpoint / Action | Method | Unauthenticated | CUSTOMER | AUDITOR | ADMIN |
|---|---|:---:|:---:|:---:|:---:|
| `/api/v1/auth/register` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/login` | `POST` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/csrf` | `GET` | Permitted | Permitted | Permitted | Permitted |
| `/api/v1/auth/me` | `GET` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/auth/logout` | `POST` | Denied (401) | Allowed | Allowed | Allowed |
| `/api/v1/audit/logs` | `GET` | Denied (401) | Denied (403) | **Allowed (Read-Only)** | Allowed |
| `/api/v1/audit/logs` | `POST` | Denied (401) | Denied (403) | **Denied (403)** | Allowed |
| `/api/v1/admin/**` | `GET` / `POST` | Denied (401) | Denied (403) | **Denied (403)** | Allowed |
| `/actuator/health` | `GET` | Permitted | Permitted | Permitted | Permitted |

*Registration always creates `CUSTOMER` accounts. Elevated roles (`ADMIN`, `AUDITOR`) cannot be requested through public endpoints.*

---

## 7. Running the Application

### Start Backend
Run from the `backend/ledgerline` directory:

```powershell
.\mvnw.cmd spring-boot:run
```

The application runs on `http://localhost:8080`.

---

## 8. Running the Tests

Execute the complete test suite against `ledgerline_test_db`:

```powershell
cd backend\ledgerline
.\mvnw.cmd test
```

Or run the full verification lifecycle:

```powershell
.\mvnw.cmd clean verify
```

### Test Suite Coverage
- **`Phase1IntegrationTests`**: 26 integration tests covering:
  - Deposit, withdrawal, and transfer normal-balance mathematics
  - Customer non-negative balance rejection
  - Transaction rollback on failure
  - JPA entity refresh after trigger balance projection updates
  - Completed balanced transaction commit invariants
  - Rejection of pending and failed transactions with entries
  - Rejection of unbalanced entries (Debits != Credits)
  - Append-only immutability of `ledger_entries` and `audit_logs`
  - Currency consistency validation
  - Session fixation protection and ID rotation
  - CSRF rejection on state-changing requests
  - Disabled user login rejection
  - Logout session invalidation and audit logging
  - Role-based authorization and AUDITOR read-only enforcement
  - BCrypt 72-byte UTF-8 password limit validation
  - Login rate limiting (sliding window HTTP 429)
  - Authentication audit log recording and sanitization
- **`LoginRateLimiterTest`**: 5 unit tests for IP/email rate limiting sliding windows and eviction.
- **`MaxUtf8BytesValidatorTest`**: 4 unit tests for UTF-8 multi-byte boundary validation.
- **`LedgerlineApplicationTests`**: Spring application context loading and health endpoint check.

---

## 9. Implementation Status

### Implemented in Phase 1
- [x] Immutable PostgreSQL schema with Flyway migrations (V1—V5).
- [x] Normal balance classification (`DEBIT` vs. `CREDIT`) and trigger-based projection.
- [x] Concurrency-safe row-locking (`FOR UPDATE`) on balance mutations.
- [x] Deferred commit constraint triggers enforcing zero-sum balanced transactions.
- [x] Append-only database protection for ledger entries and audit logs.
- [x] Database-backed session authentication with BCrypt hashing.
- [x] Max 72 UTF-8 byte password validation.
- [x] Session fixation protection with session ID rotation.
- [x] Sliding-window login rate limiting with HTTP 429 responses.
- [x] Fine-grained RBAC with explicit read-only access for `AUDITOR`.
- [x] Security audit event logging with credential sanitization.
- [x] Isolated test database configuration (`ledgerline_test_db`) and runtime safety guard (`TestDatabaseGuard`).
- [x] Formal engineering threat model ([THREATS.md](THREATS.md)).

### Planned for Phase 2 & Beyond (Deferred)
- [ ] Money movement REST API endpoints (Deposit, Withdrawal, Transfer).
- [ ] Transfer idempotency keys and replay protection.
- [ ] Account management and balance lookup REST resources.
- [ ] Compensating transaction reversal workflow endpoints.
- [ ] React / Vite frontend client application.
- [ ] Distributed Redis session clustering and multi-node rate limiting.
- [ ] Foreign exchange (FX) multi-currency clearing accounts.
