# Investigation Log

## 2026-11-08 - LedgerLine PostgreSQL and Spring Boot build failure

- Investigated Git status, Maven configuration, Spring datasource and Flyway settings, Compose configuration, ignored environment files, test sources, and the available test reports.
- Verified that `ledgerline-postgres` was healthy, listening on host port 5432, using the existing `ledgerline_data` volume, and serving `ledgerline_db` with the `ledgerline_app` role. The role has LOGIN and CONNECT privileges. No `postgres` role exists in this initialized cluster, confirming it was bootstrapped with the application role.
- Verified the configured application password over TCP without exposing its value. Authentication succeeded, so the reported password mismatch was not the underlying cause. The earlier investigation did not establish a password mismatch; no incorrect diagnosis is being recorded.
- The actual failure was PostgreSQL 18 rejecting the JVM timezone alias `Asia/Calcutta` sent by the JDBC driver. The container uses `Etc/UTC`, and the context test passed when the JVM timezone was forced to UTC.
- Corrected the configuration by loading the ignored root `.env` for local Spring runs, making Docker Compose use the same `DB_PASSWORD` interpolation instead of a duplicate hardcoded value, forcing UTC for Maven, and setting the application default timezone to UTC before Spring Boot starts.
- Verification completed: readiness passed; direct TCP authentication passed; the PostgreSQL JDBC dependency resolved; the default context test and complete Maven test suite passed; `mvnw.cmd clean package` passed; and the packaged application started on port 8080 with Flyway and JPA connected to PostgreSQL. `/actuator/health` returned HTTP 200 with status UP.
- Commit link: not available because these changes have not been committed.
