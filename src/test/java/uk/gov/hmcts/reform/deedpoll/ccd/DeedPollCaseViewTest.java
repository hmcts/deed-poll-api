package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import uk.gov.hmcts.ccd.sdk.CaseViewRequest;
import uk.gov.hmcts.ccd.sdk.type.Document;
import uk.gov.hmcts.ccd.sdk.type.ListValue;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.CcdDocumentProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStoreProperties;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The view composes the case's fields from the record the reference is bound to, and from nothing else: the link and
 * the display reference from the record, the documents from its {@code Attached} rows.
 */
class DeedPollCaseViewTest {

    private static final long REFERENCE = 1_234_567_890_123_456L;
    private static final String RECORD = "7d0b7a8e-0000-4000-8000-000000000001";
    private static final UUID FIRST = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");
    private static final UUID SECOND = UUID.fromString("5f0c2a7e-3a1b-4d2e-8c9f-0a1b2c3d4e5f");
    private static final String BASE = "http://dm-store:8080";

    private final DeedPollRepository deedPolls = mock(DeedPollRepository.class);
    private final DeedPollSupportingDocumentRepository documents = mock(DeedPollSupportingDocumentRepository.class);
    private final DeedPollCaseView view = new DeedPollCaseView(deedPolls, documents,
        new CcdProperties("A_CASE_TYPE", "A_JURISDICTION", "a-role"), new DocumentStoreProperties("filesystem", BASE));

    @Test
    @DisplayName("answers for the configured case type only")
    void namesTheConfiguredCaseType() {
        assertThat(view.caseTypeIds()).containsExactly("A_CASE_TYPE");
    }

    @Test
    @DisplayName("composes the UUID and the display reference from the bound record")
    void composesFromTheRecord() {
        bound();

        DeedPollCaseData data = view();

        assertThat(data.getDeedPollId()).isEqualTo(RECORD);
        assertThat(data.getDeedPollReference()).isEqualTo("KBDP2026-000001");
    }

    @Test
    @DisplayName("refuses a case no record is bound to, rather than answering with empty data")
    void refusesAnUnboundCase() {
        when(deedPolls.findCcdCaseProjection("1234567890123456")).thenReturn(Optional.empty());

        assertThatThrownBy(this::view)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageNotContaining("1234567890123456");
    }

    @Test
    @DisplayName("each attached document is one item, keyed on its row, in the order the rows are read")
    void projectsTheAttachedDocuments() {
        bound();
        when(documents.findCcdDocumentProjections(RECORD)).thenReturn(List.of(
            new CcdDocumentProjection("document-1", FIRST, "birth.pdf"),
            new CcdDocumentProjection("document-2", SECOND, "marriage.pdf")));

        List<ListValue<Document>> items = view().getSupportingDocuments();

        assertThat(items).extracting(ListValue::getId).containsExactly("document-1", "document-2");
        assertThat(items.get(0).getValue()).satisfies(document -> {
            assertThat(document.getUrl()).isEqualTo(BASE + "/documents/" + FIRST);
            assertThat(document.getBinaryUrl()).isEqualTo(BASE + "/documents/" + FIRST + "/binary");
            assertThat(document.getFilename()).isEqualTo("birth.pdf");
        });
    }

    @Test
    @DisplayName("no hash token is ever projected: the item carries none, and the projection has nowhere to put one")
    void noHashToken() {
        bound();
        when(documents.findCcdDocumentProjections(RECORD))
            .thenReturn(List.of(new CcdDocumentProjection("document-1", FIRST, "birth.pdf")));

        assertThat(view().getSupportingDocuments()).singleElement()
            .satisfies(item -> assertThat(item.getValue().getHashToken()).isNull());
        assertThat(Arrays.stream(CcdDocumentProjection.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("documentId", "cdamDocumentId", "filename");
    }

    @Test
    @DisplayName("only Attached rows are read: no uncertain, pending or Failed row can reach the case")
    void onlyAttachedRowsAreRead() throws NoSuchMethodException {
        String query = DeedPollSupportingDocumentRepository.class
            .getMethod("findCcdDocumentProjections", String.class).getAnnotation(Query.class).value();

        assertThat(query).contains("DocumentStorageState." + DocumentStorageState.Attached.name())
            .doesNotContainIgnoringCase("hash");
        for (DocumentStorageState state : DocumentStorageState.values()) {
            if (state != DocumentStorageState.Attached) {
                assertThat(query).as(state.name()).doesNotContain("DocumentStorageState." + state.name());
            }
        }
    }

    @Test
    @DisplayName("the view writes the URL the document event wrote, so CDAM's check against the case finds it")
    void urlsMatchTheEvent() {
        bound();
        when(documents.findCcdDocumentProjections(RECORD))
            .thenReturn(List.of(new CcdDocumentProjection("document-1", FIRST, "birth.pdf")));
        CaseDocumentsEvent event = new CaseDocumentsEvent(null, null, null, new DocumentStoreProperties("cdam", BASE));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) event.content(List.of(
            new SupportingDocumentReference("document-1", FIRST, "birth.pdf", Optional.empty())), "token")
            .getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) data.get(CaseDocumentsEvent.FIELD);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) sent.get(0).get("value");
        Document shown = view().getSupportingDocuments().get(0).getValue();

        assertThat(shown.getUrl()).isEqualTo(value.get("document_url"));
        assertThat(shown.getBinaryUrl()).isEqualTo(value.get("document_binary_url"));
    }

    @Test
    @DisplayName("a record with no attached documents has no documents field, exactly as before")
    void noDocumentsIsAbsent() {
        bound();
        when(documents.findCcdDocumentProjections(RECORD)).thenReturn(List.of());

        assertThat(view().getSupportingDocuments()).isNull();
    }

    private void bound() {
        when(deedPolls.findCcdCaseProjection("1234567890123456"))
            .thenReturn(Optional.of(new CcdCaseProjection(RECORD, "KBDP2026-000001")));
    }

    private DeedPollCaseData view() {
        return view.getCase(new CaseViewRequest<>(REFERENCE, DeedPollCaseState.Registered));
    }
}
