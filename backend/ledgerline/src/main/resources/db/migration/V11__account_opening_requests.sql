-- Migration V11: Account Opening Requests & Approval Workflow
-- Allows customers to submit account opening requests that require administrator acceptance before account creation.

CREATE TABLE account_requests (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    currency VARCHAR(3) NOT NULL DEFAULT 'INR',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    admin_notes VARCHAR(255),
    reviewed_by VARCHAR(255),
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT account_requests_status_chk CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT account_requests_currency_chk CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX account_requests_user_idx ON account_requests(user_id);
CREATE INDEX account_requests_status_idx ON account_requests(status);

-- Grant SELECT and INSERT to ledgerline_runtime (preserves table privilege invariants of SELECT, INSERT only)
GRANT SELECT, INSERT ON account_requests TO ledgerline_runtime;

-- Security definer procedure for administrative review of account opening requests
-- Preserves role_column_grants invariants (no direct column UPDATE granted to ledgerline_runtime)
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
BEGIN
    IF p_status NOT IN ('APPROVED', 'REJECTED') THEN
        RAISE EXCEPTION 'Account request status can only be updated to APPROVED or REJECTED' USING ERRCODE = '42501';
    END IF;

    UPDATE account_requests
    SET status = p_status,
        admin_notes = p_admin_notes,
        reviewed_by = p_admin_email,
        account_id = p_account_id,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_request_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Account request not found: %', p_request_id USING ERRCODE = 'P0002';
    END IF;
END;
$$;

GRANT EXECUTE ON PROCEDURE review_account_request(UUID, VARCHAR, VARCHAR, VARCHAR, UUID) TO ledgerline_runtime;
