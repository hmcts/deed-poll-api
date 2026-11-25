package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository.CcdCaseState;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DeletionProgress}'s rules with no database: what each inventory and CCD state means for the request, and
 * that nothing it decides is {@link DeletionStatus#Completed}.
 */
class DeletionProgressTest {

    private static final Duration BASE = Duration.ofMinutes(1);
    private static final Duration POLL = Duration.ofHours(1);
    private final DeletionProgress progress = new DeletionProgress(3, BASE, POLL);

    private static DeletionStep step(DeletionStepKind kind, DeletionStepState state, int attempts) {
        return new DeletionStep(UUID.randomUUID(), UUID.randomUUID(), kind, "item", "locator", state, null, attempts,
                                state == DeletionStepState.Done ? LocalDateTime.now() : null);
    }

    private static final Optional<CcdCaseState> NO_CASE = Optional.empty();
    private static final Optional<DeletionFailure> NOTHING = Optional.empty();

    @Test
    @DisplayName("an unlinked case whose files are all removed has only finalisation left, and is not completed")
    void unlinkedDone() {
        var decision = progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Done, 0),
            step(DeletionStepKind.OWNED_STAGE, DeletionStepState.Pending, 0)), NO_CASE, NOTHING);

        assertThat(decision.status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(decision.externalCleanupDone()).isTrue();
        assertThat(decision.failure()).isNull();
        assertThat(decision.delay()).isEqualTo(POLL);
    }

    @Test
    @DisplayName("an empty inventory is finalisation's at once")
    void emptyInventory() {
        assertThat(progress.decide(List.of(), NO_CASE, NOTHING).externalCleanupDone()).isTrue();
    }

    @Test
    @DisplayName("a file still to remove is retried with a doubling delay, then needs attention once tries run out")
    void retriesThenAttention() {
        var first = progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Pending, 1)),
                                    NO_CASE, NOTHING);
        var second = progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Pending, 2)),
                                     NO_CASE, NOTHING);
        var spent = progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Pending, 3)),
                                    NO_CASE, NOTHING);

        assertThat(first.status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(first.delay()).isEqualTo(BASE);
        assertThat(second.delay()).isEqualTo(BASE.multipliedBy(2));
        assertThat(first.externalCleanupDone()).isFalse();
        assertThat(spent.status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(spent.failure()).isEqualTo(DeletionFailure.LOCAL_DOCUMENT_UNREMOVED);
    }

    @Test
    @DisplayName("the retry delay never passes the poll interval")
    void backoffCapped() {
        assertThat(progress.backoff(40)).isEqualTo(POLL);
        assertThat(progress.backoff(0)).isEqualTo(BASE);
    }

    @Test
    @DisplayName("a linked case is InProgress until marked and confirmed, then AwaitingDisposal")
    void linkedStates() {
        List<DeletionStep> steps = List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Done, 0),
            step(DeletionStepKind.CDAM_DOCUMENT, DeletionStepState.Pending, 0),
            step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Pending, 0));

        assertThat(progress.decide(steps, Optional.of(new CcdCaseState("Registered", false)), NOTHING).status())
            .isEqualTo(DeletionStatus.InProgress);
        assertThat(progress.decide(steps, Optional.of(new CcdCaseState("PendingDisposal", false)), NOTHING)
                       .status()).as("marked, not yet confirmed").isEqualTo(DeletionStatus.InProgress);
        var waiting = progress.decide(steps, Optional.of(new CcdCaseState("PendingDisposal", true)), NOTHING);
        assertThat(waiting.status()).isEqualTo(DeletionStatus.AwaitingDisposal);
        assertThat(waiting.externalCleanupDone()).isFalse();
    }

    @Test
    @DisplayName("a linked case's files come first: CCD is not waited on while one is left")
    void filesBeforeCcd() {
        var decision = progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Pending, 1),
                step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Pending, 0)),
            Optional.of(new CcdCaseState("PendingDisposal", true)), NOTHING);

        assertThat(decision.status()).isEqualTo(DeletionStatus.InProgress);
    }

    @Test
    @DisplayName("a local CCD copy gone without a recorded disposal is never read as disposed")
    void missingCcdCopy() {
        var decision = progress.decide(List.of(step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Pending, 0)),
                                       NO_CASE, NOTHING);

        assertThat(decision.status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(decision.failure()).isEqualTo(DeletionFailure.CCD_CASE_MISSING);
    }

    @Test
    @DisplayName("once dispose recorded CCD's removal, only finalisation is left")
    void disposed() {
        var decision = progress.decide(List.of(step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Done, 0),
            step(DeletionStepKind.CDAM_DOCUMENT, DeletionStepState.Done, 0)), NO_CASE, NOTHING);

        assertThat(decision.status()).isEqualTo(DeletionStatus.InProgress);
        assertThat(decision.externalCleanupDone()).isTrue();
    }

    @Test
    @DisplayName("a soft-deleted CDAM document waits for CCD's disposal, then holds the request from completing")
    void removedCdamDocument() {
        DeletionStep removed = step(DeletionStepKind.CDAM_REMOVED_DOCUMENT, DeletionStepState.Pending, 0);
        DeletionStep ccd = step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Pending, 0);
        var waiting = progress.decide(List.of(removed, ccd), Optional.of(new CcdCaseState("PendingDisposal", true)),
                                      NOTHING);
        assertThat(waiting.status()).as("CCD first").isEqualTo(DeletionStatus.AwaitingDisposal);

        var held = progress.decide(List.of(removed, step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Done, 0)),
                                   NO_CASE, NOTHING);
        assertThat(held.status()).isEqualTo(DeletionStatus.NeedsAttention);
        assertThat(held.failure()).isEqualTo(DeletionFailure.CDAM_REMOVED_DOCUMENT_UNPURGED);
        assertThat(held.externalCleanupDone()).isFalse();

        var purged = progress.decide(List.of(step(DeletionStepKind.CDAM_REMOVED_DOCUMENT, DeletionStepState.Done, 0),
            step(DeletionStepKind.CCD_DISPOSAL, DeletionStepState.Done, 0)), NO_CASE, NOTHING);
        assertThat(purged.externalCleanupDone()).isTrue();
    }

    @Test
    @DisplayName("CDAM documents with no CCD case, a failed line and a found problem all need attention")
    void attention() {
        assertThat(progress.decide(List.of(step(DeletionStepKind.CDAM_DOCUMENT, DeletionStepState.Pending, 0)),
                                   NO_CASE, NOTHING).failure()).isEqualTo(DeletionFailure.CDAM_WITHOUT_CCD_CASE);
        assertThat(progress.decide(List.of(step(DeletionStepKind.LOCAL_DOCUMENT, DeletionStepState.Failed, 1)),
                                   NO_CASE, NOTHING).failure()).isEqualTo(DeletionFailure.CLEANUP_STEP_FAILED);
        assertThat(progress.decide(List.of(), NO_CASE, Optional.of(DeletionFailure.LOCAL_STORE_UNAVAILABLE))
                       .failure()).isEqualTo(DeletionFailure.LOCAL_STORE_UNAVAILABLE);
    }
}
