package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdCreationAttempts;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * V22's creation-attempt ledger against real Postgres: one live attempt per record, and one winner per race.
 *
 * <p>Not {@code @Transactional}: what is being asserted is what separately committed transactions leave behind,
 * and a race inside one test transaction is no race. {@code CcdCreationAttemptStatusTest} and
 * {@code CcdCreationAttemptsTest} are the container-free companions over the rule these statements carry out.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CcdCreationAttemptRepositoryTest extends AbstractPostgresContainerTest {

    private static final int RACERS = 8;

    private static final int TIMEOUT_SECONDS = 60;

    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private CcdCreationAttempts attempts;

    @Autowired
    private CcdCreationAttemptRepository repository;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clearTables() {
        repository.deleteAll();
        deedPolls.deleteAll();
    }

    private String newCase() {
        return deedPolls.save(new DeedPoll()).getId();
    }

    private CcdCreationAttempt opened(String deedPollId) {
        return attempts.open(deedPollId).orElseThrow();
    }

    @Test
    @DisplayName("an attempt opens Pending, unclaimed, never tried, with an operation id of its own")
    void anAttemptOpensPending() {
        String deedPollId = newCase();

        CcdCreationAttempt attempt = opened(deedPollId);

        assertThat(attempt.getStatus()).isEqualTo(Pending);
        assertThat(attempt.getDeedPollId()).isEqualTo(deedPollId);
        assertThat(attempt.getOperationId()).isNotNull().isNotEqualTo(attempt.getId());
        assertThat(attempt.getClaimedBy()).isNull();
        assertThat(attempt.getLeaseExpiresAt()).isNull();
        assertThat(attempt.getAttempts()).isZero();
        assertThat(attempt.getCreatedOn()).isNotNull();
        assertThat(attempt.getModifiedOn()).isNotNull();
        assertThat(attempts.live(deedPollId)).map(CcdCreationAttempt::getId).contains(attempt.getId());
    }

    @Test
    @DisplayName("a second open while one is live opens nothing, and leaves the first alone")
    void secondOpenOpensNothing() {
        String deedPollId = newCase();
        CcdCreationAttempt first = opened(deedPollId);

        assertThat(attempts.open(deedPollId)).isEmpty();

        assertThat(repository.findByDeedPollIdOrderByCreatedOnAsc(deedPollId))
            .extracting(CcdCreationAttempt::getId).containsExactly(first.getId());
    }

    @Test
    @DisplayName("every live status blocks a second attempt, NeedsRepair included")
    void everyLiveStatusBlocksASecondAttempt() {
        String deedPollId = newCase();
        CcdCreationAttempt attempt = opened(deedPollId);
        assertThat(attempts.open(deedPollId)).as("Pending").isEmpty();

        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        assertThat(attempts.open(deedPollId)).as("Claimed").isEmpty();

        assertThat(attempts.move(attempt.getId(), Claimed, AwaitingConfirmation, null)).isTrue();
        assertThat(attempts.open(deedPollId)).as("AwaitingConfirmation").isEmpty();

        // The one that matters most: a record whose outcome is unknown must not be offered a fresh creation,
        // because the first may already have made a case in CCD.
        assertThat(attempts.move(attempt.getId(), AwaitingConfirmation, NeedsRepair, "no answer")).isTrue();
        assertThat(attempts.open(deedPollId)).as("NeedsRepair").isEmpty();
    }

    @Test
    @DisplayName("a Linked attempt is no longer live, so the record may have another")
    void linkedAttemptIsNotLive() {
        String deedPollId = newCase();
        CcdCreationAttempt first = opened(deedPollId);
        attempts.claim(first.getId(), "worker-1", LEASE);
        attempts.move(first.getId(), Claimed, Linked, null);

        assertThat(attempts.live(deedPollId)).isEmpty();
        CcdCreationAttempt second = opened(deedPollId);

        assertThat(second.getOperationId()).isNotEqualTo(first.getOperationId());
        assertThat(attempts.live(deedPollId)).map(CcdCreationAttempt::getId).contains(second.getId());
    }

    @Test
    @DisplayName("one record's live attempt does not block another record's")
    void liveIsPerRecord() {
        opened(newCase());

        assertThat(attempts.open(newCase())).isPresent();
    }

    @Test
    @DisplayName("a claim takes a Pending attempt once, counts the try and sets the lease")
    void claimIsExclusive() {
        CcdCreationAttempt attempt = opened(newCase());

        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isFalse();

        CcdCreationAttempt claimed = repository.findById(attempt.getId()).orElseThrow();
        assertThat(claimed.getStatus()).isEqualTo(Claimed);
        assertThat(claimed.getClaimedBy()).isEqualTo("worker-1");
        assertThat(claimed.getLeaseExpiresAt()).isAfter(claimed.getModifiedOn());
        assertThat(claimed.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("a released claim can be taken again once due, and the second try is counted too")
    void releasedClaimCountsAgain() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);

        assertThat(attempts.release(attempt.getId(), "CCD unavailable", AuditClock.now().minusSeconds(1))).isTrue();
        CcdCreationAttempt released = repository.findById(attempt.getId()).orElseThrow();
        assertThat(released.getStatus()).isEqualTo(Pending);
        assertThat(released.getClaimedBy()).isNull();
        assertThat(released.getLeaseExpiresAt()).isNull();
        assertThat(released.getFailureDetail()).isEqualTo("CCD unavailable");
        assertThat(released.getNotBefore()).isNotNull();

        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isTrue();
        assertThat(repository.findById(attempt.getId()).orElseThrow().getAttempts()).isEqualTo(2);
        // A release is from Claimed only, so a second one changes nothing.
        assertThat(attempts.release(attempt.getId(), "stale", AuditClock.now())).isTrue();
        assertThat(attempts.release(attempt.getId(), "stale again", AuditClock.now())).isFalse();
    }

    @Test
    @DisplayName("a released attempt is not claimed before its notBefore, and is claimed once it has passed")
    void releasedAttemptWaits() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        attempts.release(attempt.getId(), "refused", AuditClock.now().plusMinutes(5));

        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isFalse();
        CcdCreationAttempt waiting = repository.findById(attempt.getId()).orElseThrow();
        assertThat(waiting.getStatus()).isEqualTo(Pending);
        assertThat(waiting.getAttempts()).isOne();

        jdbc.update("UPDATE \"deedpoll_ccdcreationattempt\" SET \"notBefore\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusSeconds(1)), attempt.getId());
        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).isTrue();
    }

    @Test
    @DisplayName("the submission flag is written only for the claim's own worker, at its own try, under a live lease")
    void recordSubmissionIsConditional() {
        CcdCreationAttempt attempt = opened(newCase());
        UUID id = attempt.getId();
        assertThat(attempts.recordSubmission(id, "worker-1", 1)).as("not claimed").isFalse();
        attempts.claim(id, "worker-1", LEASE);

        assertThat(attempts.recordSubmission(id, "worker-2", 1)).as("another worker").isFalse();
        assertThat(attempts.recordSubmission(id, "worker-1", 2)).as("another try").isFalse();
        assertThat(repository.findById(id).orElseThrow().isSubmissionMayHaveReached()).isFalse();

        assertThat(attempts.recordSubmission(id, "worker-1", 1)).isTrue();
        assertThat(repository.findById(id).orElseThrow().isSubmissionMayHaveReached()).isTrue();

        // Released, the flag stays: it records the attempt's history, and nothing resets it.
        attempts.release(id, "refused", AuditClock.now().minusSeconds(1));
        assertThat(repository.findById(id).orElseThrow().isSubmissionMayHaveReached()).isTrue();
        attempts.claim(id, "worker-1", LEASE);
        assertThat(repository.findById(id).orElseThrow().isSubmissionMayHaveReached()).isTrue();

        jdbc.update("UPDATE \"deedpoll_ccdcreationattempt\" SET \"leaseExpiresAt\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusSeconds(1)), id);
        assertThat(attempts.recordSubmission(id, "worker-1", 2)).as("an expired lease").isFalse();
    }

    @Test
    @DisplayName("a move is conditional on the status the caller saw, so a stale caller changes nothing")
    void moveIsConditional() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        attempts.move(attempt.getId(), Claimed, AwaitingConfirmation, null);

        // A second worker that still believes the attempt is Claimed.
        assertThat(attempts.move(attempt.getId(), Claimed, NeedsRepair, "stale")).isFalse();

        CcdCreationAttempt unchanged = repository.findById(attempt.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(unchanged.getFailureDetail()).isNull();
    }

    @Test
    @DisplayName("an expired claim is taken for reconciliation, but a fresh claim in its place is not")
    void takingAnExpiredClaimSparesAFreshOne() {
        CcdCreationAttempt attempt = opened(newCase());
        attempts.claim(attempt.getId(), "worker-1", LEASE);

        // Read as expired by a reconciler that is slow to act on it; meanwhile the claim is live again.
        assertThat(attempts.takeExpiredClaim(attempt.getId(), "expired")).isFalse();
        assertThat(repository.findById(attempt.getId()).orElseThrow().getClaimedBy()).isEqualTo("worker-1");

        jdbc.update("UPDATE \"deedpoll_ccdcreationattempt\" SET \"leaseExpiresAt\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusMinutes(1)), attempt.getId());
        assertThat(attempts.takeExpiredClaim(attempt.getId(), "expired")).isTrue();
        CcdCreationAttempt taken = repository.findById(attempt.getId()).orElseThrow();
        assertThat(taken.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(taken.getClaimedBy()).isNull();
        assertThat(taken.getLeaseExpiresAt()).isNull();
        assertThat(taken.getFailureDetail()).isEqualTo("expired");
    }

    @Test
    @DisplayName("an unbound attempt becomes a repair item, but not once its record is bound")
    void repairIfUnboundReadsTheBinding() {
        String unbound = newCase();
        CcdCreationAttempt first = awaiting(unbound);
        assertThat(attempts.repairIfUnbound(first, "nothing bound")).isTrue();
        assertThat(repository.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(NeedsRepair);

        String bound = newCase();
        CcdCreationAttempt second = awaiting(bound);
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"ccdCaseReference\" = '1234567890123456' WHERE \"id\" = ?",
            bound);
        assertThat(attempts.repairIfUnbound(second, "nothing bound")).isFalse();
        assertThat(repository.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(AwaitingConfirmation);
    }

    @Test
    @DisplayName("the sweep selects unlinked records with no live attempt or a due Pending one, oldest first")
    void dueForLinkingSelectsOnlyDueWork() {
        String bound = caseCreatedDaysAgo(9);
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"ccdCaseReference\" = '1234567890123456' WHERE \"id\" = ?",
            bound);
        final String fresh = caseCreatedDaysAgo(8);
        String notDue = caseCreatedDaysAgo(7);
        CcdCreationAttempt waiting = opened(notDue);
        attempts.claim(waiting.getId(), "worker-1", LEASE);
        attempts.release(waiting.getId(), "refused", AuditClock.now().plusMinutes(5));
        String due = caseCreatedDaysAgo(6);
        CcdCreationAttempt released = opened(due);
        attempts.claim(released.getId(), "worker-1", LEASE);
        attempts.release(released.getId(), "refused", AuditClock.now().minusSeconds(1));
        String claimed = caseCreatedDaysAgo(5);
        attempts.claim(opened(claimed).getId(), "worker-1", LEASE);
        awaiting(caseCreatedDaysAgo(4));
        CcdCreationAttempt stuck = awaiting(caseCreatedDaysAgo(3));
        attempts.move(stuck.getId(), AwaitingConfirmation, NeedsRepair, "stuck");
        String opened = caseCreatedDaysAgo(2);
        opened(opened);
        String newest = caseCreatedDaysAgo(1);

        assertThat(attempts.dueForLinking(10)).containsExactly(fresh, due, opened, newest);
        assertThat(attempts.dueForLinking(2)).containsExactly(fresh, due);
    }

    @Test
    @DisplayName("linking rows while a sweep runs skips none: the selection is always the head, never an offset")
    void dueForLinkingNeverSkips() {
        String first = caseCreatedDaysAgo(3);
        String second = caseCreatedDaysAgo(2);
        String third = caseCreatedDaysAgo(1);
        assertThat(attempts.dueForLinking(2)).containsExactly(first, second);

        // The first is claimed by this sweep; a second page by offset would now start at nothing.
        attempts.claim(opened(first).getId(), "worker-1", LEASE);
        assertThat(attempts.dueForLinking(2)).containsExactly(second, third);

        // The second is bound by another pod's sweep: the third is next, not skipped.
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"ccdCaseReference\" = '1234567890123456' WHERE \"id\" = ?",
            second);
        assertThat(attempts.dueForLinking(2)).containsExactly(third);
    }

    @Test
    @DisplayName("ties on createdOn are broken by id, so the order is total and repeatable")
    void dueForLinkingBreaksTiesById() {
        String one = caseCreatedDaysAgo(1);
        String other = caseCreatedDaysAgo(1);
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"createdOn\" = (SELECT \"createdOn\" FROM \"deedpoll_deedpoll\""
            + " WHERE \"id\" = ?) WHERE \"id\" = ?", one, other);

        assertThat(attempts.dueForLinking(2)).containsExactly(one.compareTo(other) < 0
            ? new String[] {one, other} : new String[] {other, one});
    }

    @Test
    @DisplayName("the sweep's outstanding attempts are the expired claims first, then those awaiting, bounded")
    void outstandingIsBounded() {
        CcdCreationAttempt olderWait = awaiting(newCase());
        jdbc.update("UPDATE \"deedpoll_ccdcreationattempt\" SET \"modifiedOn\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusHours(1)), olderWait.getId());
        final CcdCreationAttempt newerWait = awaiting(newCase());
        CcdCreationAttempt expired = opened(newCase());
        attempts.claim(expired.getId(), "worker-1", LEASE);
        jdbc.update("UPDATE \"deedpoll_ccdcreationattempt\" SET \"leaseExpiresAt\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusMinutes(1)), expired.getId());
        attempts.claim(opened(newCase()).getId(), "worker-1", LEASE);

        assertThat(attempts.outstanding(5)).extracting(CcdCreationAttempt::getId)
            .containsExactly(expired.getId(), olderWait.getId(), newerWait.getId());
        assertThat(attempts.outstanding(2)).extracting(CcdCreationAttempt::getId)
            .containsExactly(expired.getId(), olderWait.getId());
        assertThat(attempts.outstanding(1)).extracting(CcdCreationAttempt::getId)
            .containsExactly(expired.getId());
    }

    /**
     * A record whose createdOn is {@code days} ago, so the sweep's order is the test's to choose.
     */
    private String caseCreatedDaysAgo(int days) {
        String id = newCase();
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"createdOn\" = ? WHERE \"id\" = ?",
            Timestamp.valueOf(AuditClock.now().minusDays(days)), id);
        return id;
    }

    private CcdCreationAttempt awaiting(String deedPollId) {
        CcdCreationAttempt attempt = opened(deedPollId);
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        attempts.move(attempt.getId(), Claimed, AwaitingConfirmation, null);
        return repository.findById(attempt.getId()).orElseThrow();
    }

    /** An attempt taken to NeedsRepair, with its submission flag set as asked, through the statements that do so. */
    private CcdCreationAttempt needingRepair(String deedPollId, boolean submitted) {
        CcdCreationAttempt attempt = opened(deedPollId);
        assertThat(attempts.claim(attempt.getId(), "worker-1", LEASE)).isTrue();
        if (submitted) {
            assertThat(attempts.recordSubmission(attempt.getId(), "worker-1", 1)).isTrue();
        }
        assertThat(attempts.move(attempt.getId(), Claimed, NeedsRepair, "CCD refused the submission (422)")).isTrue();
        return repository.findById(attempt.getId()).orElseThrow();
    }

    @Test
    @DisplayName("reconcile again moves only NeedsRepair, attributed, keeping why it needed repair")
    void reconcileAgainIsConditionalAndAttributed() {
        CcdCreationAttempt attempt = needingRepair(newCase(), true);

        assertThat(attempts.reconcileAgain(attempt.getId(), "idam-admin")).isTrue();
        assertThat(attempts.reconcileAgain(attempt.getId(), "idam-admin")).as("no longer NeedsRepair").isFalse();

        CcdCreationAttempt repaired = repository.findById(attempt.getId()).orElseThrow();
        assertThat(repaired.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(repaired.getRepairedById()).isEqualTo("idam-admin");
        assertThat(repaired.getRepairedOn()).isNotNull();
        assertThat(repaired.getModifiedOn()).isAfterOrEqualTo(attempt.getModifiedOn());
        assertThat(repaired.getFailureDetail()).isEqualTo("CCD refused the submission (422)");
        assertThat(repaired.isSubmissionMayHaveReached()).isTrue();
        assertThat(repaired.getOperationId()).isEqualTo(attempt.getOperationId());
        assertThat(deedPolls.findById(attempt.getDeedPollId()).orElseThrow().getCcdCaseReference()).isNull();
    }

    @Test
    @DisplayName("a retry from scratch resets the tries and is due now, attributed, keeping the operation id")
    void retryFromScratchResets() {
        CcdCreationAttempt attempt = needingRepair(newCase(), false);

        assertThat(attempts.retryFromScratch(attempt.getId(), "idam-admin")).isTrue();
        assertThat(attempts.retryFromScratch(attempt.getId(), "idam-admin")).as("no longer NeedsRepair").isFalse();

        CcdCreationAttempt retried = repository.findById(attempt.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(Pending);
        assertThat(retried.getAttempts()).isZero();
        assertThat(retried.getNotBefore()).isNotNull().isBeforeOrEqualTo(AuditClock.now());
        assertThat(retried.getClaimedBy()).isNull();
        assertThat(retried.getLeaseExpiresAt()).isNull();
        assertThat(retried.getRepairedById()).isEqualTo("idam-admin");
        assertThat(retried.getRepairedOn()).isNotNull();
        assertThat(retried.getOperationId()).isEqualTo(attempt.getOperationId());
        assertThat(deedPolls.findById(attempt.getDeedPollId()).orElseThrow().getCcdCaseReference()).isNull();
        assertThat(attempts.claim(attempt.getId(), "worker-2", LEASE)).as("due at once").isTrue();
    }

    @Test
    @DisplayName("the statement itself refuses a retry from scratch once a submission may have reached CCD")
    void retryFromScratchRefusedInSql() {
        CcdCreationAttempt attempt = needingRepair(newCase(), true);

        // The repository directly, past the service's own check: the SQL must refuse on its own.
        Integer moved = new TransactionTemplate(transactionManager)
            .execute(status -> repository.retryFromScratch(attempt.getId(), "idam-admin", AuditClock.now()));
        assertThat(moved).isZero();

        CcdCreationAttempt untouched = repository.findById(attempt.getId()).orElseThrow();
        assertThat(untouched.getStatus()).isEqualTo(NeedsRepair);
        assertThat(untouched.getAttempts()).isEqualTo(1);
        assertThat(untouched.getRepairedById()).isNull();
    }

    @Test
    @DisplayName("a repair leaves a bound record's reference as it was")
    void repairTouchesNoBinding() {
        String deedPollId = newCase();
        CcdCreationAttempt attempt = needingRepair(deedPollId, false);
        jdbc.update("UPDATE \"deedpoll_deedpoll\" SET \"ccdCaseReference\" = ? WHERE \"id\" = ?", "1234567890123456",
            deedPollId);

        assertThat(attempts.retryFromScratch(attempt.getId(), "idam-admin")).isTrue();

        assertThat(deedPolls.findById(deedPollId).orElseThrow().getCcdCaseReference()).isEqualTo("1234567890123456");
    }

    @Test
    @DisplayName("concurrent repairs of one attempt make exactly one move")
    void concurrentRepairsMakeOneMove() throws Exception {
        CcdCreationAttempt attempt = needingRepair(newCase(), false);

        List<Boolean> results = race(worker -> worker % 2 == 0
            ? attempts.reconcileAgain(attempt.getId(), "idam-admin-" + worker)
            : attempts.retryFromScratch(attempt.getId(), "idam-admin-" + worker));

        assertThat(results).containsOnlyOnce(true);
        assertThat(repository.findById(attempt.getId()).orElseThrow().getRepairedById()).startsWith("idam-admin-");
    }

    @Test
    @DisplayName("the repair view reads counts per status, the unlinked attempts a page at a time, and one by id")
    void theRepairViewReads() {
        DeedPoll referenced = new DeedPoll();
        referenced.setDisplayReference("KBDP2026-000042");
        String first = deedPolls.save(referenced).getId();
        CcdCreationAttempt repair = needingRepair(first, true);
        CcdCreationAttempt pending = opened(newCase());
        CcdCreationAttempt linked = opened(newCase());
        assertThat(attempts.claim(linked.getId(), "worker-1", LEASE)).isTrue();
        assertThat(attempts.move(linked.getId(), Claimed, Linked, null)).isTrue();

        assertThat(attempts.countsByStatus())
            .containsEntry(NeedsRepair, 1L)
            .containsEntry(Pending, 1L)
            .containsEntry(Linked, 1L)
            .containsEntry(Claimed, 0L)
            .containsEntry(AwaitingConfirmation, 0L);

        var all = attempts.unlinked(Optional.empty(), PageRequest.of(0, 1));
        assertThat(all.getTotalElements()).isEqualTo(2);
        assertThat(all.getContent()).hasSize(1);
        var second = attempts.unlinked(Optional.empty(), PageRequest.of(1, 1));
        assertThat(List.of(all.getContent().get(0).id(), second.getContent().get(0).id()))
            .containsExactlyInAnyOrder(repair.getId(), pending.getId());

        var narrowed = attempts.unlinked(Optional.of(NeedsRepair), PageRequest.of(0, 10));
        assertThat(narrowed.getContent()).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(repair.getId());
            assertThat(row.displayReference()).isEqualTo("KBDP2026-000042");
            assertThat(row.submissionMayHaveReached()).isTrue();
            assertThat(row.attempts()).isEqualTo(1);
        });
        assertThat(attempts.unlinked(Optional.of(Linked), PageRequest.of(0, 10))
                       .getContent()).extracting(CcdAttemptRow::id).containsExactly(linked.getId());
        assertThat(attempts.row(pending.getId())).map(CcdAttemptRow::status).contains(Pending);
        assertThat(attempts.row(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("concurrent claims on one attempt yield exactly one winner")
    void concurrentClaimsYieldOneWinner() throws Exception {
        CcdCreationAttempt attempt = opened(newCase());

        List<Boolean> results = race(worker -> attempts.claim(attempt.getId(), "worker-" + worker, LEASE));

        assertThat(results).containsOnlyOnce(true);
        CcdCreationAttempt claimed = repository.findById(attempt.getId()).orElseThrow();
        assertThat(claimed.getAttempts()).isEqualTo(1);
        assertThat(claimed.getClaimedBy()).startsWith("worker-");
    }

    @Test
    @DisplayName("concurrent opens on one record yield exactly one live attempt")
    void concurrentOpensYieldOneAttempt() throws Exception {
        String deedPollId = newCase();

        List<Boolean> results = race(worker -> attempts.open(deedPollId).isPresent());

        assertThat(results).containsOnlyOnce(true);
        assertThat(repository.findByDeedPollIdOrderByCreatedOnAsc(deedPollId)).hasSize(1);
    }

    @Test
    @DisplayName("the database itself refuses a second live row, whatever the application thinks")
    void theIndexRefusesASecondLiveRow() {
        String deedPollId = newCase();
        opened(deedPollId);

        assertThatThrownBy(() -> insertRaw(deedPollId, "NeedsRepair", null, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_live_key");
    }

    @Test
    @DisplayName("the database refuses a Claimed row without a claimant and a lease")
    void theDatabaseRefusesAClaimWithoutALease() {
        String deedPollId = newCase();

        assertThatThrownBy(() -> insertRaw(deedPollId, "Claimed", null, null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_claim_has_lease");
        assertThatThrownBy(() -> insertRaw(deedPollId, "Claimed", "worker-1", null))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasMessageContaining("deedpoll_ccdcreationattempt_claim_has_lease");
    }

    @Test
    @DisplayName("the database refuses a failure detail over its bound, which is why the service cuts it first")
    void theDatabaseBoundsTheFailureDetail() {
        CcdCreationAttempt attempt = opened(newCase());
        String tooLong = "x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL + 1);

        assertThatThrownBy(() -> jdbc.update(
            "UPDATE \"deedpoll_ccdcreationattempt\" SET \"failureDetail\" = ? WHERE \"id\" = ?", tooLong,
            attempt.getId()))
            .isInstanceOf(DataIntegrityViolationException.class);

        // Through the service the same text is written, cut to fit.
        attempts.claim(attempt.getId(), "worker-1", LEASE);
        assertThat(attempts.release(attempt.getId(), tooLong, AuditClock.now())).isTrue();
        assertThat(repository.findById(attempt.getId()).orElseThrow().getFailureDetail())
            .hasSize(CcdCreationAttempt.MAX_FAILURE_DETAIL);
    }

    @Test
    @DisplayName("an attempt for a record that does not exist is refused")
    void anAttemptNeedsARecord() {
        assertThatThrownBy(() -> attempts.open("no-such-case"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("deleting the record deletes its attempts")
    void deletingTheRecordDeletesItsAttempts() {
        String deedPollId = newCase();
        CcdCreationAttempt attempt = opened(deedPollId);

        jdbc.update("DELETE FROM \"deedpoll_deedpoll\" WHERE \"id\" = ?", deedPollId);

        assertThat(repository.findById(attempt.getId())).isEqualTo(Optional.empty());
    }

    private void insertRaw(String deedPollId, String status, String claimedBy, Timestamp lease) {
        jdbc.update("""
            INSERT INTO "deedpoll_ccdcreationattempt"
                ("id", "deedPollId", "operationId", "status", "claimedBy", "leaseExpiresAt", "modifiedOn")
            VALUES (?, ?, ?, CAST(? AS "CcdCreationAttemptStatus"), ?, ?, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), deedPollId, UUID.randomUUID(), status, claimedBy, lease);
    }

    /** Runs one call per racer, all released at once, each in its own committed transaction. */
    private List<Boolean> race(Racer racer) throws Exception {
        CountDownLatch ready = new CountDownLatch(RACERS);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(RACERS);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int worker = 0; worker < RACERS; worker++) {
                int id = worker;
                Callable<Boolean> call = () -> {
                    ready.countDown();
                    if (!go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("the race never started");
                    }
                    return racer.run(id);
                };
                futures.add(pool.submit(call));
            }
            assertThat(ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> future : futures) {
                // Surfaces any racer's exception here rather than as an unexplained count.
                results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Racer {
        boolean run(int worker);
    }
}
