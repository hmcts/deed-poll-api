package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteBatchStatus;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteMember;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestState;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class GazetteStatusRepository {
    private static final String REQUEST = """
        SELECT id, state, submitted_at, submitted_by_name, recipient_email, recipient_revision,
            adult_count + minor_count AS case_count, adult_count, minor_count,
            CASE WHEN state = 'Sent' THEN settled_at END AS sent_at, failure_code
        FROM deedpoll_gazette_request
        """;
    /*
      Each statement is a compile-time constant, one per variant, rather than SQL concatenated at runtime: the
      choice between variants is code, and every value is a bound parameter.
    */
    private static final String PAGE = " ORDER BY submitted_at DESC, id DESC LIMIT ? OFFSET ?";
    private static final String COUNT_ALL = "SELECT count(*) FROM deedpoll_gazette_request";
    private static final String COUNT_IN_STATE = COUNT_ALL + " WHERE state = ?";
    private static final String LIST_ALL = REQUEST + PAGE;
    private static final String LIST_IN_STATE = REQUEST + " WHERE state = ?" + PAGE;
    private static final String REQUEST_MEMBER_COUNT =
        "SELECT count(*) FROM deedpoll_gazette_request_case WHERE batch_id = ?";
    private static final String REQUEST_MEMBERS = "SELECT deed_poll_id, reference_number "
        + "FROM deedpoll_gazette_request_case WHERE batch_id = ? ORDER BY reference_number, deed_poll_id "
        + "LIMIT ? OFFSET ?";
    private static final String BATCH_MEMBER_COUNT =
        "SELECT count(*) FROM deedpoll_gazette_notice_batch_case WHERE batch_id = ?";
    private static final String BATCH_MEMBERS = "SELECT deed_poll_id, reference_number "
        + "FROM deedpoll_gazette_notice_batch_case WHERE batch_id = ? ORDER BY reference_number, deed_poll_id "
        + "LIMIT ? OFFSET ?";
    private static final RowMapper<GazetteBatchStatus> ROW = (row, index) -> new GazetteBatchStatus(
        row.getObject("id", UUID.class), GazetteRequestState.valueOf(row.getString("state")),
        row.getObject("submitted_at", LocalDateTime.class), row.getString("submitted_by_name"),
        row.getString("recipient_email"), row.getObject("recipient_revision", Integer.class), row.getInt("case_count"),
        row.getObject("adult_count", Integer.class), row.getObject("minor_count", Integer.class),
        row.getObject("sent_at", LocalDateTime.class), row.getString("failure_code") == null ? null
            : GazetteBatchStatus.FailureCode.valueOf(row.getString("failure_code")));
    private final JdbcTemplate jdbc;

    public GazetteStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<GazetteBatchStatus> list(CasePaging paging, GazetteRequestState state) {
        long offset = (long) paging.index() * paging.size();
        long total = state == null ? jdbc.queryForObject(COUNT_ALL, Long.class)
            : jdbc.queryForObject(COUNT_IN_STATE, Long.class, state.name());
        List<GazetteBatchStatus> rows = state == null ? jdbc.query(LIST_ALL, ROW, paging.size(), offset)
            : jdbc.query(LIST_IN_STATE, ROW, state.name(), paging.size(), offset);
        return new PageImpl<>(rows, PageRequest.of(paging.index(), paging.size()), total);
    }

    public Optional<GazetteBatchStatus> detail(UUID id) {
        Optional<GazetteBatchStatus> request = jdbc.query(REQUEST + " WHERE id = ?", ROW, id).stream().findFirst();
        if (request.isPresent()) {
            return request;
        }
        return jdbc.query("""
            SELECT id, 'Sent' AS state, NULL::timestamp AS submitted_at, NULL::text AS submitted_by_name,
                NULL::text AS recipient_email, NULL::integer AS recipient_revision, case_count,
                NULL::integer AS adult_count, NULL::integer AS minor_count, sent_at, NULL::text AS failure_code
            FROM deedpoll_gazette_notice_batch WHERE id = ?
            """, ROW, id).stream().findFirst();
    }

    public Page<GazetteMember> members(UUID id, CasePaging paging) {
        boolean request = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_gazette_request WHERE id = ?)", Boolean.class, id));
        long total = jdbc.queryForObject(request ? REQUEST_MEMBER_COUNT : BATCH_MEMBER_COUNT, Long.class, id);
        List<GazetteMember> rows = jdbc.query(request ? REQUEST_MEMBERS : BATCH_MEMBERS,
            (row, index) -> new GazetteMember(row.getString(1), row.getString(2)),
            id, paging.size(), (long) paging.index() * paging.size());
        return new PageImpl<>(rows, PageRequest.of(paging.index(), paging.size()), total);
    }
}
