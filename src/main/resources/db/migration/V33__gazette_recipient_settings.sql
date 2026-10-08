create table deedpoll_gazette_settings (
    id integer primary key check (id = 1),
    recipient_email varchar(254),
    revision integer not null check (revision >= 0),
    updated_at timestamp,
    updated_by_id varchar(255),
    updated_by_name varchar(255),
    check ((revision = 0 and recipient_email is null and updated_at is null
            and updated_by_id is null and updated_by_name is null)
        or (revision > 0 and recipient_email is not null and updated_at is not null
            and updated_by_id is not null and updated_by_name is not null))
);

insert into deedpoll_gazette_settings (id, revision) values (1, 0);

create table deedpoll_gazette_settings_change (
    revision integer primary key check (revision > 0),
    previous_email varchar(254),
    recipient_email varchar(254) not null,
    changed_at timestamp not null,
    changed_by_id varchar(255) not null,
    changed_by_name varchar(255) not null
);
