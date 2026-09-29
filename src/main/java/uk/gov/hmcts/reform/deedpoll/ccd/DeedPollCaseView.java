package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.CaseView;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.CcdDocumentProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStoreProperties;

import java.util.List;
import java.util.Set;

/**
 * What CCD shows of a deed poll case: its fields, composed from the relational record every time CCD loads it.
 *
 * <p>The record is the truth (plan 02, decision 1). The SDK stores {@code {}} in {@code ccd.case_data.data} for a
 * decentralised event, and this view is where the case's data comes from on every read and on the snapshot the SDK
 * writes into {@code ccd.case_event}. The blob it is offered is ignored for the same reason.
 *
 * <p>Read through {@link CcdCaseProjection} and {@link CcdDocumentProjection}, so no entity crosses into the SDK. The
 * one view bean for the case type: the runtime refuses to start with none, or with two for one case type, and
 * {@link #caseTypeIds} names ours rather than leaving the SDK to match it by generics.
 *
 * <p><strong>The documents are the record's {@code Attached} rows</strong> (plan 03a, task 5), so the answer is the
 * same after a restart and on any replica, and nothing uncertain or failed is ever on the case. The SDK loads this
 * view inside the event's transaction, after the handler, so a document {@link SupportingDocumentsHandler} has just
 * attached is in that event's snapshot; one deleted is absent from the next read at once, whatever the event that
 * follows the delete manages.
 */
@Component
public class DeedPollCaseView implements CaseView<DeedPollCaseData, DeedPollCaseState> {

    private final DeedPollRepository deedPolls;
    private final DeedPollSupportingDocumentRepository documents;
    private final CcdProperties properties;
    private final DocumentStoreProperties documentStore;

    public DeedPollCaseView(DeedPollRepository deedPolls,
                            DeedPollSupportingDocumentRepository documents,
                            CcdProperties properties,
                            DocumentStoreProperties documentStore) {
        this.deedPolls = deedPolls;
        this.documents = documents;
        this.properties = properties;
        this.documentStore = documentStore;
    }

    @Override
    public Set<String> caseTypeIds() {
        return Set.of(properties.caseTypeId());
    }

    /**
     * The case's data, from the record its reference is bound to.
     *
     * @throws IllegalStateException if no record carries the reference. The handler binds it in the transaction
     *     that creates the case and a failure rolls both back, so a case with no record is a broken invariant, and
     *     answering with empty data would hide it
     */
    @Override
    public DeedPollCaseData getCase(CaseViewRequest<DeedPollCaseState> request) {
        CcdCaseProjection record = CcdCaseReference.of(request.caseRef())
            .flatMap(deedPolls::findCcdCaseProjection)
            .orElseThrow(() -> new IllegalStateException("A CCD case has no deed poll record bound to it"));
        DeedPollCaseData data = new DeedPollCaseData();
        data.setDeedPollId(record.deedPollId());
        data.setDeedPollReference(record.displayReference());
        List<ListValue<Document>> attached = documents.findCcdDocumentProjections(record.deedPollId()).stream()
            .map(this::item)
            .toList();
        // Absent rather than empty for a case with none, so a record with no CDAM documents reads exactly as it did.
        data.setSupportingDocuments(attached.isEmpty() ? null : attached);
        return data;
    }

    private ListValue<Document> item(CcdDocumentProjection document) {
        String base = documentStore.documentUrlBase();
        Document value = Document.builder()
            .url(CaseDocumentUrls.url(base, document.cdamDocumentId()))
            .binaryUrl(CaseDocumentUrls.binaryUrl(base, document.cdamDocumentId()))
            .filename(document.filename())
            .build();
        return new ListValue<>(document.documentId(), value);
    }
}
