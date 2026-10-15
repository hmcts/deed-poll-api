-- The append-only audit trail: who did what, to which case, and when.
--
-- The event row holds identifiers and field names only, never applicant data, and is kept for 7 years. The value
-- rows hold the before and after values, which are applicant data, and are disposed with the case's other applicant
-- data. Neither table is ever updated or deleted by application code: the triggers below refuse it unless the
-- transaction-local setting deedpoll.audit_disposal is on, and only the two disposal functions set it.
--
-- Residual risk: until a least-privilege runtime role exists, the api's own login owns these tables and can alter
-- the triggers. The setting stops application code deleting rows by accident, not a determined owner.

create table deedpoll_audit_event (
    id bigserial primary key,
    occurred_at timestamp not null,
    actor_id varchar(255),
    actor_name varchar(255) not null,
    action varchar(64) not null,
    entity_type varchar(64) not null,
    entity_id text not null,
    case_id text,
    fields text[] not null default '{}',
    detail jsonb
);

create index deedpoll_audit_event_case_idx on deedpoll_audit_event (case_id, occurred_at);
create index deedpoll_audit_event_actor_idx on deedpoll_audit_event (actor_id, occurred_at);

create table deedpoll_audit_event_value (
    event_id bigint not null references deedpoll_audit_event (id),
    field varchar(128) not null,
    old_value text,
    new_value text,
    primary key (event_id, field)
);

create function deedpoll_audit_refuse_change() returns trigger
    language plpgsql
as $$
begin
    if tg_op = 'TRUNCATE' or coalesce(current_setting('deedpoll.audit_disposal', true), '') <> 'on' then
        raise exception 'deedpoll audit rows are append-only: % on % refused', tg_op, tg_table_name;
    end if;
    if tg_op = 'UPDATE' then
        return new;
    end if;
    return old;
end;
$$;

create trigger deedpoll_audit_event_append_only
    before update or delete on deedpoll_audit_event
    for each row execute function deedpoll_audit_refuse_change();

create trigger deedpoll_audit_event_no_truncate
    before truncate on deedpoll_audit_event
    for each statement execute function deedpoll_audit_refuse_change();

create trigger deedpoll_audit_event_value_append_only
    before update or delete on deedpoll_audit_event_value
    for each row execute function deedpoll_audit_refuse_change();

create trigger deedpoll_audit_event_value_no_truncate
    before truncate on deedpoll_audit_event_value
    for each statement execute function deedpoll_audit_refuse_change();

-- The two controlled deletion paths. Each turns the setting on for its own deletes and off again before returning,
-- so a statement later in the same transaction is refused as before. An error aborts the transaction, or rolls back
-- to the caller's savepoint, either of which also discards the setting.

create function deedpoll_audit_dispose_case_values(target_case_id text) returns integer
    language plpgsql
    security definer
    set search_path from current
as $$
declare
    deleted integer;
begin
    perform set_config('deedpoll.audit_disposal', 'on', true);
    delete from deedpoll_audit_event_value v
        using deedpoll_audit_event e
        where v.event_id = e.id and e.case_id = target_case_id;
    get diagnostics deleted = row_count;
    perform set_config('deedpoll.audit_disposal', 'off', true);
    return deleted;
end;
$$;

create function deedpoll_audit_dispose_events_before(cutoff timestamp) returns integer
    language plpgsql
    security definer
    set search_path from current
as $$
declare
    deleted integer;
begin
    perform set_config('deedpoll.audit_disposal', 'on', true);
    delete from deedpoll_audit_event_value v
        using deedpoll_audit_event e
        where v.event_id = e.id and e.occurred_at < cutoff;
    delete from deedpoll_audit_event where occurred_at < cutoff;
    get diagnostics deleted = row_count;
    perform set_config('deedpoll.audit_disposal', 'off', true);
    return deleted;
end;
$$;

revoke execute on function deedpoll_audit_dispose_case_values(text) from public;
revoke execute on function deedpoll_audit_dispose_events_before(timestamp) from public;
