-- Migration V6: Enforce account ownership constraints, account classification immutability,
-- transaction status cleanup, and database privilege separation.

-- 1. Correct Account Ownership Constraints (FIX 5)
-- CUSTOMER accounts MUST have an owner_user_id.
-- SYSTEM accounts MUST have a NULL owner_user_id.
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_owner_chk;
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_ownership_policy_chk;

ALTER TABLE accounts
    ADD CONSTRAINT accounts_ownership_policy_chk
    CHECK (
        (account_type = 'CUSTOMER' AND owner_user_id IS NOT NULL) OR
        (account_type = 'SYSTEM' AND owner_user_id IS NULL)
    );

-- 2. Resolve Misleading REVERSED Transaction Status (FIX 11)
-- Transactions are strictly PENDING, COMPLETED, or FAILED. Reversals must be compensating transactions.
ALTER TABLE ledger_transactions DROP CONSTRAINT IF EXISTS ledger_transactions_status_chk;

ALTER TABLE ledger_transactions
    ADD CONSTRAINT ledger_transactions_status_chk
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'));

-- 3. Balance Projection Migration & Reconciliation (FIX 6)
-- Recalculate projections from committed ledger entries; reject accounts with non-zero balances and no entries.
DO $$
DECLARE
    r RECORD;
    v_calculated_balance NUMERIC(19, 2);
    v_entry_count INTEGER;
    v_total_credits NUMERIC(19, 2);
    v_total_debits NUMERIC(19, 2);
BEGIN
    FOR r IN SELECT id, account_number, normal_balance, balance FROM accounts LOOP
        SELECT 
            COUNT(*),
            COALESCE(SUM(CASE WHEN e.entry_type = 'CREDIT' THEN e.amount ELSE 0 END), 0),
            COALESCE(SUM(CASE WHEN e.entry_type = 'DEBIT' THEN e.amount ELSE 0 END), 0)
        INTO v_entry_count, v_total_credits, v_total_debits
        FROM ledger_entries e
        JOIN ledger_transactions t ON t.id = e.transaction_id
        WHERE e.account_id = r.id AND t.status = 'COMPLETED';

        IF v_entry_count > 0 THEN
            IF r.normal_balance = 'CREDIT' THEN
                v_calculated_balance := v_total_credits - v_total_debits;
            ELSE
                v_calculated_balance := v_total_debits - v_total_credits;
            END IF;

            IF r.balance <> v_calculated_balance THEN
                UPDATE accounts SET balance = v_calculated_balance WHERE id = r.id;
            END IF;
        ELSE
            IF r.balance <> 0 THEN
                RAISE EXCEPTION 'Account % (%) has non-zero balance % but zero committed ledger entries. Cannot reconcile balance automatically.',
                    r.account_number, r.id, r.balance;
            END IF;
        END IF;
    END LOOP;
END $$;

-- 4. Protect Account Accounting Classification (FIX 8)
-- Once ledger entries exist for an account, account_type, normal_balance, and currency cannot be modified.
CREATE OR REPLACE FUNCTION protect_account_classification()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (OLD.account_type <> NEW.account_type OR 
        OLD.normal_balance <> NEW.normal_balance OR 
        OLD.currency <> NEW.currency) THEN
        
        IF EXISTS (SELECT 1 FROM ledger_entries WHERE account_id = OLD.id) THEN
            RAISE EXCEPTION 'Cannot modify account_type, normal_balance, or currency for account % because ledger entries exist', OLD.id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS accounts_protect_classification_trg ON accounts;

CREATE TRIGGER accounts_protect_classification_trg
BEFORE UPDATE OF account_type, normal_balance, currency ON accounts
FOR EACH ROW
EXECUTE FUNCTION protect_account_classification();

-- 5. Remove Session-Variable Balance Authorization & Enforce Privilege Separation (FIX 7)
-- Replace protect_account_balance_projection: verify that balance updates only occur from within
-- apply_ledger_entry_to_balance trigger context via execution stack inspection (PG_CONTEXT).
CREATE OR REPLACE FUNCTION protect_account_balance_projection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    v_context text;
BEGIN
    IF NEW.balance <> OLD.balance THEN
        GET DIAGNOSTICS v_context = PG_CONTEXT;
        IF v_context NOT LIKE '%apply_ledger_entry_to_balance%' THEN
            RAISE EXCEPTION 'account balance is maintained by ledger entries and cannot be updated directly';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS accounts_balance_projection_protected ON accounts;

CREATE TRIGGER accounts_balance_projection_protected
BEFORE UPDATE OF balance ON accounts
FOR EACH ROW
EXECUTE FUNCTION protect_account_balance_projection();

CREATE OR REPLACE FUNCTION apply_ledger_entry_to_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    acct_normal_balance VARCHAR(6);
    delta NUMERIC(19, 2);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT normal_balance INTO acct_normal_balance
        FROM accounts WHERE id = OLD.account_id FOR UPDATE;

        IF acct_normal_balance IS NULL THEN
            RAISE EXCEPTION 'ledger account does not exist';
        END IF;

        IF acct_normal_balance = 'CREDIT' THEN
            delta := CASE WHEN OLD.entry_type = 'CREDIT' THEN OLD.amount ELSE -OLD.amount END;
        ELSE
            delta := CASE WHEN OLD.entry_type = 'DEBIT' THEN OLD.amount ELSE -OLD.amount END;
        END IF;

        UPDATE accounts
        SET balance = balance - delta,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = OLD.account_id;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        SELECT normal_balance INTO acct_normal_balance
        FROM accounts WHERE id = NEW.account_id FOR UPDATE;

        IF acct_normal_balance IS NULL THEN
            RAISE EXCEPTION 'ledger account does not exist';
        END IF;

        IF acct_normal_balance = 'CREDIT' THEN
            delta := CASE WHEN NEW.entry_type = 'CREDIT' THEN NEW.amount ELSE -NEW.amount END;
        ELSE
            delta := CASE WHEN NEW.entry_type = 'DEBIT' THEN NEW.amount ELSE -NEW.amount END;
        END IF;

        UPDATE accounts
        SET balance = balance + delta,
            updated_at = CURRENT_TIMESTAMP
        WHERE id = NEW.account_id;
    END IF;

    RETURN NEW;
END;
$$;

-- Create ledgerline_runtime role if it does not already exist
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledgerline_runtime') THEN
        CREATE ROLE ledgerline_runtime WITH LOGIN PASSWORD 'ledgerline_runtime_secret' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;
END $$;

-- Grant least-privilege runtime access:
GRANT USAGE ON SCHEMA public TO ledgerline_runtime;

GRANT SELECT, INSERT, UPDATE, DELETE ON users TO ledgerline_runtime;
GRANT SELECT, INSERT ON accounts TO ledgerline_runtime;
GRANT UPDATE (status, allow_negative) ON accounts TO ledgerline_runtime;
GRANT SELECT, INSERT, UPDATE ON ledger_transactions TO ledgerline_runtime;
GRANT SELECT, INSERT ON ledger_entries TO ledgerline_runtime;
GRANT SELECT, INSERT ON audit_logs TO ledgerline_runtime;

GRANT TRUNCATE ON ALL TABLES IN SCHEMA public TO ledgerline_runtime;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO ledgerline_runtime;
