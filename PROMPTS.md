# Investigation Log

## 2026-11-08 - LedgerLine PostgreSQL and Spring Boot build failure

- Investigated Git status, Maven configuration, Spring datasource and Flyway settings, Compose configuration, ignored environment files, test sources, and the available test reports.
- Verified that `ledgerline-postgres` was healthy, listening on host port 5432, using the existing `ledgerline_data` volume, and serving `ledgerline_db` with the `ledgerline_app` role. The role has LOGIN and CONNECT privileges. No `postgres` role exists in this initialized cluster, confirming it was bootstrapped with the application role.
- Verified the configured application password over TCP without exposing its value. Authentication succeeded, so the reported password mismatch was not the underlying cause. The earlier investigation did not establish a password mismatch; no incorrect diagnosis is being recorded.
- The actual failure was PostgreSQL 18 rejecting the JVM timezone alias `Asia/Calcutta` sent by the JDBC driver. The container uses `Etc/UTC`, and the context test passed when the JVM timezone was forced to UTC.
- Corrected the configuration by loading the ignored root `.env` for local Spring runs, making Docker Compose use the same `DB_PASSWORD` interpolation instead of a duplicate hardcoded value, forcing UTC for Maven, and setting the application default timezone to UTC before Spring Boot starts.
- Verification completed: readiness passed; direct TCP authentication passed; the PostgreSQL JDBC dependency resolved; the default context test and complete Maven test suite passed; `mvnw.cmd clean package` passed; and the packaged application started on port 8080 with Flyway and JPA connected to PostgreSQL. `/actuator/health` returned HTTP 200 with status UP.
- Commit link: not available because these changes have not been committed.

## 2026-11-08 - Phase 1 database foundation and authentication

### Prompt

The user approved Phase 1 implementation after requesting a revised design for database foundation and authentication. The requested work was to add Flyway tables for users, accounts, ledger transactions, ledger entries, and audit logs; corresponding JPA entities and repositories; BCrypt registration and login; session authentication with CSRF; role and ownership structure; PostgreSQL-backed integration tests; README updates; and verification without deleting the PostgreSQL volume or pushing changes.

### AI output and implementation

The implementation added the initial schema in V1, forward-only type corrections in V2 and V3, and database integrity protections in V4. It added UUID-based JPA entities, repositories, BCrypt-backed database authentication, session security, CSRF protection, secure cookie settings, explicit CORS configuration, disabled-user session invalidation, authentication endpoints, and PostgreSQL integration tests.

### Verification and corrections

The first JPA validation run exposed a PostgreSQL `CHAR` versus Hibernate string-mapping mismatch for currency, followed by the same issue for the fingerprint column. These were corrected with forward-only migrations rather than editing applied migrations. A test fixture also exceeded the account-number length and was corrected. The final Maven suite passed 14 tests against PostgreSQL, including migration execution, authentication, CSRF, disabled users, audit immutability, completed-transaction validation, currency consistency, and balance-projection protection.

### Commit

Commit link: https://github.com/Mourya20/Vaultora-Banking-System/commit/6afeb88
