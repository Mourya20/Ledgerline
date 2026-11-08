-- Migration V9: Prevent Privilege Escalation and Account Balance Fabrication
-- Enforces database-level boundaries on INSERT operations for runtime identity (ledgerline_runtime).

-- 1. Enforce NOINHERIT on runtime role
ALTER ROLE ledgerline_runtime NOINHERIT;

-- 2. Revoke CREATE on schema public from PUBLIC
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- 3. Function and trigger: Prevent privilege escalation on users INSERT
CREATE OR REPLACE FUNCTION protect_user_role_assignment()
RETURNS TRIGGER AS $$
BEGIN
    IF (CURRENT_USER = 'ledgerline_runtime' OR SESSION_USER = 'ledgerline_runtime') THEN
        IF NEW.role IS NULL THEN
            NEW.role := 'CUSTOMER';
        ELSIF NEW.role <> 'CUSTOMER' THEN
            RAISE EXCEPTION 'Runtime identity is strictly restricted to CUSTOMER role creation'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_user_role_assignment ON users;
CREATE TRIGGER trg_protect_user_role_assignment
    BEFORE INSERT ON users
    FOR EACH ROW
    EXECUTE FUNCTION protect_user_role_assignment();

-- 4. Function and trigger: Prevent fabricated opening balance and unauthorized SYSTEM accounts on accounts INSERT
CREATE OR REPLACE FUNCTION protect_account_insertion()
RETURNS TRIGGER AS $$
BEGIN
    IF (CURRENT_USER = 'ledgerline_runtime' OR SESSION_USER = 'ledgerline_runtime') THEN
        IF NEW.account_type IS NULL OR NEW.account_type <> 'CUSTOMER' THEN
            RAISE EXCEPTION 'Runtime identity cannot create non-CUSTOMER accounts'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.owner_user_id IS NULL THEN
            RAISE EXCEPTION 'Customer accounts must have an owner_user_id'
                USING ERRCODE = '23502';
        END IF;

        IF NEW.balance IS NOT NULL AND NEW.balance <> 0.00 THEN
            RAISE EXCEPTION 'Customer accounts must be opened with zero balance'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.normal_balance IS NULL OR NEW.normal_balance <> 'CREDIT' THEN
            RAISE EXCEPTION 'Customer accounts must have CREDIT normal balance'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.allow_negative IS TRUE THEN
            RAISE EXCEPTION 'Customer accounts cannot allow negative balance'
                USING ERRCODE = '42501';
        END IF;

        IF NEW.currency IS NULL OR NEW.currency <> 'INR' THEN
            RAISE EXCEPTION 'Customer accounts must use supported currency (INR)'
                USING ERRCODE = '42501';
        END IF;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_protect_account_insertion ON accounts;
CREATE TRIGGER trg_protect_account_insertion
    BEFORE INSERT ON accounts
    FOR EACH ROW
    EXECUTE FUNCTION protect_account_insertion();
