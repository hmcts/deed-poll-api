-- Plan D3, delivery: when a Master email refused for a known temporary reason (Notify's 429) may be tried again.
-- V44 gave the ledger no such column, and a rate limit retried on every one-minute drain is how a service stays
-- rate-limited, so the case outbox's backoff (NotificationBackoff: 1, 2, 4 ... minutes, an hour at most) is copied
-- here as it is. NULL means at once.
ALTER TABLE deedpoll_master_email ADD COLUMN next_attempt_at timestamp;
