package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRuleTest.answered;
import static uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRuleTest.noResponse;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The linking operation's wiring, with CCD and the database replaced: an in-memory attempt that obeys the real
 * transition rule, a binding that CCD's "commit" writes, and a mocked {@code CoreCaseDataApi}. The Docker-free
 * companion of {@code CcdLinkingScenariosTest}, one test per scenario it runs.
 */
class CcdLinkingServiceTest {

    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final String CASE_TYPE = "DEED_POLL_PLACEHOLDER";
    private static final long REFERENCE = 1_234_567_890_123_456L;
    private static final long OTHER_REFERENCE = 6_543_210_987_654_321L;

    private final AtomicReference<String> binding = new AtomicReference<>();
    private InMemoryAttempts attempts;
    private DeedPollRepository deedPolls;
    private CoreCaseDataApi ccd;
    private SystemUserTokens systemUser;
    private CcdLinkingService linking;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        attempts = new InMemoryAttempts();
        deedPolls = mock(DeedPollRepository.class);
        when(deedPolls.findCcdCaseProjectionById(RECORD))
            .thenReturn(Optional.of(new CcdCaseProjection(RECORD, "KBDP2026-000001")));
        when(deedPolls.findCcdCaseReference(RECORD)).thenAnswer(invocation -> Optional.ofNullable(binding.get()));
        ccd = mock(CoreCaseDataApi.class);
        when(ccd.startCase(anyString(), anyString(), eq(CASE_TYPE), eq(DeedPollCaseType.LINK_DEED_POLL)))
            .thenReturn(StartEventResponse.builder().token("token-1").build());
        systemUser = mock(SystemUserTokens.class);
        when(systemUser.accessToken()).thenReturn("Bearer system-user");
        AuthTokenGenerator s2s = () -> "Bearer s2s";
        ObjectProvider<AuthTokenGenerator> serviceTokens = mock(ObjectProvider.class);
        when(serviceTokens.getObject()).thenReturn(s2s);
        linking = new CcdLinkingService(attempts, deedPolls, ccd, systemUser, serviceTokens,
            new CcdProperties(CASE_TYPE, "DEED_POLL_PLACEHOLDER", "system-role"),
            new CcdLinkingProperties("worker-1", Duration.ofMinutes(5), Duration.ofMinutes(10), 3,
                Duration.ofMinutes(5)));
    }

    @Test
    @DisplayName("success: claims, starts, submits and confirms against the binding, then Linked")
    void success() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            return ours(REFERENCE);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.LINKED);

        assertThat(attempts.attempt.getStatus()).isEqualTo(Linked);
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isTrue();
        verify(ccd).submitCaseCreation(eq("Bearer system-user"), eq("Bearer s2s"), eq(CASE_TYPE), any());
        assertThat(linking.link(RECORD)).isEqualTo(Result.ALREADY_LINKED);
        verify(ccd, times(1)).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a lost response after commit is read back, never resubmitted")
    void lostResponse() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            throw noResponse();
        });
        when(ccd.getCase(anyString(), anyString(), eq(String.valueOf(REFERENCE)))).thenReturn(ours(REFERENCE));

        assertThat(linking.link(RECORD)).isEqualTo(Result.LINKED);
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isTrue();

        verify(ccd, times(1)).submitCaseCreation(anyString(), anyString(), anyString(), any());
        verify(ccd, times(1)).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a 5xx with nothing bound waits out the settle window, then is a repair item -- never a new start")
    void handlerRollback() {
        whenSubmitted(() -> {
            throw answered(500);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isTrue();
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("Submission outcome unknown: CCD answered HTTP 500");
        assertThat(linking.link(RECORD)).isEqualTo(Result.AWAITING_CONFIRMATION);

        attempts.attempt.setModifiedOn(AuditClock.now().minusDays(1));
        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(attempts.attempt.getStatus()).isEqualTo(NeedsRepair);
        verify(ccd, times(1)).startCase(anyString(), anyString(), anyString(), anyString());
        verify(ccd, never()).getCase(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a refusal for a record already bound is confirmed against that binding, not sent again")
    void conflictingExistingLink() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(OTHER_REFERENCE));
            throw answered(422);
        });
        when(ccd.getCase(anyString(), anyString(), eq(String.valueOf(OTHER_REFERENCE))))
            .thenReturn(ours(OTHER_REFERENCE));

        assertThat(linking.link(RECORD)).isEqualTo(Result.LINKED);
        assertThat(binding.get()).isEqualTo(String.valueOf(OTHER_REFERENCE));
    }

    @Test
    @DisplayName("a refused token with nothing bound goes round again once due, until the attempts are used")
    void tokenExpiry() {
        whenSubmitted(() -> {
            throw answered(403);
        });

        LocalDateTime before = AuditClock.now();
        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Pending);
        assertThat(attempts.attempt.getNotBefore()).isBetween(before.plusMinutes(5), AuditClock.now().plusMinutes(5));
        // Sent, then refused: it may have reached CCD, and the flag says so even though it went round again.
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isTrue();

        // Not yet due: nothing is claimed and nothing is sent.
        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getAttempts()).isOne();
        verify(ccd, times(1)).startCase(anyString(), anyString(), anyString(), anyString());

        makeDue();
        before = AuditClock.now();
        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        // The second release waits twice as long.
        assertThat(attempts.attempt.getNotBefore())
            .isBetween(before.plusMinutes(10), AuditClock.now().plusMinutes(10));
        makeDue();
        assertThat(linking.link(RECORD)).isEqualTo(Result.NEEDS_REPAIR);
        assertThat(attempts.attempt.getFailureDetail())
            .isEqualTo("Submission refused: CCD answered HTTP 403; no attempts left");
        assertThat(linking.link(RECORD)).isEqualTo(Result.NEEDS_REPAIR);
        verify(ccd, times(3)).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a claim another worker holds is left alone; CCD is not called")
    void anotherWorkersClaim() {
        attempts.open(RECORD);
        attempts.claim(attempts.attempt.getId(), "worker-2", Duration.ofMinutes(5));

        assertThat(linking.link(RECORD)).isEqualTo(Result.IN_PROGRESS);
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an expired claim goes to reconciliation, which starts nothing")
    void expiredClaim() {
        attempts.open(RECORD);
        attempts.claim(attempts.attempt.getId(), "worker-2", Duration.ofMinutes(5));
        attempts.attempt.setLeaseExpiresAt(AuditClock.now().minusMinutes(1));

        assertThat(linking.link(RECORD)).isEqualTo(Result.AWAITING_CONFIRMATION);
        assertThat(attempts.attempt.getStatus()).isEqualTo(AwaitingConfirmation);
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a failure before submission created nothing, and its detail names no credential")
    void failureBeforeSubmission() {
        when(systemUser.accessToken())
            .thenThrow(new IllegalStateException("The CCD system user's IDAM credentials are not configured"));

        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("Failed before submission: IllegalStateException");
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isFalse();
        assertThat(attempts.attempt.getNotBefore()).isAfter(AuditClock.now());
        verify(ccd, never()).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a refused start created nothing and submitted nothing, so the flag stays false")
    void startCaseRefused() {
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenThrow(answered(503));

        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("Failed before submission: CCD answered HTTP 503");
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isFalse();
        verify(ccd, never()).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a refused start whose claim was taken meanwhile releases nothing, and reports the attempt's status")
    void refusedStartOnALostClaimReleasesNothing() {
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            takenByReconciliation();
            throw answered(503);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.AWAITING_CONFIRMATION);
        assertThat(attempts.attempt.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("taken by reconciliation");
        verify(ccd, never()).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a stalled worker whose attempt was retried and claimed afresh by its own pod releases nothing")
    void staleWorkerCannotReleaseAFreshClaimAtTheSameTry() {
        String[] fresh = new String[1];
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            // Taken by reconciliation, repaired, retried from scratch -- tries back to zero -- and claimed again by
            // another thread of this pod, so at the same try and under the same worker name.
            takenByReconciliation();
            attempts.attempt.setStatus(NeedsRepair);
            attempts.attempt.setStatus(Pending);
            attempts.attempt.setAttempts(0);
            fresh[0] = CcdLinkingService.claimant("worker-1");
            attempts.claim(attempts.attempt.getId(), fresh[0], Duration.ofMinutes(5));
            throw answered(503);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.IN_PROGRESS);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Claimed);
        assertThat(attempts.attempt.getClaimedBy()).isEqualTo(fresh[0]).startsWith("worker-1#");
        assertThat(attempts.attempt.getAttempts()).isOne();
    }

    @Test
    @DisplayName("a stalled worker on its last try, whose attempt was retried and claimed afresh, repairs nothing")
    void staleWorkerOnItsLastTryCannotRepairAFreshClaim() {
        attempts.open(RECORD);
        attempts.attempt.setAttempts(2);
        String[] fresh = new String[1];
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            // Its third and last try stalls; meanwhile the attempt is taken, repaired, retried from scratch and
            // claimed again by another worker, which has now claimed its third try too.
            takenByReconciliation();
            attempts.attempt.setStatus(NeedsRepair);
            attempts.attempt.setStatus(Pending);
            attempts.attempt.setAttempts(2);
            fresh[0] = CcdLinkingService.claimant("worker-2");
            attempts.claim(attempts.attempt.getId(), fresh[0], Duration.ofMinutes(5));
            throw answered(503);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.IN_PROGRESS);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Claimed);
        assertThat(attempts.attempt.getClaimedBy()).isEqualTo(fresh[0]);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("taken by reconciliation");
        assertThat(attempts.attempt.getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a claim on a stale read keys its release on the try the claim made, not on the try it read")
    void claimOnAStaleReadUsesTheClaimedTry() {
        attempts.open(RECORD);
        // Read at no tries; before this worker claims it, another claims it and releases it, due at once.
        attempts.beforeNextClaim = () -> attempts.attempt.setAttempts(1);
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenThrow(answered(503));

        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Pending);
        assertThat(attempts.attempt.getAttempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a submission with no answer ends a sweep even when reconciliation repaired the claim meanwhile")
    void unansweredSubmissionOnARepairedClaimStillReportsCcdUnanswered() {
        whenSubmitted(() -> {
            takenByReconciliation();
            attempts.attempt.setStatus(NeedsRepair);
            throw noResponse();
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(attempts.attempt.getStatus()).isEqualTo(NeedsRepair);
    }

    @Test
    @DisplayName("a claim lost between the start and the flag write submits nothing, and records nothing")
    void claimLostBeforeTheFlagSubmitsNothing() {
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            takenByReconciliation();
            return StartEventResponse.builder().token("token-1").build();
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.AWAITING_CONFIRMATION);
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isFalse();
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("taken by reconciliation");
        verify(ccd, never()).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a claim whose lease ran out before the flag write submits nothing")
    void expiredLeaseBeforeTheFlagSubmitsNothing() {
        when(ccd.startCase(anyString(), anyString(), anyString(), anyString())).thenAnswer(invocation -> {
            attempts.attempt.setLeaseExpiresAt(AuditClock.now().minusSeconds(1));
            return StartEventResponse.builder().token("token-1").build();
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.IN_PROGRESS);
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isFalse();
        verify(ccd, never()).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a Pending attempt that is not yet due is left alone: no claim, no try counted, nothing sent")
    void notYetDueIsNotClaimed() {
        attempts.open(RECORD);
        attempts.attempt.setNotBefore(AuditClock.now().plusMinutes(1));

        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Pending);
        assertThat(attempts.attempt.getAttempts()).isZero();
        verify(systemUser, never()).accessToken();
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an IDAM refusal before submission is recorded as IDAM's or S2S's, never as CCD's")
    void tokenFailureIsNotBlamedOnCcd() {
        when(systemUser.accessToken()).thenThrow(answered(401));

        assertThat(linking.link(RECORD)).isEqualTo(Result.RETRY_LATER);
        assertThat(attempts.attempt.getFailureDetail())
            .isEqualTo("Failed before submission: IDAM or S2S answered HTTP 401");
        assertThat(attempts.attempt.isSubmissionMayHaveReached()).isFalse();
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a case CCD reports created, with nothing bound to the record, is a repair item and not Linked")
    void createdButNotBound() {
        whenSubmitted(() -> ours(REFERENCE));

        assertThat(linking.link(RECORD)).isEqualTo(Result.NEEDS_REPAIR);
        assertThat(attempts.attempt.getStatus()).isEqualTo(NeedsRepair);
        assertThat(attempts.attempt.getFailureDetail())
            .isEqualTo("CCD reported a case created, but no reference is bound to the record");
    }

    @Test
    @DisplayName("a worker whose attempt reconciliation took mid-call reports the attempt's status, and sends nothing")
    void workerThatLostItsAttemptReportsWhatItIsNow() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            takenByReconciliation();
            return ours(REFERENCE);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.AWAITING_CONFIRMATION);
        assertThat(attempts.attempt.getStatus()).isEqualTo(AwaitingConfirmation);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("taken by reconciliation");
        verify(ccd, times(1)).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a lost response to a worker that has lost its attempt too overwrites nothing, and sends nothing")
    void lostResponseAfterTheAttemptWasTaken() {
        whenSubmitted(() -> {
            takenByReconciliation();
            throw noResponse();
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("taken by reconciliation");
        verify(ccd, times(1)).submitCaseCreation(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("a bound record whose case CCD will not show is a repair item once the settle window has passed")
    void ccdUnreadablePastTheSettleWindow() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            throw noResponse();
        });
        when(ccd.getCase(anyString(), anyString(), anyString())).thenThrow(answered(503));

        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        attempts.attempt.setModifiedOn(AuditClock.now().minusDays(1));

        // Still CCD_UNANSWERED, though a person now has it: the read failed, and a sweep must stop on that.
        assertThat(linking.reconcileOne(attempts.attempt)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(attempts.attempt.getStatus()).isEqualTo(NeedsRepair);
        assertThat(attempts.attempt.getFailureDetail())
            .isEqualTo("CCD could not be read to confirm the record's binding within the settle window");
    }

    @Test
    @DisplayName("an unreadable CCD past the settle window stops the sweep even when another pod repairs first")
    void ccdUnreadablePastTheSettleWindowLosingTheRepair() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            throw noResponse();
        });
        when(ccd.getCase(anyString(), anyString(), anyString())).thenThrow(answered(503));
        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        attempts.attempt.setModifiedOn(AuditClock.now().minusDays(1));
        CcdCreationAttempt read = attempts.live(RECORD).orElseThrow();
        // Another pod's reconciliation read the same attempt and moved it first.
        attempts.move(read.getId(), AwaitingConfirmation, NeedsRepair, "another pod's repair");

        assertThat(linking.reconcileOne(read)).isEqualTo(Result.CCD_UNANSWERED);
        assertThat(attempts.attempt.getFailureDetail()).isEqualTo("another pod's repair");
    }

    @Test
    @DisplayName("a record bound between reconciliation's read and its repair stays awaiting, to be linked next time")
    void lateBindIsNotMarkedUnbound() {
        whenSubmitted(() -> {
            throw answered(500);
        });
        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        attempts.attempt.setModifiedOn(AuditClock.now().minusDays(1));
        // Reconciliation reads nothing bound; a late submission binds the record before it can mark the repair.
        when(deedPolls.findCcdCaseReference(RECORD)).thenAnswer(invocation -> {
            Optional<String> read = Optional.ofNullable(binding.get());
            binding.set(String.valueOf(REFERENCE));
            return read;
        });

        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(attempts.attempt.getStatus()).isEqualTo(AwaitingConfirmation);
    }

    @Test
    @DisplayName("a record another worker opened first is left to that worker")
    void losingTheOpen() {
        attempts.openedByAnotherWorker = true;

        assertThat(linking.link(RECORD)).isEqualTo(Result.IN_PROGRESS);
        assertThat(attempts.attempt.getClaimedBy()).isEqualTo("worker-2");
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("an attempt reconciliation cannot read does not keep the rest of the pass from being read")
    void oneUnreadableAttemptDoesNotStopThePass() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            throw noResponse();
        });
        when(ccd.getCase(anyString(), anyString(), anyString())).thenThrow(answered(503));
        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        // The same attempt listed twice stands for two: the first read fails outright, the second succeeds.
        attempts.listedTwice = true;
        when(deedPolls.findCcdCaseReference(RECORD))
            .thenThrow(new IllegalStateException("database unavailable"))
            .thenReturn(Optional.of(String.valueOf(REFERENCE)));
        // doReturn, because when(ccd.getCase(...)) would call the stub above and throw its 503.
        doReturn(ours(REFERENCE)).when(ccd).getCase(anyString(), anyString(), eq(String.valueOf(REFERENCE)));

        assertThat(linking.reconcileOutstanding()).isOne();
        assertThat(attempts.attempt.getStatus()).isEqualTo(Linked);
    }

    @Test
    @DisplayName("reconcileOne: an expired claim is taken to reconciliation and read, starting nothing")
    void reconcileOneTakesAnExpiredClaim() {
        attempts.open(RECORD);
        attempts.claim(attempts.attempt.getId(), "worker-2", Duration.ofMinutes(5));
        attempts.attempt.setLeaseExpiresAt(AuditClock.now().minusMinutes(1));

        assertThat(linking.reconcileOne(attempts.live(RECORD).orElseThrow()))
            .isEqualTo(Result.AWAITING_CONFIRMATION);
        assertThat(attempts.attempt.getStatus()).isEqualTo(AwaitingConfirmation);
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("reconcileOne: an attempt awaiting confirmation is confirmed against the binding and CCD")
    void reconcileOneConfirms() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            throw noResponse();
        });
        when(ccd.getCase(anyString(), anyString(), anyString())).thenThrow(answered(503));
        assertThat(linking.link(RECORD)).isEqualTo(Result.CCD_UNANSWERED);
        doReturn(ours(REFERENCE)).when(ccd).getCase(anyString(), anyString(), eq(String.valueOf(REFERENCE)));

        assertThat(linking.reconcileOne(attempts.live(RECORD).orElseThrow())).isEqualTo(Result.LINKED);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Linked);
    }

    @Test
    @DisplayName("reconcileOne: a claim read as expired but claimed afresh since is left to its worker")
    void reconcileOneLeavesAFreshClaim() {
        attempts.open(RECORD);
        attempts.claim(attempts.attempt.getId(), "worker-2", Duration.ofMinutes(5));
        CcdCreationAttempt readAsExpired = attempts.live(RECORD).orElseThrow();
        readAsExpired.setLeaseExpiresAt(AuditClock.now().minusMinutes(1));

        assertThat(linking.reconcileOne(readAsExpired)).isEqualTo(Result.IN_PROGRESS);
        assertThat(attempts.attempt.getStatus()).isEqualTo(Claimed);
        assertThat(attempts.attempt.getClaimedBy()).isEqualTo("worker-2");
    }

    @Test
    @DisplayName("reconcileOne: a failure is let through for the sweep to stop on, unlike reconcileOutstanding")
    void reconcileOneLetsAFailureThrough() {
        attempts.open(RECORD);
        attempts.attempt.setStatus(AwaitingConfirmation);
        when(deedPolls.findCcdCaseReference(RECORD)).thenThrow(new IllegalStateException("database unavailable"));

        CcdCreationAttempt waiting = attempts.live(RECORD).orElseThrow();
        assertThatThrownBy(() -> linking.reconcileOne(waiting)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("reconcileOne: an attempt in any other status is reported as it is now, and CCD is not called")
    void reconcileOneLeavesTheRestAlone() {
        attempts.open(RECORD);

        assertThat(linking.reconcileOne(attempts.live(RECORD).orElseThrow())).isEqualTo(Result.RETRY_LATER);
        verify(ccd, never()).startCase(anyString(), anyString(), anyString(), anyString());
        verify(ccd, never()).getCase(anyString(), anyString(), anyString());
    }

    /** Puts a released attempt's notBefore behind it, as time passing would. */
    private void makeDue() {
        attempts.attempt.setNotBefore(AuditClock.now().minusSeconds(1));
    }

    /** What reconciliation does to a claim it has read as expired, done while its worker is still talking to CCD. */
    private void takenByReconciliation() {
        attempts.attempt.setStatus(AwaitingConfirmation);
        attempts.attempt.setClaimedBy(null);
        attempts.attempt.setLeaseExpiresAt(null);
        attempts.attempt.setFailureDetail("taken by reconciliation");
    }

    @Test
    @DisplayName("a case CCD reports that contradicts the binding is a repair item at once")
    void contradicted() {
        whenSubmitted(() -> {
            binding.set(String.valueOf(REFERENCE));
            return ours(OTHER_REFERENCE);
        });

        assertThat(linking.link(RECORD)).isEqualTo(Result.NEEDS_REPAIR);
        assertThat(attempts.attempt.getFailureDetail())
            .isEqualTo("CCD reported a case that does not match the record's binding");
    }

    @Test
    @DisplayName("a failure is described by its status, never by the body it carried")
    void describesWithoutTheBody() {
        assertThat(CcdLinkingService.describe(answered(500))).isEqualTo("CCD answered HTTP 500")
            .doesNotContain("secret");
        assertThat(CcdLinkingService.describe(noResponse())).isEqualTo("no response from CCD");
    }

    @Test
    @DisplayName("an unknown record is reported, not opened")
    void unknownRecord() {
        assertThat(linking.link("not-a-record")).isEqualTo(Result.NO_SUCH_RECORD);
        assertThat(attempts.attempt).isNull();
    }

    private void whenSubmitted(java.util.function.Supplier<CaseDetails> ccdDoes) {
        when(ccd.submitCaseCreation(anyString(), anyString(), eq(CASE_TYPE), any(CaseDataContent.class)))
            .thenAnswer(invocation -> {
                CaseDataContent content = invocation.getArgument(3);
                assertThat(content.getEventToken()).isEqualTo("token-1");
                assertThat(content.getEvent().getId()).isEqualTo(DeedPollCaseType.LINK_DEED_POLL);
                return ccdDoes.get();
            });
    }

    private static CaseDetails ours(long reference) {
        return CaseDetails.builder().id(reference).caseTypeId(CASE_TYPE).data(Map.of("deedPollId", RECORD)).build();
    }

    /**
     * One record's attempts in memory, moving only as the real transition rule allows, and releasing a claim as
     * the real statements do.
     */
    private final class InMemoryAttempts extends CcdCreationAttempts {

        private CcdCreationAttempt attempt;

        /** The next open loses to another worker, which opens and claims the attempt first. */
        private boolean openedByAnotherWorker;

        /** Lists the one attempt awaiting confirmation twice, standing for two. */
        private boolean listedTwice;

        /** Runs once, just before the next claim: what another worker did after the caller read the attempt. */
        private Runnable beforeNextClaim;

        InMemoryAttempts() {
            super(null, null);
        }

        @Override
        public Optional<CcdCreationAttempt> open(String deedPollId) {
            if (live(deedPollId).isPresent()) {
                return Optional.empty();
            }
            attempt = new CcdCreationAttempt();
            attempt.setId(UUID.randomUUID());
            attempt.setDeedPollId(deedPollId);
            attempt.setStatus(Pending);
            attempt.setModifiedOn(AuditClock.now());
            if (openedByAnotherWorker) {
                claim(attempt.getId(), "worker-2", Duration.ofMinutes(5));
                return Optional.empty();
            }
            return Optional.of(snapshot());
        }

        @Override
        public Optional<CcdCreationAttempt> live(String deedPollId) {
            return Optional.ofNullable(attempt).filter(found -> found.getStatus() != Linked).map(found -> snapshot());
        }

        /** What a read hands back: a detached copy, which a later conditional UPDATE leaves as it was. */
        private CcdCreationAttempt snapshot() {
            CcdCreationAttempt copy = new CcdCreationAttempt();
            copy.setId(attempt.getId());
            copy.setDeedPollId(attempt.getDeedPollId());
            copy.setOperationId(attempt.getOperationId());
            copy.setStatus(attempt.getStatus());
            copy.setClaimedBy(attempt.getClaimedBy());
            copy.setLeaseExpiresAt(attempt.getLeaseExpiresAt());
            copy.setAttempts(attempt.getAttempts());
            copy.setFailureDetail(attempt.getFailureDetail());
            copy.setSubmissionMayHaveReached(attempt.isSubmissionMayHaveReached());
            copy.setNotBefore(attempt.getNotBefore());
            copy.setCreatedOn(attempt.getCreatedOn());
            copy.setModifiedOn(attempt.getModifiedOn());
            return copy;
        }

        @Override
        public List<CcdCreationAttempt> expiredClaims() {
            return live(RECORD).filter(found -> found.getStatus() == Claimed)
                .filter(found -> found.getLeaseExpiresAt().isBefore(AuditClock.now())).stream().toList();
        }

        @Override
        public List<CcdCreationAttempt> awaitingConfirmation() {
            List<CcdCreationAttempt> awaiting =
                live(RECORD).filter(found -> found.getStatus() == AwaitingConfirmation).stream().toList();
            return listedTwice ? List.of(awaiting.get(0), awaiting.get(0)) : awaiting;
        }

        @Override
        public boolean takeExpiredClaim(UUID attemptId, String failureDetail) {
            if (attempt == null || attempt.getStatus() != Claimed
                || !attempt.getLeaseExpiresAt().isBefore(AuditClock.now())) {
                return false;
            }
            return move(attemptId, Claimed, AwaitingConfirmation, failureDetail);
        }

        @Override
        public boolean repairIfUnbound(CcdCreationAttempt waiting, String failureDetail) {
            return binding.get() == null && move(waiting.getId(), AwaitingConfirmation, NeedsRepair, failureDetail);
        }

        @Override
        public OptionalInt claim(UUID attemptId, String worker, Duration lease) {
            if (beforeNextClaim != null) {
                Runnable meanwhile = beforeNextClaim;
                beforeNextClaim = null;
                meanwhile.run();
            }
            LocalDateTime now = AuditClock.now();
            if (attempt == null || attempt.getStatus() != Pending
                || !CcdLinkingRule.isDue(attempt.getNotBefore(), now)) {
                return OptionalInt.empty();
            }
            attempt.setStatus(Claimed);
            attempt.setClaimedBy(worker);
            attempt.setLeaseExpiresAt(now.plus(lease));
            attempt.setAttempts(attempt.getAttempts() + 1);
            attempt.setModifiedOn(now);
            return OptionalInt.of(attempt.getAttempts());
        }

        @Override
        public boolean recordSubmission(UUID attemptId, String worker, int tries) {
            if (attempt == null || attempt.getStatus() != Claimed || !worker.equals(attempt.getClaimedBy())
                || attempt.getAttempts() != tries || !attempt.getLeaseExpiresAt().isAfter(AuditClock.now())) {
                return false;
            }
            attempt.setSubmissionMayHaveReached(true);
            return true;
        }

        @Override
        public boolean release(UUID attemptId, String worker, int tries, String failureDetail,
                               LocalDateTime notBefore) {
            assertThat(notBefore).isNotNull();
            if (attempt == null || !worker.equals(attempt.getClaimedBy()) || attempt.getAttempts() != tries
                || !move(attemptId, Claimed, Pending, failureDetail)) {
                return false;
            }
            attempt.setNotBefore(notBefore);
            return true;
        }

        @Override
        public boolean moveClaim(UUID attemptId, String worker, int tries, CcdCreationAttemptStatus to,
                                 String failureDetail) {
            assertThat(to).isNotEqualTo(Pending);
            if (attempt == null || !worker.equals(attempt.getClaimedBy()) || attempt.getAttempts() != tries) {
                return false;
            }
            return move(attemptId, Claimed, to, failureDetail);
        }

        @Override
        public boolean move(UUID attemptId, CcdCreationAttemptStatus from, CcdCreationAttemptStatus to,
                            String failureDetail) {
            assertThat(from.canMoveTo(to)).as(from + " -> " + to).isTrue();
            if (attempt == null || attempt.getStatus() != from) {
                return false;
            }
            attempt.setStatus(to);
            attempt.setClaimedBy(null);
            attempt.setLeaseExpiresAt(null);
            attempt.setFailureDetail(failureDetail);
            attempt.setModifiedOn(AuditClock.now());
            return true;
        }
    }
}
