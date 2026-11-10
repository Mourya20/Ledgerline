-- ==============================================================================
-- Migration V13: Concurrency Safety for Account Requests and Account Lifecycle Guards
-- Enforces:
-- 1. At most one PENDING account opening request per customer via partial unique index.
-- 2. Concurrency-safe review_account_request procedure with pessimistic row locking.
-- 3. Database-level trigger preventing positive-balance account closure or reopening of CLOSED accounts.
-- ==============================================================================

-- 1. Resolve any historical duplicate pending requests safely by marking newer duplicates as REJECTED
WITH ranked_duplicates AS (
    SELECT id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at ASC) as rn
    FROM account_requests
    WHERE status = 'PENDING'
)
UPDATE account_requests
SET status = 'REJECTED',
    admin_notes = 'Duplicate pending request automatically resolved',
    updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT id FROM ranked_duplicates WHERE rn > 1);

-- 2. Create partial unique index to enforce at most one PENDING request per customer
CREATE UNIQUE INDEX IF NOT EXISTS idx_unique_pending_account_request_per_user
ON account_requests (user_id)
WHERE status = 'PENDING';

-- 3. Replace review_account_request procedure with row locking and status validation
CREATE OR REPLACE PROCEDURE review_account_request(
    p_request_id UUID,
    p_status VARCHAR,
    p_admin_notes VARCHAR,
    p_admin_email VARCHAR,
    p_account_id UUID
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_current_status VARCHAR;
BEGIN
    IF p_status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'Account request status can only be updated to APPROVED or REJECTED' USING ERRCODE = '42501';
    END IF;

    -- Row lock the request and verify it is still PENDING
    SELECT status INTO v_current_status
    FROM account_requests
    WHERE id = p_request_id
    FOR UPDATE;

    IF v_current_status IS NULL THEN
        RAISE EXCEPTION 'Account request not found: %', p_request_id USING ERRCODE = 'P0002';
    END IF;

    IF v_current_status <> 'PENDING' THEN
        RAISE EXCEPTION 'Account request % has already been reviewed with status: %', p_request_id, v_current_status USING ERRCODE = '23505';
    END IF;

    UPDATE account_requests
    SET status = p_status,
        admin_notes = p_admin_notes,
        reviewed_by = p_admin_email,
        account_id = p_account_id,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_request_id;
END;
$$;

GRANT EXECUTE ON PROCEDURE review_account_request(UUID, VARCHAR, VARCHAR, VARCHAR, UUID) TO ledgerline_runtime;

-- 4. Trigger function to enforce account lifecycle invariants at the database level:
--    - A CLOSED account cannot be reopened or have its status modified.
--    - An account with a non-zero balance cannot be closed.
CREATE OR REPLACE FUNCTION check_account_status_transition()
RETURNS TRIGGER AS $$
BEGIN
    IF OLD.status = 'CLOSED' AND NEW.status <> 'CLOSED' THEN
        RAISE EXCEPTION 'A CLOSED account cannot be reopened or have its status modified' USING ERRCODE = '23514';
    END IF;
    IF NEW.status = 'CLOSED' AND NEW.balance <> 0 THEN
        RAISE EXCEPTION 'Account balance must be 0.00 to close an account' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_check_account_status_transition ON accounts;
CREATE TRIGGER trg_check_account_status_transition
BEFORE UPDATE OF status ON accounts
FOR EACH ROW
EXECUTE FUNCTION check_account_status_transition();
