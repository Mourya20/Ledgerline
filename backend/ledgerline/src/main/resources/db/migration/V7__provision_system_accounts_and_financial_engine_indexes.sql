-- Migration V7: Provision System Counterpart Accounts and Financial Engine Indexes

-- 1. Seed default operational SYSTEM cash asset account for INR
-- Uses ON CONFLICT (account_number) because account_number has a UNIQUE constraint
INSERT INTO accounts (
    id,
    account_number,
    owner_user_id,
    account_type,
    currency,
    status,
    balance,
    normal_balance,
    allow_negative,
    created_at,
    updated_at
)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'SYS-INR-CASH-VAULT-001',
    NULL,
    'SYSTEM',
    'INR',
    'ACTIVE',
    0.00,
    'DEBIT',
    FALSE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (account_number) DO NOTHING;

-- 2. Performance indexes for transaction history and account ledger queries
CREATE INDEX IF NOT EXISTS ledger_transactions_created_at_idx 
    ON ledger_transactions (created_at DESC);

CREATE INDEX IF NOT EXISTS ledger_entries_account_created_idx 
    ON ledger_entries (account_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ledger_entries_account_type_idx 
    ON ledger_entries (account_id, entry_type);
