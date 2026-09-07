-- Indexes for the access paths the caseworker endpoints actually use.
--
-- V2 indexed the columns the Dynamics views filtered on. What it did not index is the column every
-- list query ORDERS BY, which is the one the list page cannot avoid touching.

-- Every DeedPollRepository finder ends "order by createdOn desc", over a set with no pagination.
-- Without this, each list request is a sequential scan of the whole case table plus an external
-- sort of every row in it -- including the filtered variants, because the existing "state" index
-- cannot supply the ordering and Postgres sorts after filtering.
--
-- Composite rather than "createdOn" alone: the leading "state" serves the two state filters, and the
-- trailing "createdOn" lets those read out in order rather than sorting. The "all" filter, which has
-- no state predicate, is served by the second index. DESC is stated to match the query, though
-- Postgres can also read an ASC index backwards -- saying it makes the intent legible next to the
-- finder that depends on it.
CREATE INDEX "deedpoll_deedpoll_createdOn_idx"
    ON "deedpoll_deedpoll" ("createdOn" DESC);

CREATE INDEX "deedpoll_deedpoll_state_createdOn_idx"
    ON "deedpoll_deedpoll" ("state", "createdOn" DESC);

-- Postgres does NOT index a foreign key column automatically, only the primary key it references.
-- "documentTypeId" has both a RESTRICT foreign key (V3) and a countByDocumentTypeId consumer, so
-- deleting a document type scans the whole document table twice -- once for the application's count,
-- once for Postgres's own RESTRICT check.
CREATE INDEX "deedpoll_supportingdocument_documentTypeId_idx"
    ON "deedpoll_supportingdocument" ("documentTypeId");
