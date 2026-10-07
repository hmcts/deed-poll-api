-- Preserve restrictive audit foreign keys while making parent checks and bounded previews indexed.
create index deedpoll_gazette_notice_batch_case_deed_poll_idx
    on deedpoll_gazette_notice_batch_case (deed_poll_id);

create index deedpoll_gazette_notice_batch_case_reference_idx
    on deedpoll_gazette_notice_batch_case (batch_id, reference_number collate "C", deed_poll_id);
