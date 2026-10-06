-- Audit evidence only; the Gazette send/recovery design remains a separate task.
create table deedpoll_gazette_notice_batch (
    id uuid primary key,
    sent_at timestamp not null,
    sent_by_id varchar(255) not null,
    sent_by_name varchar(255) not null,
    request_id uuid not null,
    case_count integer not null
);

create unique index deedpoll_gazette_notice_batch_request_id_key
    on deedpoll_gazette_notice_batch (request_id);

create table deedpoll_gazette_notice_batch_case (
    batch_id uuid not null references deedpoll_gazette_notice_batch (id),
    -- V2 stores case IDs as text; the foreign key must use the same type.
    deed_poll_id text not null references deedpoll_deedpoll (id),
    reference_number varchar(255) not null,
    primary key (batch_id, deed_poll_id)
);
