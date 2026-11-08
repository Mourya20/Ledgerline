CREATE OR REPLACE FUNCTION prevent_currency_mismatch_on_transaction_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ledger_entries
        WHERE transaction_id = NEW.id
          AND currency <> NEW.currency
    ) THEN
        RAISE EXCEPTION 'transaction currency must match all ledger entries';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER ledger_transactions_currency_consistency
BEFORE UPDATE OF currency ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_currency_mismatch_on_transaction_update();

CREATE OR REPLACE FUNCTION prevent_currency_mismatch_on_account_update()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM ledger_entries
        WHERE account_id = NEW.id
          AND currency <> NEW.currency
    ) THEN
        RAISE EXCEPTION 'account currency must match all ledger entries';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_currency_consistency
BEFORE UPDATE OF currency ON accounts
FOR EACH ROW EXECUTE FUNCTION prevent_currency_mismatch_on_account_update();

CREATE OR REPLACE FUNCTION protect_account_balance_projection()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.balance <> OLD.balance
       AND current_setting('ledgerline.balance_update', true) IS DISTINCT FROM 'on' THEN
        RAISE EXCEPTION 'account balance is maintained by ledger entries';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER accounts_balance_projection_protected
BEFORE UPDATE OF balance ON accounts
FOR EACH ROW EXECUTE FUNCTION protect_account_balance_projection();

CREATE OR REPLACE FUNCTION apply_ledger_entry_to_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_delta NUMERIC(19, 2);
    new_delta NUMERIC(19, 2);
BEGIN
    PERFORM set_config('ledgerline.balance_update', 'on', true);

    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_delta := CASE WHEN OLD.entry_type = 'CREDIT' THEN OLD.amount ELSE -OLD.amount END;
        UPDATE accounts SET balance = balance - old_delta WHERE id = OLD.account_id;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_delta := CASE WHEN NEW.entry_type = 'CREDIT' THEN NEW.amount ELSE -NEW.amount END;
        UPDATE accounts SET balance = balance + new_delta WHERE id = NEW.account_id;
    END IF;

    PERFORM set_config('ledgerline.balance_update', 'off', true);
    RETURN COALESCE(NEW, OLD);
END;
$$;
