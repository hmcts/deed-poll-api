package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DisposalEventsHandler} with its repository mocked: a disposal event goes through only for a case whose
 * deletion request is ready for it, and refuses as a submit error otherwise. {@code RetainAndDisposeDeletionTest} runs
 * it against real Postgres along the SDK's own task.
 */
class DisposalEventsHandlerTest {

    private static final long REFERENCE = 1_234_567_890_123_456L;

    private final DeletionWorkRepository work = mock(DeletionWorkRepository.class);
    private final DisposalEventsHandler handler = new DisposalEventsHandler(work);

    private static EventPayload<DeedPollCaseData, DeedPollCaseState> payload(Long reference) {
        return new EventPayload<>(reference, new DeedPollCaseData(), new LinkedMultiValueMap<>());
    }

    @Test
    @DisplayName("MarkForDisposal moves a case being deleted to PendingDisposal")
    void marks() {
        when(work.lockReadyForCcdDisposal("1234567890123456")).thenReturn(true);

        var response = handler.markForDisposal(payload(REFERENCE));

        assertThat(response.getErrors()).isNullOrEmpty();
        assertThat(response.getState()).isEqualTo(DeedPollCaseState.PendingDisposal);
    }

    @Test
    @DisplayName("ConfirmDisposal goes through for a case being deleted and changes no state itself")
    void confirms() {
        when(work.lockReadyForCcdDisposal("1234567890123456")).thenReturn(true);

        var response = handler.confirmDisposal(payload(REFERENCE));

        assertThat(response.getErrors()).isNullOrEmpty();
        assertThat(response.getState()).isNull();
    }

    @Test
    @DisplayName("a case with no deletion ready for it is refused by both events")
    void refusesAnythingElse() {
        when(work.lockReadyForCcdDisposal(any())).thenReturn(false);

        assertThat(handler.markForDisposal(payload(REFERENCE)).getErrors())
            .containsExactly(DisposalEventsHandler.NOT_DELETING);
        assertThat(handler.confirmDisposal(payload(REFERENCE)).getErrors())
            .containsExactly(DisposalEventsHandler.NOT_DELETING);
    }

    @Test
    @DisplayName("an invalid reference is refused without asking the database")
    void refusesAnInvalidReference() {
        assertThat(handler.markForDisposal(payload(42L)).getErrors())
            .containsExactly(DisposalEventsHandler.NOT_DELETING);
        verify(work, never()).lockReadyForCcdDisposal(any());
    }

    @Test
    @DisplayName("the policy records a disposal only for a case a request is waiting on, and refuses any other")
    void policyDispose() {
        DeedPollRetainAndDisposePolicy policy = new DeedPollRetainAndDisposePolicy(
            new CcdProperties("A_CASE_TYPE", "A_JURISDICTION", "a-role"), work);
        when(work.recordDisposed(any(), any())).thenReturn(1, 0);

        policy.dispose(REFERENCE);
        assertThatThrownBy(() -> policy.dispose(REFERENCE)).isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("1234567890123456");
        assertThatThrownBy(() -> policy.dispose(42L)).isInstanceOf(IllegalStateException.class);
        assertThat(policy.caseTypes()).containsExactly("A_CASE_TYPE");
    }
}
