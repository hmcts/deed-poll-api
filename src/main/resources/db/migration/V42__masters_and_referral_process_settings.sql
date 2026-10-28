-- Plan D1 (Master Sullivan's feedback, item D): the Masters list, four more referral process settings beside the
-- claim window, which becomes the processing window, and England and Wales bank holidays. Snake_case, as V31/V40.

-- The Delivery Manager's Masters list. A Master can be listed, and assigned to, before they have ever signed in;
-- idam_subject is filled once, by matching the email IDAM gives for them, and never published.
create table deedpoll_master (
    id uuid primary key,
    name varchar(200) not null,
    email varchar(320) not null,
    available boolean not null default true,
    idam_subject varchar(255),
    linked_at timestamp,
    created_at timestamp not null,
    created_by_id varchar(255) not null,
    created_by_name varchar(255) not null,
    updated_at timestamp,
    updated_by_id varchar(255),
    updated_by_name varchar(255),
    revision integer not null default 0 check (revision >= 0),
    check ((idam_subject is null) = (linked_at is null))
);

create unique index deedpoll_master_email_key on deedpoll_master (lower(email));

create unique index deedpoll_master_idam_subject_key on deedpoll_master (idam_subject)
    where idam_subject is not null;

-- The new settings. Times are local times in Europe/London, on the quarter hour, and the morning window ends
-- before the afternoon one starts.
alter table deedpoll_process_settings
    add column reminder_interval_hours integer not null default 48
        check (reminder_interval_hours between 1 and 168),
    add column master_window_cap integer not null default 10
        check (master_window_cap between 1 and 100),
    add column morning_window_start time not null default '07:00',
    add column morning_window_end time not null default '10:30',
    add column afternoon_window_start time not null default '14:00',
    add column afternoon_window_end time not null default '14:30',
    add constraint deedpoll_process_settings_windows_quarter_hour check (
        extract(second from morning_window_start) = 0
        and extract(second from morning_window_end) = 0
        and extract(second from afternoon_window_start) = 0
        and extract(second from afternoon_window_end) = 0
        and extract(minute from morning_window_start)::integer % 15 = 0
        and extract(minute from morning_window_end)::integer % 15 = 0
        and extract(minute from afternoon_window_start)::integer % 15 = 0
        and extract(minute from afternoon_window_end)::integer % 15 = 0),
    add constraint deedpoll_process_settings_windows_order check (
        morning_window_start < morning_window_end
        and morning_window_end < afternoon_window_start
        and afternoon_window_start < afternoon_window_end);

-- The claim window becomes the processing window: how long a Master has to give a direction before the referral is
-- unassigned, 1 to 30 days. V40's inline check took Postgres's default name.
alter table deedpoll_process_settings
    drop constraint deedpoll_process_settings_referral_claim_expiry_minutes_check;

-- Never changed by a Delivery Manager: the new default, 7 days, with the row still at revision 0.
update deedpoll_process_settings set referral_claim_expiry_minutes = 10080
    where id = 1 and revision = 0;

-- Chosen by a Delivery Manager but below the new minimum: raised to 1 day, as a change of its own attributed to the
-- service account (name and id alike; it has no IDAM subject), so the history says why. A chosen value inside the
-- new range is left as it was. History first, while the row still holds the value it replaces.
insert into deedpoll_process_settings_change
    (revision, field, previous_value, new_value, changed_at, changed_by_id, changed_by_name)
select revision + 1, 'referralClaimExpiryMinutes', referral_claim_expiry_minutes::text, '1440',
       now() at time zone 'utc', '# svc_HMCTS_LCPT_Dev', '# svc_HMCTS_LCPT_Dev'
    from deedpoll_process_settings
    where id = 1 and revision > 0 and referral_claim_expiry_minutes < 1440;

update deedpoll_process_settings
    set referral_claim_expiry_minutes = 1440, revision = revision + 1, updated_at = now() at time zone 'utc',
        updated_by_id = '# svc_HMCTS_LCPT_Dev', updated_by_name = '# svc_HMCTS_LCPT_Dev'
    where id = 1 and revision > 0 and referral_claim_expiry_minutes < 1440;

alter table deedpoll_process_settings
    add constraint deedpoll_process_settings_referral_claim_expiry_minutes_check
        check (referral_claim_expiry_minutes between 1440 and 43200);

-- England and Wales bank holidays, which a Delivery Manager can add to or remove from.
create table deedpoll_bank_holiday (
    holiday_date date primary key,
    name varchar(200) not null,
    created_at timestamp not null,
    created_by_id varchar(255) not null,
    created_by_name varchar(255) not null
);

-- Copied by hand on 2026-10-28 from GOV.UK's published England and Wales list (https://www.gov.uk/bank-holidays),
-- which marks the substitute days. Nothing fetches it at build or run time.
insert into deedpoll_bank_holiday (holiday_date, name, created_at, created_by_id, created_by_name)
select holiday_date, name, now() at time zone 'utc', '# svc_HMCTS_LCPT_Dev', '# svc_HMCTS_LCPT_Dev'
    from (values
        (date '2026-01-01', 'New Year''s Day'),
        (date '2026-04-03', 'Good Friday'),
        (date '2026-04-06', 'Easter Monday'),
        (date '2026-05-04', 'Early May bank holiday'),
        (date '2026-05-25', 'Spring bank holiday'),
        (date '2026-08-31', 'Summer bank holiday'),
        (date '2026-12-25', 'Christmas Day'),
        (date '2026-12-28', 'Boxing Day (substitute day)'),
        (date '2027-01-01', 'New Year''s Day'),
        (date '2027-03-26', 'Good Friday'),
        (date '2027-03-29', 'Easter Monday'),
        (date '2027-05-03', 'Early May bank holiday'),
        (date '2027-05-31', 'Spring bank holiday'),
        (date '2027-08-30', 'Summer bank holiday'),
        (date '2027-12-27', 'Christmas Day (substitute day)'),
        (date '2027-12-28', 'Boxing Day (substitute day)'),
        (date '2028-01-03', 'New Year''s Day (substitute day)'),
        (date '2028-04-14', 'Good Friday'),
        (date '2028-04-17', 'Easter Monday'),
        (date '2028-05-01', 'Early May bank holiday'),
        (date '2028-05-29', 'Spring bank holiday'),
        (date '2028-08-28', 'Summer bank holiday'),
        (date '2028-12-25', 'Christmas Day'),
        (date '2028-12-26', 'Boxing Day')
    ) as seeded (holiday_date, name);
