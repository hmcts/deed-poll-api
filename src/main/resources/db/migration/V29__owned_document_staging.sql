-- Payloads use V26's immutable store and its shared quota lock. No draft/case cascade may erase recovery bytes.
CREATE TABLE deedpoll_ownedstage (
    id text PRIMARY KEY,
    "ownerKind" text NOT NULL CHECK ("ownerKind" IN ('RECORD', 'DRAFT')),
    "ownerId" text NOT NULL,
    subject text NOT NULL,
    "operationId" uuid NOT NULL,
    "originalName" text NOT NULL,
    "mimeType" text NOT NULL,
    "sizeBytes" integer NOT NULL CHECK ("sizeBytes" > 0 AND "sizeBytes" <= 26214400),
    checksum varchar(64) NOT NULL,
    "createdOn" timestamptz NOT NULL,
    "expiresOn" timestamptz NOT NULL,
    state text NOT NULL CHECK (state IN ('Staged', 'Promoting', 'Consumed', 'Removed', 'Expired')),
    "caseId" text,
    "documentId" text UNIQUE,
    "documentTypeId" text,
    UNIQUE ("ownerKind", "ownerId", "operationId"),
    CHECK ("expiresOn" > "createdOn"),
    CHECK ((state IN ('Staged', 'Removed', 'Expired') AND "caseId" IS NULL AND "documentId" IS NULL)
        OR (state IN ('Promoting', 'Consumed') AND "caseId" IS NOT NULL AND "documentId" IS NOT NULL
            AND "documentTypeId" IS NOT NULL))
);
CREATE INDEX deedpoll_ownedstage_expiry ON deedpoll_ownedstage ("expiresOn") WHERE state = 'Staged';

CREATE FUNCTION deedpoll_preserve_stage_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id, NEW."ownerKind", NEW."ownerId", NEW.subject, NEW."operationId", NEW."originalName",
        NEW."mimeType", NEW."sizeBytes", NEW.checksum, NEW."createdOn", NEW."expiresOn") IS DISTINCT FROM
       (OLD.id, OLD."ownerKind", OLD."ownerId", OLD.subject, OLD."operationId", OLD."originalName",
        OLD."mimeType", OLD."sizeBytes", OLD.checksum, OLD."createdOn", OLD."expiresOn")
       OR (OLD."caseId" IS NOT NULL AND
           (NEW."caseId", NEW."documentId", NEW."documentTypeId") IS DISTINCT FROM
           (OLD."caseId", OLD."documentId", OLD."documentTypeId"))
       OR NOT (NEW.state = OLD.state OR (OLD.state = 'Staged' AND NEW.state IN ('Promoting','Removed','Expired'))
           OR (OLD.state = 'Promoting' AND NEW.state = 'Consumed')) THEN
        RAISE EXCEPTION 'Staging evidence cannot be rewritten';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER deedpoll_ownedstage_evidence BEFORE UPDATE ON deedpoll_ownedstage
    FOR EACH ROW EXECUTE FUNCTION deedpoll_preserve_stage_evidence();
