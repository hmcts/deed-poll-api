create table deedpoll_process_settings (
    id integer primary key check (id = 1),
    referral_claim_expiry_minutes integer not null check (referral_claim_expiry_minutes between 30 and 1440),
    revision integer not null check (revision >= 0),
    updated_at timestamp,
    updated_by_id varchar(255),
    updated_by_name varchar(255),
    check ((revision = 0 and updated_at is null and updated_by_id is null and updated_by_name is null)
        or (revision > 0 and updated_at is not null and updated_by_id is not null and updated_by_name is not null))
);

insert into deedpoll_process_settings (id, referral_claim_expiry_minutes, revision) values (1, 120, 0);

create table deedpoll_process_settings_change (
    revision integer not null check (revision > 0),
    field varchar(64) not null,
    previous_value text,
    new_value text not null,
    changed_at timestamp not null,
    changed_by_id varchar(255) not null,
    changed_by_name varchar(255) not null,
    primary key (revision, field)
);
