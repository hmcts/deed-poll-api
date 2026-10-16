-- Per-identity request budgets for the abuse limits (OWASP plan 06): one fixed-window counter per (IDAM subject,
-- bucket). The row is overwritten when its window passes, and only a request the staff chain has admitted writes one,
-- so the table never grows past staff users x buckets and needs no cleanup job. It is shared by every replica and survives restarts, which a per-pod limiter would not.
--
-- The subject is the token's IDAM sub, never a name or an address. No case data is stored here.

create table "deedpoll_request_budget" (
    "subject" text not null,
    "bucket" text not null,
    "windowStart" timestamp(3) not null,
    "count" integer not null,
    primary key ("subject", "bucket")
);
