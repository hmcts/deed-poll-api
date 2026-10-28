-- Plan D3 (Master Sullivan's feedback, item D): the two emails a Master receives, the window digest and the
-- reminder. The first emails this service sends to anybody but an applicant.
--
-- They do not go through the case outbox: "deedpoll_notification" is case-scoped ("deedPollId" NOT NULL, per-case
-- indexes, a case note per event), and a digest spans cases. So they get their own small ledger, which copies the
-- outbox's delivery rules (an owner and a lease, "Sending" committed before the one Notify call, an unknown outcome
-- never resent automatically) and none of its tables.
--
-- snake_case, as V31, V40 and V42 are; only the referral column below is mixed case, because its table is.

CREATE TABLE deedpoll_master_email (
    id uuid PRIMARY KEY,
    -- The recipient. Masters are never deleted (V42), so the restrictive foreign key costs nothing. The address is
    -- read from the Masters list at send time and never copied here, so a Master whose email changed gets it at the
    -- new address and the ledger holds no address at all.
    master_id uuid NOT NULL REFERENCES deedpoll_master (id),
    kind varchar(16) NOT NULL CHECK (kind IN ('DIGEST', 'REMINDER')),
    -- A digest's window ("2026-10-28:AM"), or a reminder's run (its start to the minute and the pod's run id).
    period_key varchar(64) NOT NULL,
    -- The referrals the email lists, in the order it lists them.
    referral_ids uuid[] NOT NULL,
    -- Skipped: the template was unprovisioned when the row was written, so nothing is ever sent for it.
    state varchar(16) NOT NULL CHECK (state IN ('Pending', 'Sending', 'Sent', 'Failed', 'Skipped')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    -- Who holds the row's claim, and until when. Set together.
    owner uuid,
    lease_until timestamp,
    -- Committed before the Notify call. A row with it set is never sent again automatically (ADR-0007).
    send_started_at timestamp,
    notify_id uuid,
    -- A NotificationFailure name, never a provider message.
    failure varchar(32),
    created_at timestamp NOT NULL,
    settled_at timestamp,

    CONSTRAINT deedpoll_master_email_claim_check CHECK ((owner IS NULL) = (lease_until IS NULL)),
    -- What makes two pods enqueue one email: one per Master, per kind, per window or run.
    CONSTRAINT deedpoll_master_email_period_key UNIQUE (master_id, kind, period_key)
);

-- The drain's claim: the Pending rows, oldest first.
CREATE INDEX deedpoll_master_email_pending_idx ON deedpoll_master_email (created_at) WHERE state = 'Pending';

-- One row per closed referral window, inserted by whichever pod reaches it first. Only the pod whose insert wrote
-- the row enqueues that window's digests.
CREATE TABLE deedpoll_window_digest_run (
    window_date date NOT NULL,
    window_slot varchar(2) NOT NULL CHECK (window_slot IN ('AM', 'PM')),
    ran_at timestamp NOT NULL,
    PRIMARY KEY (window_date, window_slot)
);

-- How many reminders the referral's current assignment has had. A reminder k is due once k reminder intervals have
-- passed since "assignedAt" and k > "remindersSent"; a conditional UPDATE raising it to k is the claim, so two pods
-- remind once. Reset to 0 whenever the clock restarts: an assignment, a reassignment, a withdrawn direction.
ALTER TABLE "deedpoll_referral"
    ADD COLUMN "remindersSent" integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT "deedpoll_referral_remindersSent_check" CHECK ("remindersSent" >= 0);
