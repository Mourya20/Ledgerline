-- Migration V5: Remediate accounting model and ledger integrity

-- 1. Add normal_balance and allow_negative to accounts
ALTER TABLE accounts ADD COLUMN normal_balance VARCHAR(6) NOT NULL DEFAULT 'CREDIT';
ALTER TABLE accounts ADD COLUMN allow_negative BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE accounts ADD CONSTRAINT accounts_normal_balance_chk CHECK (normal_balance IN ('DEBIT', 'CREDIT'));

-- Existing SYSTEM accounts should default to DEBIT normal balance
UPDATE accounts SET normal_balance = 'DEBIT' WHERE account_type = 'SYSTEM';

-- Replace universal balance non-negative constraint with explicit policy
ALTER TABLE accounts DROP CONSTRAINT IF EXISTS accounts_balance_nonnegative_chk;

ALTER TABLE accounts ADD CONSTRAINT accounts_balance_policy_chk
    CHECK (allow_negative = TRUE OR balance >= 0);

ALTER TABLE accounts ADD CONSTRAINT accounts_customer_policy_chk
    CHECK (account_type <> 'CUSTOMER' OR (normal_balance = 'CREDIT' AND allow_negative = FALSE AND balance >= 0));

-- 2. Update apply_ledger_entry_to_balance function to respect normal_balance
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
    PERFORM set_config('ledgerline.balance_update', 'on', true);

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

    PERFORM set_config('ledgerline.balance_update', 'off', true);
    RETURN COALESCE(NEW, OLD);
END;
$$;

-- 3. Update validate_ledger_entry to enforce append-only entries and check account status
CREATE OR REPLACE FUNCTION validate_ledger_entry()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    transaction_status VARCHAR(20);
    transaction_currency VARCHAR(3);
    account_currency VARCHAR(3);
    account_status VARCHAR(20);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'ledger entries are immutable and cannot be updated';
    END IF;

    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ledger entries cannot be deleted';
    END IF;

    IF TG_OP = 'INSERT' THEN
        SELECT status, currency INTO transaction_status, transaction_currency
        FROM ledger_transactions
        WHERE id = NEW.transaction_id;

        IF transaction_status IS NULL THEN
            RAISE EXCEPTION 'ledger transaction does not exist';
        END IF;

        IF transaction_status = 'COMPLETED' THEN
            RAISE EXCEPTION 'cannot add entries to a completed ledger transaction';
        END IF;

        SELECT currency, status INTO account_currency, account_status
        FROM accounts
        WHERE id = NEW.account_id;

        IF account_currency IS NULL THEN
            RAISE EXCEPTION 'ledger account does not exist';
        END IF;

        IF account_status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'cannot post entries to an inactive account (status: %)', account_status;
        END IF;

        IF NEW.currency <> transaction_currency OR NEW.currency <> account_currency THEN
            RAISE EXCEPTION 'ledger entry currency must match its transaction and account';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

-- 4. Deferred transaction integrity validation at commit
DROP TRIGGER IF EXISTS ledger_transactions_balanced_at_commit ON ledger_transactions;
DROP FUNCTION IF EXISTS validate_completed_transaction;

CREATE OR REPLACE FUNCTION check_transaction_integrity_by_id(target_tx_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    tx_record RECORD;
    entry_count INTEGER;
    debit_count INTEGER;
    credit_count INTEGER;
    debit_total NUMERIC(19, 2);
    credit_total NUMERIC(19, 2);
    mismatch_count INTEGER;
BEGIN
    SELECT id, status, currency
    INTO tx_record
    FROM ledger_transactions
    WHERE id = target_tx_id;

    IF tx_record.id IS NULL THEN
        RETURN;
    END IF;

    SELECT COUNT(*),
           COUNT(*) FILTER (WHERE entry_type = 'DEBIT'),
           COUNT(*) FILTER (WHERE entry_type = 'CREDIT'),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
           COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
    INTO entry_count, debit_count, credit_count, debit_total, credit_total
    FROM ledger_entries
    WHERE transaction_id = target_tx_id;

    -- A transaction containing ledger entries must finish as COMPLETED at commit
    IF entry_count > 0 AND tx_record.status <> 'COMPLETED' THEN
        RAISE EXCEPTION 'transactions containing ledger entries must finish as COMPLETED at commit (current status: %)', tx_record.status;
    END IF;

    -- A COMPLETED transaction must satisfy all financial invariants
    IF tx_record.status = 'COMPLETED' THEN
        IF entry_count = 0 THEN
            RAISE EXCEPTION 'completed ledger transactions must contain entries';
        END IF;

        IF debit_count < 1 THEN
            RAISE EXCEPTION 'completed ledger transactions must contain at least one debit entry';
        END IF;

        IF credit_count < 1 THEN
            RAISE EXCEPTION 'completed ledger transactions must contain at least one credit entry';
        END IF;

        IF debit_total <> credit_total THEN
            RAISE EXCEPTION 'completed ledger transactions must be balanced: debits (%) != credits (%)', debit_total, credit_total;
        END IF;

        SELECT COUNT(*)
        INTO mismatch_count
        FROM ledger_entries le
        JOIN accounts a ON a.id = le.account_id
        WHERE le.transaction_id = target_tx_id
          AND (le.currency <> tx_record.currency OR a.currency <> tx_record.currency);

        IF mismatch_count > 0 THEN
            RAISE EXCEPTION 'all entries and accounts must match the transaction currency (%)', tx_record.currency;
        END IF;
    END IF;
END;
$$;

CREATE OR REPLACE FUNCTION defer_check_transaction_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    PERFORM check_transaction_integrity_by_id(NEW.id);
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION defer_check_entry_transaction_integrity()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM check_transaction_integrity_by_id(OLD.transaction_id);
        RETURN OLD;
    ELSE
        PERFORM check_transaction_integrity_by_id(NEW.transaction_id);
        RETURN NEW;
    END IF;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transactions_integrity_at_commit
AFTER INSERT OR UPDATE ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION defer_check_transaction_integrity();

CREATE CONSTRAINT TRIGGER ledger_entries_transaction_integrity_at_commit
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION defer_check_entry_transaction_integrity();
