-- Plan F1: referrals wait in a queue and a Team leader or Delivery Manager sends them to Masters in batches.
--
-- The cap becomes the window's total across every Master, so V42's per-Master name goes. The value, default (10)
-- and bounds (1 to 100) are kept; V42's inline check took Postgres's default name, renamed with the column. History
-- rows already written keep the old field name, masterWindowCap; new rows use windowCap.
alter table deedpoll_process_settings rename column master_window_cap to window_cap;

alter table deedpoll_process_settings
    rename constraint deedpoll_process_settings_master_window_cap_check to deedpoll_process_settings_window_cap_check;

-- The email a Master is sent for each send (and each reassignment to them). DIGEST stays admitted, because rows
-- already written keep it, though nothing writes it any more. V44's inline check took Postgres's default name.
alter table deedpoll_master_email drop constraint deedpoll_master_email_kind_check;

alter table deedpoll_master_email
    add constraint deedpoll_master_email_kind_check check (kind in ('DIGEST', 'REMINDER', 'SENT'));
