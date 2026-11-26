-- Permanent document deletion's local completion counts its own failures (plan 20261125-permanent-document-deletion,
-- review). V51's attempts counts every claim of a request -- remote-step retries, passes the budget sent nothing in,
-- staff replays -- so comparing it with max-attempts let one transient completion failure, after a few of those,
-- leave a request NeedsAttention with every remote step already Done. Only DocumentDeletionWork.complete writes it.
ALTER TABLE deedpoll_document_deletion_request
    ADD COLUMN completion_attempts integer NOT NULL DEFAULT 0
        CONSTRAINT deedpoll_document_deletion_request_completion_attempts_check CHECK (completion_attempts >= 0);
