CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_role_chk CHECK (role IN ('CUSTOMER', 'ADMIN', 'AUDITOR')),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX users_email_lower_uidx ON users (LOWER(email));

CREATE TABLE accounts (
    id UUID PRIMARY KEY,
    account_number VARCHAR(34) NOT NULL UNIQUE,
    owner_user_id UUID REFERENCES users(id),
    account_type VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    balance NUMERIC(19, 2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT accounts_type_chk CHECK (account_type IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT accounts_status_chk CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT accounts_owner_chk CHECK (
        (account_type = 'CUSTOMER' AND owner_user_id IS NOT NULL)
        OR (account_type = 'SYSTEM')
    ),
    CONSTRAINT accounts_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT accounts_balance_nonnegative_chk CHECK (balance >= 0)
);

CREATE INDEX accounts_owner_idx ON accounts (owner_user_id);

CREATE TABLE ledger_transactions (
    id UUID PRIMARY KEY,
    transaction_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    initiated_by_user_id UUID REFERENCES users(id),
    currency CHAR(3) NOT NULL,
    idempotency_key VARCHAR(255),
    request_fingerprint CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    CONSTRAINT ledger_transactions_status_chk CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED')),
    CONSTRAINT ledger_transactions_currency_chk CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ledger_transactions_idempotency_chk CHECK (
        (initiated_by_user_id IS NULL AND idempotency_key IS NULL AND request_fingerprint IS NULL)
        OR (initiated_by_user_id IS NOT NULL AND idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL)
    ),
    CONSTRAINT ledger_transactions_fingerprint_chk CHECK (
        request_fingerprint IS NULL OR request_fingerprint ~ '^[0-9a-fA-F]{64}$'
    ),
    CONSTRAINT ledger_transactions_completed_at_chk CHECK (
        status <> 'COMPLETED' OR completed_at IS NOT NULL
    )
);

CREATE UNIQUE INDEX ledger_transactions_idempotency_uidx
    ON ledger_transactions (initiated_by_user_id, idempotency_key)
    WHERE initiated_by_user_id IS NOT NULL AND idempotency_key IS NOT NULL;

CREATE INDEX ledger_transactions_initiator_idx ON ledger_transactions (initiated_by_user_id);

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL REFERENCES ledger_transactions(id),
    account_id UUID NOT NULL REFERENCES accounts(id),
    entry_type VARCHAR(6) NOT NULL,
    amount NUMERIC(19, 2) NOT NULL,
    currency CHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ledger_entries_type_chk CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entries_amount_chk CHECK (amount > 0),
    CONSTRAINT ledger_entries_currency_chk CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX ledger_entries_transaction_idx ON ledger_entries (transaction_id);
CREATE INDEX ledger_entries_account_idx ON ledger_entries (account_id);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY,
    actor_user_id UUID REFERENCES users(id),
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100),
    resource_id UUID,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_user_id);
CREATE INDEX audit_logs_resource_idx ON audit_logs (resource_type, resource_id);

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_set_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER accounts_set_updated_at
BEFORE UPDATE ON accounts
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE OR REPLACE FUNCTION prevent_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit logs are append-only';
END;
$$;

CREATE TRIGGER audit_logs_append_only
BEFORE UPDATE OR DELETE ON audit_logs
FOR EACH ROW EXECUTE FUNCTION prevent_audit_mutation();

CREATE OR REPLACE FUNCTION prevent_completed_transaction_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'COMPLETED' THEN
        RAISE EXCEPTION 'completed ledger transactions are immutable';
    END IF;
    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER ledger_transactions_completed_immutable
BEFORE UPDATE OR DELETE ON ledger_transactions
FOR EACH ROW EXECUTE FUNCTION prevent_completed_transaction_mutation();

CREATE OR REPLACE FUNCTION validate_ledger_entry()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    transaction_status VARCHAR(20);
    transaction_currency CHAR(3);
    account_currency CHAR(3);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        SELECT status INTO transaction_status
        FROM ledger_transactions
        WHERE id = OLD.transaction_id;

        IF transaction_status = 'COMPLETED' THEN
            RAISE EXCEPTION 'entries for completed ledger transactions are immutable';
        END IF;
    END IF;

    IF TG_OP <> 'DELETE' THEN
        SELECT status, currency INTO transaction_status, transaction_currency
        FROM ledger_transactions
        WHERE id = NEW.transaction_id;

        IF transaction_status IS NULL THEN
            RAISE EXCEPTION 'ledger transaction does not exist';
        END IF;
        IF transaction_status = 'COMPLETED' THEN
            RAISE EXCEPTION 'cannot add entries to a completed ledger transaction';
        END IF;

        SELECT currency INTO account_currency
        FROM accounts
        WHERE id = NEW.account_id;

        IF account_currency IS NULL THEN
            RAISE EXCEPTION 'ledger account does not exist';
        END IF;
        IF NEW.currency <> transaction_currency OR NEW.currency <> account_currency THEN
            RAISE EXCEPTION 'ledger entry currency must match its transaction and account';
        END IF;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER ledger_entries_validate
BEFORE INSERT OR UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION validate_ledger_entry();

CREATE OR REPLACE FUNCTION apply_ledger_entry_to_balance()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    old_delta NUMERIC(19, 2);
    new_delta NUMERIC(19, 2);
BEGIN
    IF TG_OP IN ('UPDATE', 'DELETE') THEN
        old_delta := CASE WHEN OLD.entry_type = 'CREDIT' THEN OLD.amount ELSE -OLD.amount END;
        UPDATE accounts SET balance = balance - old_delta WHERE id = OLD.account_id;
    END IF;

    IF TG_OP IN ('INSERT', 'UPDATE') THEN
        new_delta := CASE WHEN NEW.entry_type = 'CREDIT' THEN NEW.amount ELSE -NEW.amount END;
        UPDATE accounts SET balance = balance + new_delta WHERE id = NEW.account_id;
    END IF;

    RETURN COALESCE(NEW, OLD);
END;
$$;

CREATE TRIGGER ledger_entries_apply_balance
AFTER INSERT OR UPDATE OR DELETE ON ledger_entries
FOR EACH ROW EXECUTE FUNCTION apply_ledger_entry_to_balance();

CREATE OR REPLACE FUNCTION validate_completed_transaction()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    entry_count INTEGER;
    debit_total NUMERIC(19, 2);
    credit_total NUMERIC(19, 2);
BEGIN
    IF NEW.status = 'COMPLETED' THEN
        SELECT COUNT(*),
               COALESCE(SUM(amount) FILTER (WHERE entry_type = 'DEBIT'), 0),
               COALESCE(SUM(amount) FILTER (WHERE entry_type = 'CREDIT'), 0)
        INTO entry_count, debit_total, credit_total
        FROM ledger_entries
        WHERE transaction_id = NEW.id;

        IF entry_count = 0 THEN
            RAISE EXCEPTION 'completed ledger transactions must contain entries';
        END IF;
        IF debit_total <> credit_total THEN
            RAISE EXCEPTION 'completed ledger transactions must be balanced';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE CONSTRAINT TRIGGER ledger_transactions_balanced_at_commit
AFTER INSERT OR UPDATE OF status ON ledger_transactions
DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION validate_completed_transaction();
