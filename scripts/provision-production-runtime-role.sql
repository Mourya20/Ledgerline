-- ==============================================================================
-- Vaultora — Production Database Role Provisioning Script
-- Target Environment: Render Managed PostgreSQL (or any cloud PostgreSQL provider)
-- Execution Identity: Database Administrator / Primary User (e.g. ledgerline_app)
-- ==============================================================================
--
-- This script prepares the production managed PostgreSQL database by provisioning
-- the restricted application runtime role (ledgerline_runtime) with least-privilege
-- grants matching V1–V13 security invariants.
--
-- INSTRUCTIONS FOR OPERATOR:
-- 1. Connect to your managed PostgreSQL database as the primary user (ledgerline_app):
--      psql "<MANAGED_POSTGRES_PRIMARY_CONNECTION_STRING>"
-- 2. Run this script, replacing ':RUNTIME_PASSWORD' with your generated secret:
--      \set RUNTIME_PASSWORD 'your_secure_runtime_password_here'
--      \i scripts/provision-production-runtime-role.sql
-- 3. In the Render Dashboard (or cloud console), configure the web service environment:
--      APP_DB_USERNAME = ledgerline_runtime
--      APP_DB_PASSWORD = your_secure_runtime_password_here
--    Flyway migrations will run under the primary user (FLYWAY_DB_USERNAME).
-- ==============================================================================

DO $$
BEGIN
    -- 1. Create runtime role if not exists
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ledgerline_runtime') THEN
        CREATE ROLE ledgerline_runtime WITH LOGIN;
    END IF;
END $$;

-- 2. Set password for runtime role (operator can customize or alter separately)
-- ALTER ROLE ledgerline_runtime WITH PASSWORD 'your_secure_runtime_password_here';

-- 3. Grant schema usage
GRANT USAGE ON SCHEMA public TO ledgerline_runtime;

-- 4. Table grants: Least-privilege access
-- Runtime role can SELECT and INSERT across primary business tables
GRANT SELECT, INSERT ON accounts TO ledgerline_runtime;
GRANT SELECT, INSERT ON ledger_transactions TO ledgerline_runtime;
GRANT SELECT, INSERT ON ledger_entries TO ledgerline_runtime;
GRANT SELECT, INSERT ON audit_logs TO ledgerline_runtime;
GRANT SELECT, INSERT ON account_requests TO ledgerline_runtime;
GRANT SELECT, INSERT ON users TO ledgerline_runtime;
GRANT SELECT, INSERT ON email_verification_tokens TO ledgerline_runtime;
GRANT SELECT, INSERT ON password_reset_tokens TO ledgerline_runtime;
GRANT SELECT, INSERT ON transaction_limits TO ledgerline_runtime;
GRANT SELECT, INSERT ON maker_checker_requests TO ledgerline_runtime;

-- Column-level UPDATE grants (strict least-privilege)
-- accounts: status and updated_at only (balances modified strictly via ledger entries)
GRANT UPDATE (status, updated_at) ON accounts TO ledgerline_runtime;
GRANT UPDATE (status, completed_at) ON ledger_transactions TO ledgerline_runtime;
GRANT UPDATE (status, updated_at) ON users TO ledgerline_runtime;

-- Strict denials: No DELETE on core tables, no direct UPDATE on users or financial ledgers
REVOKE DELETE ON accounts FROM ledgerline_runtime;
REVOKE DELETE ON ledger_transactions FROM ledgerline_runtime;
REVOKE DELETE, UPDATE ON ledger_entries FROM ledgerline_runtime;
REVOKE DELETE, UPDATE ON audit_logs FROM ledgerline_runtime;
REVOKE DELETE, UPDATE ON account_requests FROM ledgerline_runtime;
REVOKE DELETE ON users FROM ledgerline_runtime;

-- 5. Sequence grants for identity generation
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerline_runtime;

-- 6. Execute grants for privileged SECURITY DEFINER procedures
GRANT EXECUTE ON PROCEDURE admin_onboard_user(UUID, VARCHAR, VARCHAR, VARCHAR) TO ledgerline_runtime;
GRANT EXECUTE ON PROCEDURE admin_assign_user_password(UUID, VARCHAR) TO ledgerline_runtime;
GRANT EXECUTE ON PROCEDURE review_account_request(UUID, VARCHAR, VARCHAR, VARCHAR, UUID) TO ledgerline_runtime;
GRANT EXECUTE ON PROCEDURE change_user_password(UUID, VARCHAR) TO ledgerline_runtime;

-- Ensure future sequence usage
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO ledgerline_runtime;
