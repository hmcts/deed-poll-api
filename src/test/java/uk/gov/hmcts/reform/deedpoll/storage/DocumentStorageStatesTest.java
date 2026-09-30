package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.ccd.SupportingDocumentReference;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentOwner;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.AttachUncertain;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Attached;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Uploaded;

/**
 * The moves task 5 added, with the repository mocked: the one the document event's handler makes inside the event's
 * transaction, the orchestrator's move that now finds it already made, and the list a delete's event sets.
 */
class DocumentStorageStatesTest {

    private static final String RECORD = "record-1";
    private static final UUID CDAM_ID = UUID.fromString("0b9fb4a8-b0c7-4c52-9f7f-5dc1a6b2d0e1");
    private static final UUID OTHER_ID = UUID.fromString("5f0c2a7e-3a1b-4d2e-8c9f-0a1b2c3d4e5f");
    private static final String HASH = "hash-token";

    private final DeedPollSupportingDocumentRepository documents = mock(DeedPollSupportingDocumentRepository.class);
    private final DocumentStorageStates states = new DocumentStorageStates(null, documents, null);

    @ParameterizedTest
    @EnumSource(value = DocumentStorageState.class, names = {"Uploaded", "AttachUncertain"})
    @DisplayName("the event's handler attaches a row waiting to be attached, clearing the token and setting the key")
    void attachesAWaitingRow(DocumentStorageState from) {
        DeedPollSupportingDocument row = row("document-1", from, CDAM_ID);

        assertThat(states.attachedOnTheCase(RECORD, "document-1", CDAM_ID)).isTrue();

        assertThat(row.getStorageState()).isEqualTo(Attached);
        assertThat(row.getCdamHashToken()).isNull();
        assertThat(row.getStorageKey()).isEqualTo(CDAM_ID.toString());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStorageState.class, names = {"Uploaded", "AttachUncertain"},
        mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("a row in any other state is left alone: nothing pending, uncertain or failed is put on the case")
    void leavesEveryOtherStateAlone(DocumentStorageState from) {
        DeedPollSupportingDocument row = row("document-1", from, CDAM_ID);

        assertThat(states.attachedOnTheCase(RECORD, "document-1", CDAM_ID)).isFalse();

        assertThat(row.getStorageState()).isEqualTo(from);
    }

    @Test
    @DisplayName("an item naming another CDAM document than the row's is left alone: the id is compared, never taken")
    void comparesTheCdamId() {
        DeedPollSupportingDocument row = row("document-1", Uploaded, CDAM_ID);

        assertThat(states.attachedOnTheCase(RECORD, "document-1", OTHER_ID)).isFalse();

        assertThat(row.getStorageState()).isEqualTo(Uploaded);
        assertThat(row.getCdamDocumentId()).isEqualTo(CDAM_ID);
        assertThat(row.getCdamHashToken()).isEqualTo(HASH);
    }

    @Test
    @DisplayName("a row that is not this case's, or is gone, is left alone")
    void anotherCasesRow() {
        when(documents.findByIdAndDeedPollIdForUpdate("document-1", RECORD)).thenReturn(Optional.empty());

        assertThat(states.attachedOnTheCase(RECORD, "document-1", CDAM_ID)).isFalse();
    }

    @Test
    @DisplayName("the orchestrator's attach after the handler already made it answers the row, unchanged")
    void attachedIsIdempotent() {
        DeedPollSupportingDocument row = row("document-1", AttachUncertain, CDAM_ID);
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName("Birth certificate");
        row.setDocumentType(type);
        states.attachedOnTheCase(RECORD, "document-1", CDAM_ID);

        DeedPollSupportingDocument answered = states.attached(claim("document-1"));

        assertThat(answered).isSameAs(row);
        assertThat(answered.getStorageState()).isEqualTo(Attached);
    }

    @Test
    @DisplayName("a delete's event sets every attached document but the deleted one, oldest first, with no token")
    void referencesWithout() {
        DeedPollSupportingDocument first = row("document-1", Attached, CDAM_ID);
        DeedPollSupportingDocument second = row("document-2", Attached, OTHER_ID);
        first.setCdamHashToken(null);
        second.setCdamHashToken(null);
        when(documents.findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(RECORD, Attached))
            .thenReturn(List.of(first, second));

        assertThat(states.referencesWithout(RECORD, "document-1")).singleElement().satisfies(reference -> {
            assertThat(reference.id()).isEqualTo("document-2");
            assertThat(reference.cdamDocumentId()).isEqualTo(OTHER_ID);
            assertThat(reference.hashToken()).isEmpty();
        });
        assertThat(states.referencesWithout(RECORD, "document-3")).extracting(SupportingDocumentReference::id)
            .containsExactly("document-1", "document-2");
    }

    @Test
    @DisplayName("the handler's move and the delete's read join the caller's transaction and never open their own")
    void joinTheCallersTransaction() throws NoSuchMethodException {
        assertThat(DocumentStorageStates.class.getMethod("attachedOnTheCase", String.class, String.class, UUID.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
        assertThat(DocumentStorageStates.class.getMethod("referencesWithout", String.class, String.class)
            .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.MANDATORY);
    }

    private DeedPollSupportingDocument row(String id, DocumentStorageState state, UUID cdamId) {
        DeedPollSupportingDocument row = new DeedPollSupportingDocument();
        row.setId(id);
        row.setStorageState(state);
        row.setCdamDocumentId(cdamId);
        row.setCdamHashToken(state.holdsHashToken() ? HASH : null);
        row.setOriginalName(id + ".pdf");
        when(documents.findByIdAndDeedPollIdForUpdate(id, RECORD)).thenReturn(Optional.of(row));
        return row;
    }

    private static DocumentStorageStates.Claim claim(String documentId) {
        return new DocumentStorageStates.Claim(RECORD, documentId, new DocumentOwner(RECORD, Optional.of("1")));
    }
}
