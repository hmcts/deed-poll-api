-- No backfill: a legacy type name is not proof that we generated a document.
-- Case uniqueness spans both templates. The identity survives document removal so removal
-- cannot silently grant permission to generate another legal instrument. No document FK for that reason.
CREATE TABLE "deedpoll_deedpreparation" (
    "deedPollId" text PRIMARY KEY REFERENCES "deedpoll_deedpoll" ("id") ON DELETE CASCADE,
    "documentId" text NOT NULL UNIQUE,
    "template" varchar(16) NOT NULL CHECK ("template" IN ('ADULT', 'MINOR')),
    "originalName" text NOT NULL,
    "mimeType" text NOT NULL,
    "sizeBytes" integer NOT NULL CHECK ("sizeBytes" > 0 AND "sizeBytes" <= 26214400),
    "checksum" varchar(64) NOT NULL CHECK ("checksum" ~ '^[0-9a-f]{64}$'),
    "createdOn" timestamp(3) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Separate payload reads from ordinary case/document queries. Also the backing store for
-- future owned staging (Task 6). All admission takes the same transaction advisory lock;
-- sum(sizeBytes), including failed/uncertain preparations, counts retained storage against the cap.
CREATE TABLE "deedpoll_stagedpayload" (
    "id" text PRIMARY KEY,
    "content" bytea NOT NULL,
    "sizeBytes" integer NOT NULL CHECK ("sizeBytes" > 0 AND "sizeBytes" <= 26214400),
    "checksum" varchar(64) NOT NULL CHECK ("checksum" ~ '^[0-9a-f]{64}$'),
    CHECK (octet_length("content") = "sizeBytes"),
    CHECK (encode(sha256("content"), 'hex') = "checksum")
);

-- Immutable evidence. Payload removal after verified read-back is separate from reservation
-- removal; there is deliberately no expiry or deletion operation in Task 2.
CREATE FUNCTION deedpoll_refuse_preparation_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Deed preparation evidence is immutable';
END;
$$;
CREATE TRIGGER deedpoll_deedpreparation_immutable BEFORE UPDATE ON "deedpoll_deedpreparation"
    FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_preparation_update();
CREATE TRIGGER deedpoll_stagedpayload_immutable BEFORE UPDATE ON "deedpoll_stagedpayload"
    FOR EACH ROW EXECUTE FUNCTION deedpoll_refuse_preparation_update();
