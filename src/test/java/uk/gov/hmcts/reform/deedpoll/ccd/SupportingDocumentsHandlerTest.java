package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageStates;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The document event's handler, with the repository and the row moves mocked: it resolves the case's record, and
 * asks for a move only for an item naming a row and a readable CDAM id. Whether the move happens is
 * {@link DocumentStorageStates#attachedOnTheCase}'s to say.
 */
class SupportingDocumentsHandlerTest {

    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final long REFERENCE = 1_234_567_890_123_456L;
    private static final UUID FIRST = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");
    private static final UUID SECOND = UUID.fromString("5f0c2a7e-3a1b-4d2e-8c9f-0a1b2c3d4e5f");

    private final DeedPollRepository deedPolls = mock(DeedPollRepository.class);
    private final DocumentStorageStates states = mock(DocumentStorageStates.class);
    private final SupportingDocumentsHandler handler = new SupportingDocumentsHandler(deedPolls, states);

    @Test
    @DisplayName("each item naming a row and a CDAM id asks for that row's move, on this case's record")
    void movesTheNamedRows() {
        bound();

        assertThat(handler.submit(payload(item("document-1", FIRST), item("document-2", SECOND))).getErrors())
            .isNullOrEmpty();

        verify(states).attachedOnTheCase(RECORD, "document-1", FIRST);
        verify(states).attachedOnTheCase(RECORD, "document-2", SECOND);
    }

    @Test
    @DisplayName("a case no record is bound to is refused in fixed prose, and nothing is moved")
    void refusesAnUnboundCase() {
        when(deedPolls.findCcdCaseProjection(anyString())).thenReturn(Optional.empty());

        assertThat(handler.submit(payload(item("document-1", FIRST))).getErrors())
            .containsExactly(SupportingDocumentsHandler.NO_RECORD);
        verifyNoInteractions(states);
    }

    @Test
    @DisplayName("an item with no id, no value, or a URL naming no document asks for nothing")
    void ignoresWhatNamesNothing() {
        bound();
        ListValue<Document> noId = item(null, FIRST);
        ListValue<Document> noValue = new ListValue<>("document-2", null);
        ListValue<Document> badUrl = new ListValue<>("document-3",
            Document.builder().url("http://dm-store:8080/documents/not-a-uuid").build());
        ListValue<Document> noUrl = new ListValue<>("document-4", Document.builder().filename("x.pdf").build());

        assertThat(handler.submit(payload(noId, noValue, badUrl, noUrl)).getErrors()).isNullOrEmpty();

        verify(states, never()).attachedOnTheCase(any(), any(), any());
    }

    @Test
    @DisplayName("an event with no documents -- the one a delete of the last one sends -- moves nothing")
    void noDocuments() {
        bound();

        assertThat(handler.submit(new EventPayload<>(REFERENCE, new DeedPollCaseData(), new LinkedMultiValueMap<>()))
            .getErrors()).isNullOrEmpty();
        assertThat(handler.submit(new EventPayload<>(REFERENCE, null, new LinkedMultiValueMap<>())).getErrors())
            .isNullOrEmpty();

        verifyNoInteractions(states);
    }

    @Test
    @DisplayName("joins the SDK's transaction and refuses to run without one")
    void requiresTheCallersTransaction() throws NoSuchMethodException {
        Transactional transactional = SupportingDocumentsHandler.class.getMethod("submit", EventPayload.class)
            .getAnnotation(Transactional.class);

        assertThat(transactional.propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private void bound() {
        when(deedPolls.findCcdCaseProjection("1234567890123456"))
            .thenReturn(Optional.of(new CcdCaseProjection(RECORD, "KBDP2026-000001")));
    }

    private static ListValue<Document> item(String id, UUID cdamId) {
        return new ListValue<>(id, Document.builder()
            .url(CaseDocumentUrls.url("http://dm-store:8080", cdamId))
            .binaryUrl(CaseDocumentUrls.binaryUrl("http://dm-store:8080", cdamId))
            .filename("birth.pdf")
            .build());
    }

    @SafeVarargs
    private static EventPayload<DeedPollCaseData, DeedPollCaseState> payload(ListValue<Document>... items) {
        DeedPollCaseData data = new DeedPollCaseData();
        data.setSupportingDocuments(Arrays.asList(items));
        return new EventPayload<>(REFERENCE, data, new LinkedMultiValueMap<>());
    }
}
