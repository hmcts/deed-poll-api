package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * When anything about a case last changed: the later of its own {@code modifiedOn} and its newest audited write.
 *
 * <p>{@code modifiedOn} moves only when the case row is written. A note, a witness, a document, a referral, an email
 * or a Master's direction leaves it alone, but every such write records an {@code AuditTrail} event on the case in its
 * own transaction, so the newest non-read event is when one of them last happened. Reads ({@link AuditAction#isRead})
 * are left out: opening the case to review it must not make the review stale.
 *
 * <p>Published as the case detail's {@code lastChangedOn} ({@code DeedPollService.findDeedPollForReview}) and compared
 * by application deletion ({@code ApplicationDeletionService.refuseIfIneligible}, which calls {@link #of} in its own
 * statement after {@code DeletionAdmissionRepository.lock} has taken the case lock, so a change committed while it
 * waited is counted). A change made after the Delivery Manager opened the case therefore refuses the deletion. Both
 * sides call {@link #of}, so they cannot disagree.
 */
@Repository
public class CaseLastChanged {

    /**
     * The read actions, written out so the statement is a constant: {@link AuditAction}'s {@code isRead} members.
     * {@code CaseLastChangedTest} fails if the two ever differ, so a new read action is added here too.
     */
    static final List<String> READ_ACTIONS = List.of("CASE_VIEWED", "DOCUMENT_DOWNLOADED", "DOCUMENT_PREVIEWED");

    /**
     * The case's last change; {@code greatest} ignores the null of a case with no audited write yet.
     */
    private static final String SQL = """
        SELECT greatest(d."modifiedOn", (SELECT max(e.occurred_at) FROM deedpoll_audit_event e
                                          WHERE e.case_id = d.id
                                            AND e.action NOT IN ('CASE_VIEWED', 'DOCUMENT_DOWNLOADED',
                                                                 'DOCUMENT_PREVIEWED'))) AS last_changed
          FROM deedpoll_deedpoll d WHERE d.id = ?
        """;

    /**
     * The statement, for the test that holds its read actions to {@link #READ_ACTIONS}.
     */
    static String sql() {
        return SQL;
    }

    private final JdbcTemplate jdbc;

    public CaseLastChanged(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The case's last change, or empty when there is no such case. Read it <em>before</em> the case it describes, so a
     * write landing between the two reads makes the value older than what was shown, which refuses a deletion rather
     * than admitting one over a change nobody saw.
     *
     * <p><strong>It is a time, not a version.</strong> {@code modifiedOn} and {@code occurred_at} are stamped from the
     * pod's clock while the write runs, not when it commits, and child writes do not lock the case. So a write that
     * stamps T1, then commits after another write stamped T2 &gt; T1 was already shown, does not move the value, and a
     * deletion reviewed at T2 is admitted over it; pods whose clocks disagree do the same. The window is one child
     * write's transaction (or the clock skew). It is accepted (Steve, 2026-11-19): a deletion changes nothing
     * permanent during its grace period, and a Delivery Manager can restore it whole then. A value every commit moves
     * was tried (a commit-time trigger locking the case row) and removed, because it deadlocked against Gazette
     * settlement and the referral queue (docs/APPLICATION-DELETION.md).
     *
     * <p>Some writes to the case row are deliberately not changes, because they are bulk updates that bypass the
     * entity's {@code @PreUpdate} and record no audit event: the CCD binding ({@code bindCcdCaseReference}) and the
     * read-model recomputes ({@code currentReferralState}, {@code awaitingConfirmationSince}). Each follows from
     * something else, which is itself a change if it is one.
     */
    public Optional<LocalDateTime> of(String caseId) {
        return jdbc.query(SQL, (row, index) -> row.getTimestamp("last_changed").toLocalDateTime(), caseId)
            .stream().findFirst();
    }
}
