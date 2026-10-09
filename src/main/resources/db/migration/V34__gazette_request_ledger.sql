-- Request evidence exists before any remote send. V31 remains completed-send history.
create table deedpoll_gazette_request (
    id uuid primary key,
    request_id uuid not null unique,
    payload_hash varchar(64) not null check (payload_hash ~ '^[0-9a-f]{64}$'),
    selection_fingerprint varchar(512) not null,
    submitted_by_id varchar(255) not null,
    submitted_by_name varchar(200) not null,
    submitted_at timestamp not null,
    recipient_email varchar(254) not null,
    recipient_revision integer not null check (recipient_revision > 0),
    adult_count integer not null check (adult_count >= 0),
    minor_count integer not null check (minor_count >= 0),
    template_id uuid not null,
    reply_to_id uuid not null,
    retention_weeks integer not null check (retention_weeks between 1 and 78),
    confirm_email boolean not null check (confirm_email),
    owner uuid not null,
    lease_until timestamp not null,
    state varchar(16) not null check (state in ('Preparing', 'Sending', 'Sent', 'NotSent', 'Uncertain')),
    send_started_at timestamp,
    settled_at timestamp,
    failure_code varchar(40),
    provider_notification_id uuid,
    template_version integer check (template_version > 0),
    check (adult_count + minor_count between 1 and 100),
    check (lease_until > submitted_at),
    check ((state in ('Preparing', 'Sending') and settled_at is null and failure_code is null)
        or (state = 'Sent' and settled_at is not null and failure_code is null
            and provider_notification_id is not null)
        or (state = 'NotSent' and settled_at is not null and failure_code is not null and failure_code in
            ('PREPARATION_FAILED', 'CONFIGURATION_INVALID', 'PROVIDER_REFUSED', 'PRE_SEND_EXPIRED'))
        or (state = 'Uncertain' and settled_at is not null and failure_code is not null
            and failure_code = 'SEND_OUTCOME_UNKNOWN')),
    check ((state = 'Preparing' and send_started_at is null)
        or (state in ('Sending', 'Sent', 'Uncertain') and send_started_at is not null)
        or state = 'NotSent'),
    check (state <> 'NotSent' or ((send_started_at is null and failure_code <> 'PROVIDER_REFUSED')
        or (send_started_at is not null and failure_code = 'PROVIDER_REFUSED')))
);
create index deedpoll_gazette_request_recovery on deedpoll_gazette_request (lease_until, id)
    where state in ('Preparing', 'Sending');

create table deedpoll_gazette_request_case (
    batch_id uuid not null references deedpoll_gazette_request(id),
    deed_poll_id text not null references deedpoll_deedpoll(id),
    reference_number varchar(255) not null,
    kind varchar(5) not null check (kind in ('adult', 'minor')),
    primary key (batch_id, deed_poll_id)
);
create index deedpoll_gazette_request_case_case on deedpoll_gazette_request_case (deed_poll_id);

create table deedpoll_gazette_reservation (
    deed_poll_id text primary key references deedpoll_deedpoll(id),
    request_batch_id uuid references deedpoll_gazette_request(id),
    sent_batch_id uuid references deedpoll_gazette_notice_batch(id),
    check (num_nonnulls(request_batch_id, sent_batch_id) = 1)
);
create index deedpoll_gazette_reservation_request on deedpoll_gazette_reservation (request_batch_id);
-- One permanent reservation per previously sent case, without removing duplicate historical memberships.
insert into deedpoll_gazette_reservation (deed_poll_id, sent_batch_id)
select distinct on (m.deed_poll_id) m.deed_poll_id, b.id
from deedpoll_gazette_notice_batch_case m join deedpoll_gazette_notice_batch b on b.id = m.batch_id
order by m.deed_poll_id, b.sent_at, b.id;

create table deedpoll_gazette_request_file (
    batch_id uuid not null references deedpoll_gazette_request(id),
    kind varchar(5) not null check (kind in ('adult', 'minor')),
    filename varchar(40) not null,
    case_count integer not null check (case_count > 0),
    byte_size integer not null check (byte_size between 1 and 2097152),
    sha256 varchar(64) not null check (sha256 ~ '^[0-9a-f]{64}$'),
    primary key (batch_id, kind),
    check (filename = 'gazette-' || kind || '.xlsx')
);
