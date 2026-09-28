package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The ledger's own decisions, taken before any statement reaches the database.
 *
 * <p>The container-free companion of {@code CcdCreationAttemptRepositoryTest}: which of two racing callers wins is
 * the conditional statement's answer and is proved against Postgres there; that an illegal move never issues a
 * statement at all is proved here, where CI runs it.
 */
class CcdCreationAttemptsTest {

    private static final UUID ATTEMPT = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private CcdCreationAttemptRepository repository;

    private DeedPollRepository deedPolls;

    private CcdCreationAttempts attempts;

    @BeforeEach
    void setUp() {
        repository = mock(CcdCreationAttemptRepository.class);
        deedPolls = mock(DeedPollRepository.class);
        attempts = new CcdCreationAttempts(repository, deedPolls);
    }

    @Test
    @DisplayName("every move the rule refuses throws, and issues no statement")
    void refusedMovesIssueNoStatement() {
        for (CcdCreationAttemptStatus from : CcdCreationAttemptStatus.values()) {
            for (CcdCreationAttemptStatus to : CcdCreationAttemptStatus.values()) {
                if (!from.canMoveTo(to)) {
                    assertThatThrownBy(() -> attempts.move(ATTEMPT, from, to, null))
                        .as("%s -> %s", from, to)
                        .isInstanceOf(IllegalStateException.class);
                }
            }
        }
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("Claimed is reached only through claim(), even from a status that may move to it")
    void claimedOnlyThroughClaim() {
        assertThat(Pending.canMoveTo(Claimed)).isTrue();

        assertThatThrownBy(() -> attempts.move(ATTEMPT, Pending, Claimed, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("claim()");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("NeedsRepair -> Pending is refused here, because this statement cannot check nothing was submitted")
    void retryFromScratchIsNotAPlainMove() {
        assertThat(NeedsRepair.canMoveTo(Pending)).isTrue();

        assertThatThrownBy(() -> attempts.move(ATTEMPT, NeedsRepair, Pending, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("nothing having been submitted");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("NeedsRepair -> AwaitingConfirmation is refused here, because only reconcileAgain() attributes it")
    void reconcileAgainIsNotAPlainMove() {
        assertThat(NeedsRepair.canMoveTo(AwaitingConfirmation)).isTrue();

        assertThatThrownBy(() -> attempts.move(ATTEMPT, NeedsRepair, AwaitingConfirmation, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("reconcileAgain()");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a repair with nobody to attribute it to is refused, and issues no statement")
    void repairMustBeAttributed() {
        for (String nobody : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> attempts.reconcileAgain(ATTEMPT, nobody))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> attempts.retryFromScratch(ATTEMPT, nobody))
                .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("each repair is one conditional statement, attributed, and reports whether it won")
    void repairsAreConditionalAndAttributed() {
        when(repository.reconcileAgain(eq(ATTEMPT), eq("idam-admin"), any())).thenReturn(1, 0);
        when(repository.retryFromScratch(eq(ATTEMPT), eq("idam-admin"), any())).thenReturn(1, 0);

        assertThat(attempts.reconcileAgain(ATTEMPT, "idam-admin")).isTrue();
        assertThat(attempts.reconcileAgain(ATTEMPT, "idam-admin")).isFalse();
        assertThat(attempts.retryFromScratch(ATTEMPT, "idam-admin")).isTrue();
        assertThat(attempts.retryFromScratch(ATTEMPT, "idam-admin")).isFalse();
        verify(repository, never()).move(any(), anyString(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the counts name every status, zero for one no attempt is in")
    void countsNameEveryStatus() {
        when(repository.countByStatus()).thenReturn(List.of(
            new Object[] {NeedsRepair, 3L}, new Object[] {Linked, 12L}));

        assertThat(attempts.countsByStatus())
            .containsExactly(
                Map.entry(Pending, 0L),
                Map.entry(Claimed, 0L),
                Map.entry(AwaitingConfirmation, 0L),
                Map.entry(Linked, 12L),
                Map.entry(NeedsRepair, 3L));
    }

    @Test
    @DisplayName("Claimed -> Pending is refused here, because only release() says when the attempt is next due")
    void releaseIsNotAPlainMove() {
        assertThat(Claimed.canMoveTo(Pending)).isTrue();

        assertThatThrownBy(() -> attempts.move(ATTEMPT, Claimed, Pending, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("release()");
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a release must say when the attempt is next due, and bounds its detail like a move")
    void releaseSetsWhenNextDue() {
        assertThatThrownBy(() -> attempts.release(ATTEMPT, "worker-1", 1, "refused", null))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);

        LocalDateTime due = LocalDateTime.of(2026, 9, 27, 12, 5);
        String verbose = "x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL + 1);
        when(repository.release(eq(ATTEMPT), eq("worker-1"), eq(1), anyString(), eq(due), any()))
            .thenReturn(1, 0);

        assertThat(attempts.release(ATTEMPT, "worker-1", 1, verbose, due)).isTrue();
        assertThat(attempts.release(ATTEMPT, "worker-1", 1, verbose, due)).isFalse();
        verify(repository, times(2)).release(eq(ATTEMPT), eq("worker-1"), eq(1),
            eq("x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL)),
            eq(due), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("the submission flag is written for this worker's claim at this try, and reports whether it held")
    void recordSubmissionIsConditionalOnTheClaim() {
        when(repository.recordSubmission(eq(ATTEMPT), eq("worker-1"), eq(2), any())).thenReturn(1);

        assertThat(attempts.recordSubmission(ATTEMPT, "worker-1", 2)).isTrue();
        assertThat(attempts.recordSubmission(ATTEMPT, "worker-1", 1)).isFalse();
        assertThat(attempts.recordSubmission(ATTEMPT, "worker-2", 2)).isFalse();
    }

    @Test
    @DisplayName("a worker moves its own claim on only at this try, never back to Pending, and bounds its detail")
    void moveClaimIsConditionalOnTheClaim() {
        assertThatThrownBy(() -> attempts.moveClaim(ATTEMPT, "worker-1", 1, Pending, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("release()");
        assertThatThrownBy(() -> attempts.moveClaim(ATTEMPT, "worker-1", 1, Claimed, null))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(repository);

        String verbose = "x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL + 1);
        when(repository.moveClaim(eq(ATTEMPT), eq("worker-1"), eq(2), eq("NeedsRepair"),
            eq("x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL)), any())).thenReturn(1);

        assertThat(attempts.moveClaim(ATTEMPT, "worker-1", 2, NeedsRepair, verbose)).isTrue();
        assertThat(attempts.moveClaim(ATTEMPT, "worker-1", 1, NeedsRepair, verbose)).isFalse();
        assertThat(attempts.moveClaim(ATTEMPT, "worker-2", 2, NeedsRepair, verbose)).isFalse();
    }

    @Test
    @DisplayName("an allowed move is conditional on the status the caller saw, and reports whether it won")
    void anAllowedMoveIsConditional() {
        when(repository.move(eq(ATTEMPT), eq("Claimed"), eq("AwaitingConfirmation"), isNull(), any()))
            .thenReturn(1);
        when(repository.move(eq(ATTEMPT), eq("AwaitingConfirmation"), eq("Linked"), isNull(), any()))
            .thenReturn(0);

        assertThat(attempts.move(ATTEMPT, Claimed, AwaitingConfirmation, null)).isTrue();
        assertThat(attempts.move(ATTEMPT, AwaitingConfirmation, Linked, null)).isFalse();
    }

    @Test
    @DisplayName("the failure detail is cut to the column's bound before it is written")
    void theFailureDetailIsBoundedBeforeTheWrite() {
        String verbose = "x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL + 500);

        attempts.move(ATTEMPT, AwaitingConfirmation, NeedsRepair, verbose);

        verify(repository).move(eq(ATTEMPT), eq("AwaitingConfirmation"), eq("NeedsRepair"),
                                eq("x".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL)), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("bounded leaves null and short details alone")
    void boundedLeavesShortDetailsAlone() {
        String exact = "y".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL);

        assertThat(CcdCreationAttempts.bounded(null)).isNull();
        assertThat(CcdCreationAttempts.bounded("timed out")).isEqualTo("timed out");
        assertThat(CcdCreationAttempts.bounded(exact)).isSameAs(exact);
    }

    @Test
    @DisplayName("bounded never cuts a surrogate pair in half")
    void boundedNeverSplitsASurrogatePair() {
        // The cut falls between the two halves of the emoji, so it steps back one and drops the whole pair.
        String detail = "z".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL - 1) + "😀" + "tail";

        String bounded = CcdCreationAttempts.bounded(detail);

        assertThat(bounded).isEqualTo("z".repeat(CcdCreationAttempt.MAX_FAILURE_DETAIL - 1));
        assertThat(Character.isHighSurrogate(bounded.charAt(bounded.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("a claim needs a positive lease, because a lease that has already expired is no claim")
    void claimNeedsPositiveLease() {
        assertThatThrownBy(() -> attempts.claim(ATTEMPT, "worker-1", Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> attempts.claim(ATTEMPT, "worker-1", Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("a claim's lease runs from the moment it is taken")
    void claimLeaseRunsFromNow() {
        when(repository.claim(eq(ATTEMPT), eq("worker-1"), any(), any())).thenReturn(1);
        when(repository.findTryOfClaim(ATTEMPT, "worker-1")).thenReturn(Optional.of(3));

        assertThat(attempts.claim(ATTEMPT, "worker-1", Duration.ofMinutes(5))).hasValue(3);

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> until = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).claim(eq(ATTEMPT), eq("worker-1"), now.capture(), until.capture());
        assertThat(until.getValue()).isEqualTo(now.getValue().plusMinutes(5));
    }

    @Test
    @DisplayName("open answers empty, without reading, when another attempt is live")
    void openAnswersEmptyWhenAnotherIsLive() {
        when(repository.open(any(), anyString(), any(), any())).thenReturn(0);

        assertThat(attempts.open("case-1")).isEmpty();
        verify(repository).open(any(), eq("case-1"), any(), any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("open mints a fresh operation id for every attempt")
    void openMintsAFreshOperationId() {
        CcdCreationAttempt opened = new CcdCreationAttempt();
        when(repository.open(any(), anyString(), any(), any())).thenReturn(1);
        when(repository.findById(any())).thenReturn(Optional.of(opened));

        assertThat(attempts.open("case-1")).containsSame(opened);
        assertThat(attempts.open("case-2")).containsSame(opened);

        ArgumentCaptor<UUID> operationIds = ArgumentCaptor.forClass(UUID.class);
        verify(repository, times(2)).open(any(), anyString(), operationIds.capture(), any());
        assertThat(operationIds.getAllValues()).doesNotHaveDuplicates().doesNotContainNull();
    }

    @Test
    @DisplayName("repairIfUnbound locks the record, and moves nothing once it is bound")
    void repairIfUnboundReadsTheBindingUnderTheLock() {
        CcdCreationAttempt waiting = new CcdCreationAttempt();
        waiting.setId(ATTEMPT);
        waiting.setDeedPollId("case-1");
        DeedPoll bound = new DeedPoll();
        bound.setCcdCaseReference("1234567890123456");
        when(deedPolls.findByIdForUpdate("case-1")).thenReturn(Optional.of(bound));

        assertThat(attempts.repairIfUnbound(waiting, "nothing bound")).isFalse();
        verify(repository, never()).move(any(), anyString(), anyString(), any(), any());

        when(deedPolls.findByIdForUpdate("case-1")).thenReturn(Optional.of(new DeedPoll()));
        when(repository.move(eq(ATTEMPT), eq("AwaitingConfirmation"), eq("NeedsRepair"), eq("nothing bound"), any()))
            .thenReturn(1);
        assertThat(attempts.repairIfUnbound(waiting, "nothing bound")).isTrue();
    }
}
