-- Migration V14: Extended Banking Controls, Lifecycle, Verification and Recovery
-- Supports:
-- 1. Transaction reversal with compensating entries and controlled transition to REVERSED
-- 2. Customer email verification tokens and email_verified status on users
-- 3. Independent password reset tokens
-- 4. Configurable transaction limits (per-transaction and daily)
-- 5. Maker-checker approval requests for large transfers and reversals
-- 6. Runtime role permissions

-- 1. Update prevent_completed_transaction_mutation() to allow transition to REVERSED
CREATE OR REPLACE FUNCTION prevent_completed_transaction_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'COMPLETED' THEN
        IF TG_OP = 'UPDATE' AND NEW.status = 'REVERSED' AND NEW.related_transaction_id IS NOT NULL THEN
            -- Verify immutable financial fields are not altered during status transition
            IF NEW.id <> OLD.id OR NEW.transaction_type <> OLD.transaction_type OR NEW.currency <> OLD.currency THEN
                RAISE EXCEPTION 'cannot modify immutable financial fields of a completed transaction during reversal';
            END IF;
            RETURN NEW;
        ELSE
            RAISE EXCEPTION 'completed ledger transactions are immutable';
        END IF;
    END IF;
    IF OLD.status = 'REVERSED' THEN
        RAISE EXCEPTION 'reversed ledger transactions are immutable';
    END IF;
    RETURN NEW;
END;
$$;

-- Allow REVERSED in ledger_transactions status check constraint
ALTER TABLE ledger_transactions DROP CONSTRAINT IF EXISTS ledger_transactions_status_chk;
ALTER TABLE ledger_transactions ADD CONSTRAINT ledger_transactions_status_chk
    CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED', 'REVERSED'));

-- 2. Update check_transaction_integrity_by_id() to recognize REVERSED status
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

    -- A transaction containing ledger entries must finish as COMPLETED or REVERSED at commit
    IF entry_count > 0 AND tx_record.status NOT IN ('COMPLETED', 'REVERSED') THEN
        RAISE EXCEPTION 'transactions containing ledger entries must finish as COMPLETED or REVERSED at commit (current status: %)', tx_record.status;
    END IF;

    -- A COMPLETED or REVERSED transaction must satisfy all financial invariants
    IF tx_record.status IN ('COMPLETED', 'REVERSED') THEN
        IF entry_count = 0 THEN
            RAISE EXCEPTION 'completed or reversed ledger transactions must contain entries';
        END IF;

        IF debit_count < 1 THEN
            RAISE EXCEPTION 'completed or reversed ledger transactions must contain at least one debit entry';
        END IF;

        IF credit_count < 1 THEN
            RAISE EXCEPTION 'completed or reversed ledger transactions must contain at least one credit entry';
        END IF;

        IF debit_total <> credit_total THEN
            RAISE EXCEPTION 'completed or reversed ledger transactions must be balanced: debits (%) != credits (%)', debit_total, credit_total;
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

-- 3. Add reversal tracking fields to ledger_transactions
ALTER TABLE ledger_transactions
    ADD COLUMN IF NOT EXISTS related_transaction_id UUID REFERENCES ledger_transactions(id),
    ADD COLUMN IF NOT EXISTS reversal_reason VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_ledger_transactions_related ON ledger_transactions(related_transaction_id);

-- 4. Add email_verified to users (defaults to TRUE so all existing demo users are verified)
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE;

-- 5. Email verification tokens table
CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user ON email_verification_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_hash ON email_verification_tokens(token_hash);

-- 6. Password reset tokens table
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_user ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_tokens_hash ON password_reset_tokens(token_hash);

-- 7. Transaction limits configuration table
CREATE TABLE IF NOT EXISTS transaction_limits (
    id VARCHAR(50) PRIMARY KEY,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    max_per_transaction NUMERIC(19, 4) NOT NULL,
    max_daily_total NUMERIC(19, 4) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by_user_id UUID REFERENCES users(id)
);
INSERT INTO transaction_limits (id, currency, max_per_transaction, max_daily_total)
VALUES ('DEFAULT', 'INR', 500000.0000, 2000000.0000)
ON CONFLICT (id) DO NOTHING;

-- 8. Maker-checker approval requests table
CREATE TABLE IF NOT EXISTS maker_checker_requests (
    id UUID PRIMARY KEY,
    request_type VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    initiated_by_user_id UUID NOT NULL REFERENCES users(id),
    reviewed_by_user_id UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    rejection_reason VARCHAR(255),
    amount NUMERIC(19, 4),
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    source_account_id UUID REFERENCES accounts(id),
    destination_account_id UUID REFERENCES accounts(id),
    original_transaction_id UUID REFERENCES ledger_transactions(id),
    resulting_transaction_id UUID REFERENCES ledger_transactions(id),
    description VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_maker_checker_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'))
);
CREATE INDEX IF NOT EXISTS idx_maker_checker_status ON maker_checker_requests(status);
CREATE INDEX IF NOT EXISTS idx_maker_checker_type ON maker_checker_requests(request_type);

-- 9. Runtime permissions
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'ledgerline_runtime') THEN
        GRANT SELECT, INSERT ON email_verification_tokens TO ledgerline_runtime;
        GRANT SELECT, INSERT ON password_reset_tokens TO ledgerline_runtime;
        GRANT SELECT, INSERT ON transaction_limits TO ledgerline_runtime;
        GRANT SELECT, INSERT ON maker_checker_requests TO ledgerline_runtime;
    END IF;
END $$;
