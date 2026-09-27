-- The CCD case reference of the one genuine CCD case linked to this record (plan 02, decision 7).
--
-- V21 as plan 02 names it: V20__notification_case_exclusion_group.sql is the last committed migration, confirmed
-- by listing the directory.
--
-- NULLABLE, and a null is not a state (decision 12). Every existing row, every migrated record and every record
-- whose linking operation has not yet succeeded has none, and none of them may be hidden from the queue, counted
-- differently or read as "unpaid online" because of it. Nothing in DeedPollFields reads this column, so no list,
-- count, filter, sort or query can name it.
--
-- text-shaped, never a number, at every boundary. CCD's own reference is a 16-digit Luhn number that overflows
-- nothing but loses its leading zeros the moment anything treats it as an integer, and the JSON a number would
-- become is a double. varchar(16) plus the CHECK below is the whole format rule; CcdCaseReference.PATTERN states
-- the same rule in Java, and CcdCaseReferenceTest holds the two to each other by reading this file.
--
-- UNIQUE WHEN PRESENT. One CCD case belongs to one record. A plain unique constraint gives that, because
-- Postgres treats NULLs as distinct: any number of unlinked rows, never two rows with one reference.
--
-- NEVER OVERWRITTEN. The entity maps this column insertable = false, updatable = false, so no save or merge of a
-- DeedPoll -- a caseworker's patch included -- can write it, and a stale read cannot null it out. Its only
-- writer is DeedPollRepository.bindCcdCaseReference, a single-column UPDATE conditional on the column still
-- being NULL.
--
-- Mixed-case column name for the mechanical reason V6 and V11 give.
ALTER TABLE "deedpoll_deedpoll"
    ADD COLUMN "ccdCaseReference" varchar(16);

ALTER TABLE "deedpoll_deedpoll"
    ADD CONSTRAINT "deedpoll_deedpoll_ccdCaseReference_key" UNIQUE ("ccdCaseReference");

ALTER TABLE "deedpoll_deedpoll"
    ADD CONSTRAINT "deedpoll_deedpoll_ccdCaseReference_format"
    CHECK ("ccdCaseReference" ~ '^[0-9]{16}$');
