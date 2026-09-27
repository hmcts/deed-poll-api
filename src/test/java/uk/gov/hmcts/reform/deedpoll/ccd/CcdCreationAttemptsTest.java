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

        assertThat(attempts.claim(ATTEMPT, "worker-1", Duration.ofMinutes(5))).isTrue();

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
