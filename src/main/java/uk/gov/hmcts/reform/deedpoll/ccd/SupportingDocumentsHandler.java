package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageStates;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code updateSupportingDocuments} submit handler (plan 03a, task 5): records, inside the event's transaction,
 * that the documents the event attached are attached.
 *
 * <p>By the time data-store calls this, it has already attached every document in the payload that carried a hash
 * in CDAM, and stripped the hashes (docs/CDAM-SEQUENCE.md section 2). What is left here is the local half: each item
 * that names one of this case's rows still waiting to be attached moves that row to {@code Attached}, in
 * {@link DocumentStorageStates#attachedOnTheCase}. {@code MANDATORY} for {@link LinkDeedPollHandler}'s reason: the move
 * and the SDK's event rows commit or roll back together, and the view the SDK then loads for the snapshot sees it.
 *
 * <p>The payload is not the case's documents, and nothing else in it is written: {@link DeedPollCaseView} composes
 * the field from the rows. So an item for a document already attached, or deleted since, is left alone, and the event
 * a delete sends -- the remaining documents, none of them waiting -- moves nothing and refreshes the snapshot.
 *
 * <p>The one refusal is a case no record is bound to, which the view would refuse too.
 */
@Component
public class SupportingDocumentsHandler {

    /** Fixed prose: the refusal reaches data-store and the caller's log, and names nothing. */
    static final String NO_RECORD = "This CCD case has no deed poll record bound to it";

    private final DeedPollRepository deedPolls;
    private final DocumentStorageStates states;

    public SupportingDocumentsHandler(DeedPollRepository deedPolls, DocumentStorageStates states) {
        this.deedPolls = deedPolls;
        this.states = states;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public SubmitResponse<DeedPollCaseState> submit(EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        Optional<String> deedPollId = CcdCaseReference.of(payload.caseReference())
            .flatMap(deedPolls::findCcdCaseProjection)
            .map(CcdCaseProjection::deedPollId);
        if (deedPollId.isEmpty()) {
            return SubmitResponse.<DeedPollCaseState>builder().errors(List.of(NO_RECORD)).build();
        }
        List<ListValue<Document>> items = Optional.ofNullable(payload.caseData())
            .map(DeedPollCaseData::getSupportingDocuments)
            .orElse(List.of());
        for (ListValue<Document> item : items) {
            Optional<UUID> cdamDocumentId = Optional.ofNullable(item.getValue())
                .flatMap(document -> CaseDocumentUrls.cdamDocumentIdOf(document.getUrl()));
            if (item.getId() != null && cdamDocumentId.isPresent()) {
                states.attachedOnTheCase(deedPollId.get(), item.getId(), cdamDocumentId.get());
            }
        }
        return SubmitResponse.defaultResponse();
    }
}
