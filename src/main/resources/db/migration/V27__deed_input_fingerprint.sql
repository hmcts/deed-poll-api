-- Existing preparations have no provable input snapshot. Preserve them for investigation.
ALTER TABLE deedpoll_deedpreparation ADD COLUMN "inputFingerprint" varchar(64)
    CHECK ("inputFingerprint" ~ '^[0-9a-f]{64}$');
