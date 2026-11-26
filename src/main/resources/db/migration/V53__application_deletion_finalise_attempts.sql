-- How many times local finalisation of an application deletion request has failed with an unexpected error.
--
-- DeletionCleanup retried such a failure on every poll for ever: DeletionProgress counts only the remote steps'
-- attempts, so a deterministic finalisation fault (a protecting trigger, a mapping bug) never reached
-- NeedsAttention and only a WARN said anything (Yarrow, 2026-11-26). The worker now counts each failed pass here and
-- stops at the same maxAttempts the steps use, as NeedsAttention LOCAL_FINALISATION_REFUSED. A requeue
-- (docs/deletion/requeue.sql) resets it, so a request an operator has fixed gets a fresh set of tries.

ALTER TABLE deedpoll_deletion_request
    ADD COLUMN finalise_attempts integer NOT NULL DEFAULT 0 CHECK (finalise_attempts >= 0);
