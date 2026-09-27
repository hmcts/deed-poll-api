package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCreationAttemptRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.List;
import java.util.Optional;

/**
 * The {@code linkDeedPoll} submit handler: binds the CCD reference data-store is creating to the record the payload
 * names, once, and never over another (plan 02, decision 10).
 *
 * <p><strong>{@code MANDATORY}, because the whole point is to share the SDK's event transaction.</strong> The SDK
 * runs this inside {@code CaseEventTransactionCoordinator.execute}, before it writes {@code ccd.case_data} and
 * {@code ccd.case_event} on the same connection, so the bind and those rows commit or roll back together. Run
 * outside it, a bind could commit for a case CCD then failed to write. The propagation makes that a refusal rather
 * than a silent second transaction.
 *
 * <p>The record is locked first -- {@code SELECT … FOR UPDATE} -- so two submissions for one record serialise, and
 * the second reads what the first bound. Every other fact is read under that lock, and {@link CcdLinkRule} decides.
 * A refusal is returned as a submit error, which the SDK raises inside the transaction, so nothing a refused
 * submission touched survives it.
 *
 * <p>A repeated request with the same {@code Idempotency-Key} never reaches this class: the SDK finds the event it
 * already wrote and replays that event's stored snapshot. So the replay answers what was true when the event was
 * written, however the record has changed since.
 */
@Component
public class LinkDeedPollHandler {

    private final DeedPollRepository deedPolls;
    private final CcdCreationAttemptRepository attempts;

    public LinkDeedPollHandler(DeedPollRepository deedPolls, CcdCreationAttemptRepository attempts) {
        this.deedPolls = deedPolls;
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubmitResponse<DeedPollCaseState> submit(EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        Optional<String> reference = CcdCaseReference.of(payload.caseReference());
        String deedPollId = Optional.ofNullable(payload.caseData())
            .map(DeedPollCaseData::getDeedPollId)
            .filter(id -> !id.isBlank())
            .orElse(null);

        Optional<DeedPoll> record = reference.isEmpty() || deedPollId == null
            ? Optional.empty()
            : deedPolls.findByIdForUpdate(deedPollId);

        CcdLinkRule.Facts facts = new CcdLinkRule.Facts(
            reference,
            record.isPresent(),
            record.map(DeedPoll::getCcdCaseReference),
            record.flatMap(found -> attempts.findLive(found.getId())).map(CcdCreationAttempt::getStatus),
            record.isEmpty() ? Optional.empty()
                : reference.flatMap(deedPolls::findCcdCaseProjection).map(CcdCaseProjection::deedPollId));

        CcdLinkRule.Outcome outcome = CcdLinkRule.decide(deedPollId, facts);
        if (outcome.refused()) {
            return SubmitResponse.<DeedPollCaseState>builder().errors(List.of(outcome.refusal())).build();
        }
        if (outcome == CcdLinkRule.Outcome.BIND && deedPolls.bindCcdCaseReference(deedPollId, reference.get()) != 1) {
            // Unreachable under the row lock: the record was there and unbound when it was read.
            throw new IllegalStateException("A locked, unbound deed poll record refused its CCD case reference");
        }
        return SubmitResponse.defaultResponse();
    }
}
