-- The fact that lets an attempt which ran out of tries on refusals be tried again from scratch (plan 02b, decision 9
-- as amended 2026-09-28).
--
-- WHY. V23's "submissionMayHaveReached" is set true before submitCaseCreation is called, so a submission CCD then
-- refused has set it as surely as one whose outcome nobody knows. An attempt that spent every try on refused
-- submissions, with nothing bound to its record, therefore reached NeedsRepair with the flag true, and "try again
-- from scratch" refused it for good -- while reconciliation, finding nothing bound, could only send it back. It was
-- stuck. Yet the automatic path already trusts "refused, and nothing bound": that is what releases a claim to
-- Pending, whose next claim starts a fresh event. Trusting the same evidence once more, for an admin, trusts nothing
-- new.
--
-- WHY IT HOLDS FOR THE WHOLE ATTEMPT. The only ways back to Pending are a release -- nothing created -- and an admin's
-- retry from scratch, which clears this column. So when a claim that created nothing finds no tries left, every
-- earlier try since the attempt was opened or last retried also ended in a release, and nothing was created by any of
-- them.
--
-- ONE TRANSACTION, no rewrite: a constant default is stored in the catalogue, not in every row.

ALTER TABLE "deedpoll_ccdcreationattempt"
    -- Whether it is proven that nothing this attempt submitted created a case. Set true by exactly one statement,
    -- CcdCreationAttemptRepository.exhaust, which moves a claim that created nothing and has no tries left from
    -- Claimed to NeedsRepair; no other way into NeedsRepair sets it. Kept by "reconcile again", which never
    -- submits. Cleared by "retry from scratch", which starts a new run of claims whose own outcome -- an unknown one,
    -- say -- must not inherit the old proof. While it is true a retry from scratch is open even with
    -- "submissionMayHaveReached" true (CcdLinkingRule.mayRetryFromScratch).
    --
    -- Every EXISTING row gets false, the conservative value: nobody proved anything about an attempt before this
    -- column existed. Never published in a DTO, never written to a log line; the list publishes only the answer,
    -- canRetryFromScratch.
    ADD COLUMN "provenNothingCreated" boolean NOT NULL DEFAULT false;
