-- One-time backfill: the power app's referrals, moved out of the case's flat columns and into
-- "deedpoll_referral".
--
-- Data, not schema, and deliberately its own migration so that "the table exists" and "the table was
-- seeded" are separate versions in flyway_schema_history.
--
-- Why it exists at all: cutover happens with real referrals in flight. V2's inline block is the only
-- record of them, and leaving it there would mean the new referrals tab showed nothing for a case
-- whose referral is the reason a caseworker opened it.
--
-- The state rule, and it is a judgement rather than a lookup:
--
--   * "masterDecision" set  -> 'Closed'. A decision came back, so the referral finished. Which
--     decision it was lives in the case's own columns; this table starts recording that from the
--     directions plan onwards, and inventing a direction here would be fabricating history.
--   * "dateReferred" set, no decision -> 'Open'. This is a live backlog item and it goes into the
--     pool, not into a tidy terminal state -- a referral quietly closed at migration is one nobody
--     ever answers. The handful that turn out to be stale get a manual clear-up, which is a smaller
--     problem than a lost one.
--   * Neither set -> no row. A case that was never referred does not gain a referral.
--
-- "reasons" is '[]' and "migrated" is true, together: the reason set did not exist in the power app,
-- so there is nothing to derive one from, and the flag is what stops an empty array reading as a bug.
-- Guessing reasons from the case (a minor, say) would put invented data in a column caseworkers
-- filter on -- explicitly out of scope.
--
-- "urgency" and "urgencyReason" both come from the case, as a pair. The case's urgency is the only
-- urgency signal that exists, and carrying the reason with it keeps a backfilled 'Urgent' referral
-- saying why -- which is the rule the domain enforces on every new one.
--
-- The audit trail says what actually happened: the service account wrote these rows, not a
-- caseworker, and the name matches CurrentUser.SERVICE_ACCOUNT_NAME so the notes grid and the
-- referral list attribute migrated writes identically. "createdOn" is the date the case was
-- referred where that is known, because that is when the referral came into being; "modifiedOn" is
-- now, because now is when this row was written.
--
-- One statement, so it is all-or-nothing without a transaction block of its own.
INSERT INTO "deedpoll_referral" ("id",
                                 "deedPollId",
                                 "state",
                                 "reasons",
                                 "notes",
                                 "urgency",
                                 "urgencyReason",
                                 "migrated",
                                 "createdOn",
                                 "modifiedOn",
                                 "createdBy",
                                 "modifiedBy")
SELECT gen_random_uuid(),
       "id",
       CASE WHEN "masterDecision" IS NOT NULL THEN 'Closed' ELSE 'Open' END::"ReferralState",
       '[]'::jsonb,
       "referralNotes",
       "urgency",
       "urgencyreason",
       true,
       COALESCE("dateReferred", CURRENT_TIMESTAMP),
       CURRENT_TIMESTAMP,
       '# svc_HMCTS_LCPT_Dev',
       '# svc_HMCTS_LCPT_Dev'
FROM "deedpoll_deedpoll"
WHERE "masterDecision" IS NOT NULL
   OR "dateReferred" IS NOT NULL;
