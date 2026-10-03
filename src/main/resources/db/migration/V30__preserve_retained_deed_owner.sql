-- Recovery bytes must retain their case and preparation until verified read-back releases them.
-- Protect cascaded case deletion as well as direct deletion of preparation evidence.
CREATE FUNCTION deedpoll_preserve_retained_deed_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM deedpoll_stagedpayload WHERE id = OLD."documentId") THEN
        RAISE EXCEPTION 'Retained deed payload must not lose its preparation owner'
            USING ERRCODE = '23503';
    END IF;
    RETURN OLD;
END;
$$;
CREATE TRIGGER deedpoll_deedpreparation_retained_owner BEFORE DELETE ON deedpoll_deedpreparation
    FOR EACH ROW EXECUTE FUNCTION deedpoll_preserve_retained_deed_owner();
