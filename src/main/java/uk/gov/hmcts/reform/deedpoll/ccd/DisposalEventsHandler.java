package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionWorkRepository;

import java.util.List;
import java.util.Optional;

/**
 * The submit handlers of Retain and Dispose's two events, {@code MarkForDisposal} and {@code ConfirmDisposal}
 * (plan 20261118-dm-application-deletion, Task 5).
 *
 * <p><strong>Only an admitted deletion can start a disposal.</strong> The SDK's task fires the events for the
 * references {@link DeedPollRetainAndDisposePolicy} offers, but data-store would run a hand-fired one just the same,
 * so each handler asks again, under the deletion request's lock and inside the SDK's event transaction
 * ({@code MANDATORY}, as {@link LinkDeedPollHandler} is): the case must have a request whose own cleanup is done and
 * whose CCD step is still to come. Otherwise the event is refused, and nothing it touched survives. Neither handler
 * writes the record: V49 would refuse that for a fenced case, and the SDK persists the state and the resolved TTL.
 */
@Component
public class DisposalEventsHandler {

    static final String NOT_DELETING = "This case has no application deletion waiting for its disposal";

    private final DeletionWorkRepository work;

    public DisposalEventsHandler(DeletionWorkRepository work) {
        this.work = work;
    }

    /**
     * {@code MarkForDisposal}: {@code Registered} to {@code PendingDisposal}, for a case being deleted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubmitResponse<DeedPollCaseState> markForDisposal(
        EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        return admitted(payload)
            ? SubmitResponse.<DeedPollCaseState>builder().state(DeedPollCaseState.PendingDisposal).build()
            : refused();
    }

    /**
     * {@code ConfirmDisposal}: stays {@code PendingDisposal}; data-store sets {@code SystemTTL} from its increment.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public SubmitResponse<DeedPollCaseState> confirmDisposal(
        EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        return admitted(payload) ? SubmitResponse.defaultResponse() : refused();
    }

    private boolean admitted(EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        Optional<String> reference = CcdCaseReference.of(payload.caseReference());
        return reference.isPresent() && work.lockReadyForCcdDisposal(reference.get());
    }

    private static SubmitResponse<DeedPollCaseState> refused() {
        return SubmitResponse.<DeedPollCaseState>builder().errors(List.of(NOT_DELETING)).build();
    }
}
