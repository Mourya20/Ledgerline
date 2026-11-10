-- Migration V10: Allow Admin Password Assignment and Dynamic Auditor/Customer Provisioning via Security Definer Procedures
-- Preserves information_schema.role_column_grants invariants and raw UPDATE/INSERT restrictions on ledgerline_runtime.

-- 1. Security definer procedure for administrative password assignment
-- Allows admin workflows to safely update password_hash for CUSTOMER or AUDITOR users.
-- Prevents runtime role from directly updating password_hash via raw SQL.
CREATE OR REPLACE PROCEDURE admin_assign_user_password(
    p_user_id UUID,
    p_new_password_hash VARCHAR
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_role VARCHAR;
BEGIN
    SELECT role INTO v_role FROM users WHERE id = p_user_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'User not found: %', p_user_id USING ERRCODE = 'P0002';
    END IF;

    IF v_role NOT IN ('CUSTOMER', 'AUDITOR') THEN
        RAISE EXCEPTION 'Passwords can only be assigned to CUSTOMER or AUDITOR users' USING ERRCODE = '42501';
    END IF;

    UPDATE users
    SET password_hash = p_new_password_hash,
        updated_at = CURRENT_TIMESTAMP
    WHERE id = p_user_id;
END;
$$;

GRANT EXECUTE ON PROCEDURE admin_assign_user_password(UUID, VARCHAR) TO ledgerline_runtime;

-- 2. Security definer procedure for administrative dynamic user onboarding
-- Allows admin workflows to provision new users with CUSTOMER or AUDITOR roles and assigned IDs.
-- Prevents runtime role from escalating to ADMIN role.
CREATE OR REPLACE PROCEDURE admin_onboard_user(
    p_user_id UUID,
    p_email VARCHAR,
    p_password_hash VARCHAR,
    p_role VARCHAR
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
BEGIN
    IF p_role NOT IN ('CUSTOMER', 'AUDITOR') THEN
        RAISE EXCEPTION 'Onboarded users can only have CUSTOMER or AUDITOR role' USING ERRCODE = '42501';
    END IF;

    INSERT INTO users (id, email, password_hash, role, status, created_at, updated_at)
    VALUES (
        p_user_id,
        LOWER(TRIM(p_email)),
        p_password_hash,
        p_role,
        'ACTIVE',
        CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP
    );
END;
$$;

GRANT EXECUTE ON PROCEDURE admin_onboard_user(UUID, VARCHAR, VARCHAR, VARCHAR) TO ledgerline_runtime;
