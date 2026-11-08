# LedgerLine

LedgerLine is a secure banking and money-movement platform under incremental development. Phase 1 establishes the PostgreSQL schema, JPA model, and database-backed session authentication. Deposits, withdrawals, transfers, and the frontend are not implemented yet.

## Architecture

The current backend is a single Spring Boot application using PostgreSQL as the system of record. Flyway owns schema changes and Hibernate validates the schema with `ddl-auto=validate`. Ledger entries are designed as the authoritative financial record, with database-maintained account balance projections and deferred balance checks for completed transactions.

## Technology

- Java 25
- Spring Boot 4.1.2
- Spring Security with BCrypt and server-side sessions
- Spring Data JPA and Hibernate
- PostgreSQL 18
- Flyway
- Maven Wrapper

## Database Setup

Copy `.env.example` to `.env` and set a strong `DB_PASSWORD`. Start PostgreSQL without deleting the existing volume:

```powershell
docker compose up -d
```

The local database uses `ledgerline_db` and the `ledgerline_app` role on port `5432`. The ignored root `.env` is imported by the backend for local development. Never commit `.env`.

## Backend

Run the backend from `backend/ledgerline`:

```powershell
.\mvnw.cmd spring-boot:run
```

Phase 1 endpoints:

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /api/v1/auth/csrf`
- `GET /api/v1/auth/me`
- `POST /api/v1/auth/logout`
- `GET /actuator/health`

Registration always creates a `CUSTOMER`. Elevated roles cannot be requested by clients. Session cookies are HTTP-only, CSRF remains enabled, and allowed frontend origins are configured with `CORS_ALLOWED_ORIGINS`. Local development uses `COOKIE_SAME_SITE=lax`; a cross-site production frontend should use HTTPS with `COOKIE_SECURE=true` and `COOKIE_SAME_SITE=none`.

## Tests

The tests use the PostgreSQL Docker database rather than an in-memory replacement:

```powershell
.\mvnw.cmd clean verify
```

The Phase 1 integration tests cover registration, BCrypt hashing, duplicate email handling, login, CSRF, disabled sessions, role restrictions, Flyway/JPA startup, and database-enforced ledger invariants.

## AI Usage

See [PROMPTS.md](PROMPTS.md) for dated, genuine development interactions and verification notes.

## Not Finished

- Deposits, withdrawals, and transfers
- Transaction service idempotency handling
- Account and transaction REST resources
- Full administrative and auditor read-only workflows
- React/Vite frontend
- Deployment configuration and live URLs
