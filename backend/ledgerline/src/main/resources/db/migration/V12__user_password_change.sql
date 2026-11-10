-- Migration V12: Self-Service User Password Change via Security Definer Procedure
-- Preserves information_schema.role_column_grants invariants and raw UPDATE restrictions on ledgerline_runtime.

CREATE OR REPLACE PROCEDURE change_user_password(
    p_user_id UUID,
    p_new_password_hash VARCHAR
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_exists BOOLEAN;
BEGIN
    SELECT EXISTS(SELECT 1 FROM users WHERE id = p_user_id) INTO v_exists;
    IF NOT v_exists THEN
        RAISE EXCEPTION 'User not found: %', p_user_id USING ERRCODE = 'P0002';
    END IF;

    UPDATE users
    SET password_hash = p_new_password_hash,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;
END;
$$;

GRANT EXECUTE ON PROCEDURE change_user_password(UUID, VARCHAR) TO ledgerline_runtime;
