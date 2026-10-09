-- Bounded newest-first request reads and full frozen membership pagination.
create index deedpoll_gazette_request_submitted on deedpoll_gazette_request (submitted_at desc, id desc);
create index deedpoll_gazette_request_state_submitted
    on deedpoll_gazette_request (state, submitted_at desc, id desc);
create index deedpoll_gazette_request_case_reference
    on deedpoll_gazette_request_case (batch_id, reference_number, deed_poll_id);
