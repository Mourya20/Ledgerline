-- Migration V8: Harden Runtime Database Privileges and Validate System Counterparts
-- Revokes excessive runtime grants and enforces strict least-privilege security.

-- 1. Revoke TRUNCATE across all tables from ledgerline_runtime
REVOKE TRUNCATE ON ALL TABLES IN SCHEMA public FROM ledgerline_runtime;

-- 2. Revoke DELETE on all tables from ledgerline_runtime
REVOKE DELETE ON users FROM ledgerline_runtime;
REVOKE DELETE ON accounts FROM ledgerline_runtime;
REVOKE DELETE ON ledger_transactions FROM ledgerline_runtime;
REVOKE DELETE ON ledger_entries FROM ledgerline_runtime;
REVOKE DELETE ON audit_logs FROM ledgerline_runtime;

-- 3. Restrict UPDATE on users:
-- Revoke full UPDATE. Grant column-level UPDATE only on status and updated_at.
-- Prevents runtime modification of role, password_hash, email, or id.
REVOKE UPDATE ON users FROM ledgerline_runtime;
GRANT UPDATE (status, updated_at) ON users TO ledgerline_runtime;

-- 4. Restrict UPDATE on accounts:
-- Revoke full UPDATE and revoke UPDATE on allow_negative.
-- Grant column-level UPDATE only on status and updated_at.
-- Prevents runtime modification of balance, allow_negative, account_type, normal_balance, currency, or owner_user_id.
REVOKE UPDATE ON accounts FROM ledgerline_runtime;
GRANT UPDATE (status, updated_at) ON accounts TO ledgerline_runtime;

-- 5. Restrict UPDATE on ledger_transactions:
-- Runtime only transitions pending transactions to completed/failed with completed_at.
-- Prevents modification of transaction_type, currency, idempotency_key, request_fingerprint, or initiated_by_user_id.
REVOKE UPDATE ON ledger_transactions FROM ledgerline_runtime;
GRANT UPDATE (status, completed_at) ON ledger_transactions TO ledgerline_runtime;

-- 6. Reaffirm append-only tables:
-- Ensure ledger_entries and audit_logs cannot be updated or deleted by runtime.
REVOKE UPDATE, DELETE ON ledger_entries FROM ledgerline_runtime;
REVOKE UPDATE, DELETE ON audit_logs FROM ledgerline_runtime;

-- 7. Restrict schema permissions:
-- Runtime must only have USAGE on schema public, never CREATE.
REVOKE CREATE ON SCHEMA public FROM ledgerline_runtime;

-- 8. Validate Canonical Operational System Cash Vault for INR
-- Ensures SYS-INR-CASH-VAULT-001 has exact required accounting properties.
DO $$
DECLARE
    v_acc RECORD;
BEGIN
    SELECT * INTO v_acc FROM accounts WHERE account_number = 'SYS-INR-CASH-VAULT-001';
    IF FOUND THEN
        IF v_acc.account_type <> 'SYSTEM' OR
           v_acc.owner_user_id IS NOT NULL OR
           v_acc.currency <> 'INR' OR
           v_acc.normal_balance <> 'DEBIT' OR
           v_acc.allow_negative <> FALSE THEN
            RAISE EXCEPTION 'Canonical system cash vault SYS-INR-CASH-VAULT-001 has invalid accounting configuration';
        END IF;
    END IF;
END $$;
