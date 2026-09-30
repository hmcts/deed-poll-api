package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStoreProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sets a case's supporting documents in CCD by event, which is how a CDAM upload is attached (plan 03a, task 4).
 *
 * <p><strong>This service never calls CDAM's {@code attachToCase}.</strong> Data-store does, on the decentralised path
 * as on the centralised one, for every document in the event payload that carries a {@code document_hash}, and then
 * strips the hash before the data reaches {@code /ccd-persistence} (docs/CDAM-SEQUENCE.md section 2). So attaching a
 * document is submitting this event with the document in it, hash and all; and because CDAM skips a document already
 * attached to the same case, submitting it again after a lost answer is safe.
 *
 * <p>The event and the field are registered in {@link DeedPollCaseType} alongside {@code linkDeedPoll} (plan 03a,
 * task 5), and {@link SupportingDocumentsHandler} is its submit handler. An environment that has not imported that
 * definition has neither, which is one more reason the CDAM store is off.
 *
 * <p>The payload is what data-store attaches with and checks grants against, and nothing more: the case's documents as
 * CCD shows them come from {@link DeedPollCaseView}, composed from the rows, like every other field.
 *
 * <p><strong>Two kinds of failure, told apart by type.</strong> {@link NotSubmitted} means nothing reached the
 * submission -- the tokens or the start failed -- so the case cannot carry the document from this attempt. Anything
 * else escaped {@code createEvent} and is the caller's to classify with {@link CcdLinkingRule#classify}. Neither
 * carries a message from the far side, which is where a token would be echoed if one were.
 */
@Component
public class CaseDocumentsEvent {

    /**
     * The event that sets the case's supporting documents, registered in {@link DeedPollCaseType}.
     */
    public static final String EVENT_ID = "updateSupportingDocuments";

    /**
     * The case field holding the documents: a collection of the SDK's {@code Document}, {@link DeedPollCaseData}'s.
     */
    public static final String FIELD = "supportingDocuments";

    /** Fixed prose, never composed from a document: an event summary is one of the places the token must not be. */
    static final String EVENT_SUMMARY = "Supporting documents updated";

    private final CoreCaseDataApi ccd;
    private final SystemUserTokens systemUser;
    private final ObjectProvider<AuthTokenGenerator> serviceTokens;
    private final DocumentStoreProperties properties;

    public CaseDocumentsEvent(CoreCaseDataApi ccd,
                              SystemUserTokens systemUser,
                              ObjectProvider<AuthTokenGenerator> serviceTokens,
                              DocumentStoreProperties properties) {
        this.ccd = ccd;
        this.systemUser = systemUser;
        this.serviceTokens = serviceTokens;
        this.properties = properties;
    }

    /** Nothing reached the submission, so this attempt attached nothing. The message is fixed prose. */
    public static final class NotSubmitted extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public NotSubmitted(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Sets the case's supporting documents to exactly these, as the system user.
     *
     * @throws NotSubmitted if the tokens or the start failed, so nothing was submitted
     * @throws RuntimeException whatever {@code createEvent} threw, for the caller to classify
     */
    public void submit(String caseReference, List<SupportingDocumentReference> documents) {
        String user;
        String service;
        StartEventResponse start;
        try {
            user = systemUser.accessToken();
            service = serviceTokens.getObject().generate();
        } catch (RuntimeException failure) {
            throw new NotSubmitted("Not submitted: " + CcdLinkingService.describe("IDAM or S2S", failure));
        }
        try {
            start = ccd.startEvent(user, service, caseReference, EVENT_ID);
        } catch (RuntimeException failure) {
            throw new NotSubmitted("Not submitted: " + CcdLinkingService.describe(failure));
        }
        ccd.createEvent(user, service, caseReference, content(documents, start.getToken()));
    }

    CaseDataContent content(List<SupportingDocumentReference> documents, String eventToken) {
        return CaseDataContent.builder()
            .event(Event.builder().id(EVENT_ID).summary(EVENT_SUMMARY).build())
            .eventToken(eventToken)
            .data(Map.of(FIELD, documents.stream().map(this::item).toList()))
            .build();
    }

    /**
     * One collection item: the deed-poll document id, and the SDK's {@code Document} shape, whose URL data-store checks
     * against its pattern and CDAM reads its id from.
     */
    private Map<String, Object> item(SupportingDocumentReference document) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("document_url", CaseDocumentUrls.url(properties.documentUrlBase(), document.cdamDocumentId()));
        value.put("document_binary_url",
            CaseDocumentUrls.binaryUrl(properties.documentUrlBase(), document.cdamDocumentId()));
        value.put("document_filename", document.filename());
        document.hashToken().ifPresent(token -> value.put("document_hash", token));
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", document.id());
        item.put("value", value);
        return item;
    }
}
