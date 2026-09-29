package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.ccd.SupportingDocumentReference;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentOwner;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUpload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.AttachUncertain;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Attached;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Failed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.UploadPending;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.UploadUncertain;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Uploaded;
import static uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStoreTest.CDAM_ID;
import static uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStoreTest.HASH;
import static uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStoreTest.answered;
import static uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStoreTest.noResponse;

/**
 * The orchestration's state transitions, container-free: every row move goes through an in-memory
 * {@link DocumentStorageStates} that applies the same successor rule and the same token rule as V25.
 */
class CdamDocumentUploadsTest {

    private static final UUID RETRY_ID = UUID.fromString("5f0c2a7e-3a1b-4d2e-8c9f-0a1b2c3d4e5f");
    private static final String CASE = "1234567890123456";

    private final CdamDocumentStore store = mock(CdamDocumentStore.class);
    private final CaseDocumentsEvent event = mock(CaseDocumentsEvent.class);
    private final Row row = new Row();
    private CdamDocumentUploads uploads;

    @BeforeEach
    void setUp() {
        uploads = new CdamDocumentUploads(row, store, event);
    }

    private static DocumentUpload upload() {
        return DocumentUpload.of("record-1", "type-1",
            UploadedFile.of("%PDF".getBytes(StandardCharsets.UTF_8), "birth.pdf", "application/pdf"),
            Optional.empty(), Optional.empty());
    }

    private static StorageReceipt receipt(UUID id) {
        return new StorageReceipt(id.toString(), Optional.of(HASH));
    }

    @Test
    @DisplayName("upload then attach ends Attached, with the token cleared and the attach carrying it")
    void happyPath() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));

        uploads.upload(upload());

        assertThat(row.state).isEqualTo(Attached);
        assertThat(row.token).isNull();
        assertThat(row.cdamId).isEqualTo(CDAM_ID);
        assertThat(row.submitted).singleElement().satisfies(refs ->
            assertThat(refs).singleElement().satisfies(ref -> assertThat(ref.hashToken()).contains(HASH)));
        verify(event).submit(any(), anyList());
    }

    @Test
    @DisplayName("the upload is filed under the owner the claim read, with its CCD case")
    void uploadsForTheClaimedOwner() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));

        uploads.upload(upload());

        verify(store).save(org.mockito.ArgumentMatchers.argThat((DocumentToStore d) ->
            d.owner().ccdCaseReference().equals(Optional.of(CASE)) && d.filename().equals("birth.pdf")));
    }

    @Test
    @DisplayName("case not ready: nothing is claimed and nothing is sent")
    void caseNotReady() {
        row.ready = false;

        assertThatThrownBy(() -> uploads.upload(upload())).isInstanceOf(ConflictError.class);

        assertThat(row.state).isNull();
        verifyNoInteractions(store, event);
    }

    @Test
    @DisplayName("a refused upload is Failed, and nothing is attached")
    void refusedUpload() {
        when(store.save(any())).thenThrow(CdamUploadFailure.nothingStored("Upload refused: CDAM answered HTTP 400"));

        assertThatThrownBy(() -> uploads.upload(upload())).isInstanceOf(UpstreamFailureError.class);

        assertThat(row.state).isEqualTo(Failed);
        verify(store, times(1)).save(any());
        verifyNoInteractions(event);
    }

    @Test
    @DisplayName("one lost upload then a good one ends Uploaded with the retry's id, from exactly two uploads")
    void oneLostUploadThenARetry() {
        when(store.save(any()))
            .thenThrow(CdamUploadFailure.uncertain("Upload outcome unknown: no response from CDAM"))
            .thenReturn(receipt(RETRY_ID));
        doThrow(new CaseDocumentsEvent.NotSubmitted("Not submitted: CCD answered HTTP 404"))
            .when(event).submit(any(), anyList());

        assertThatThrownBy(() -> uploads.upload(upload())).isInstanceOf(UpstreamFailureError.class);

        assertThat(row.history).containsSubsequence(UploadPending, UploadUncertain, UploadPending, Uploaded);
        assertThat(row.cdamId).isEqualTo(RETRY_ID);
        assertThat(row.attempts).isEqualTo(2);
        verify(store, times(2)).save(any());
    }

    @Test
    @DisplayName("one lost upload then a good one, then an attach, ends Attached on the retry's id")
    void oneLostUploadThenARetryAttaches() {
        when(store.save(any()))
            .thenThrow(CdamUploadFailure.uncertain("Upload outcome unknown: no response from CDAM"))
            .thenReturn(receipt(RETRY_ID));

        uploads.upload(upload());

        assertThat(row.state).isEqualTo(Attached);
        assertThat(row.cdamId).isEqualTo(RETRY_ID);
        verify(store, times(2)).save(any());
    }

    @Test
    @DisplayName("two lost uploads stay UploadUncertain, after exactly two uploads and never a third")
    void twoLostUploads() {
        when(store.save(any())).thenThrow(CdamUploadFailure.uncertain("Upload outcome unknown: no response from CDAM"));

        assertThatThrownBy(() -> uploads.upload(upload()))
            .isInstanceOf(UpstreamFailureError.class)
            .hasMessage(CdamDocumentUploads.UPLOAD_LOST);

        assertThat(row.state).isEqualTo(UploadUncertain);
        assertThat(row.attempts).isEqualTo(2);
        verify(store, times(2)).save(any());
        verifyNoInteractions(event);
    }

    @Test
    @DisplayName("a lost attach is retried once, and a second answer attaches it")
    void lostAttachThenAttached() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));
        doThrow(noResponse()).doNothing().when(event).submit(any(), anyList());

        uploads.upload(upload());

        assertThat(row.history).containsSubsequence(Uploaded, AttachUncertain, Attached);
        assertThat(row.token).isNull();
        verify(event, times(2)).submit(any(), anyList());
    }

    @Test
    @DisplayName("two lost attaches stay AttachUncertain with the token kept for a later retry")
    void twoLostAttaches() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));
        doThrow(noResponse()).when(event).submit(any(), anyList());

        assertThatThrownBy(() -> uploads.upload(upload()))
            .isInstanceOf(UpstreamFailureError.class)
            .hasMessage(CdamDocumentUploads.ATTACH_LOST);

        assertThat(row.state).isEqualTo(AttachUncertain);
        assertThat(row.token).isEqualTo(HASH);
        verify(event, times(CdamDocumentUploads.MAX_ATTACH_SUBMISSIONS)).submit(any(), anyList());
    }

    @Test
    @DisplayName("an attach CCD refused, or that never reached the submission, is Failed from Uploaded")
    void refusedAttach() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));
        doThrow(answered(422)).when(event).submit(any(), anyList());

        assertThatThrownBy(() -> uploads.upload(upload()))
            .isInstanceOf(UpstreamFailureError.class)
            .hasMessage(CdamDocumentUploads.ATTACH_REFUSED);

        assertThat(row.state).isEqualTo(Failed);
        assertThat(row.token).isNull();
        verify(event, times(1)).submit(any(), anyList());
    }

    @Test
    @DisplayName("a refusal after a lost attach keeps AttachUncertain, because the first may have landed")
    void refusalAfterALostAttach() {
        when(store.save(any())).thenReturn(receipt(CDAM_ID));
        doThrow(noResponse()).doThrow(new CaseDocumentsEvent.NotSubmitted("Not submitted: CCD answered HTTP 404"))
            .when(event).submit(any(), anyList());

        assertThatThrownBy(() -> uploads.upload(upload()))
            .isInstanceOf(UpstreamFailureError.class)
            .hasMessage(CdamDocumentUploads.ATTACH_LOST);

        assertThat(row.state).isEqualTo(AttachUncertain);
    }

    @Test
    @DisplayName("an upload inside a transaction is refused before anything is claimed")
    void refusedInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> uploads.upload(upload())).isInstanceOf(IllegalStateException.class);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        assertThat(row.state).isNull();
        verifyNoInteractions(store, event);
    }

    /**
     * One row, moved by the same rules the database and {@link DocumentStorageStates} apply.
     */
    private static final class Row extends DocumentStorageStates {

        boolean ready = true;
        DocumentStorageState state;
        int attempts;
        UUID cdamId;
        String token;
        final List<DocumentStorageState> history = new ArrayList<>();
        final List<List<SupportingDocumentReference>> submitted = new ArrayList<>();

        Row() {
            super(null, null, null);
        }

        private Claim theClaim() {
            return new Claim("record-1", "document-1", new DocumentOwner("record-1", Optional.of(CASE)));
        }

        private void move(DocumentStorageState to) {
            if (!state.canMoveTo(to)) {
                throw new IllegalStateException(state + " -> " + to);
            }
            state = to;
            history.add(to);
            if (!to.holdsHashToken()) {
                token = null;
            }
        }

        @Override
        public Claim claim(DocumentUpload upload) {
            DocumentStorageRule.requireCaseReady(ready ? Optional.of(CASE) : Optional.empty());
            state = UploadPending;
            history.add(UploadPending);
            attempts = 1;
            return theClaim();
        }

        @Override
        public void uploadUncertain(Claim claim) {
            move(UploadUncertain);
        }

        @Override
        public boolean uploadAgain(Claim claim) {
            if (!DocumentStorageRule.mayUploadAgain(state, attempts)) {
                return false;
            }
            move(UploadPending);
            attempts++;
            return true;
        }

        @Override
        public void uploaded(Claim claim, UUID cdamDocumentId, String hashToken) {
            move(Uploaded);
            cdamId = cdamDocumentId;
            token = hashToken;
        }

        @Override
        public void failed(Claim claim) {
            move(Failed);
        }

        @Override
        public void attachUncertain(Claim claim) {
            move(AttachUncertain);
        }

        @Override
        public DeedPollSupportingDocument attached(Claim claim) {
            move(Attached);
            return new DeedPollSupportingDocument();
        }

        @Override
        public DocumentStorageState stateOf(Claim claim) {
            return state;
        }

        @Override
        public List<SupportingDocumentReference> references(Claim claim) {
            if (!DocumentStorageRule.mayAttach(state)) {
                throw new IllegalStateException("cannot attach from " + state);
            }
            List<SupportingDocumentReference> references =
                List.of(new SupportingDocumentReference("document-1", cdamId, "birth.pdf", Optional.of(token)));
            submitted.add(references);
            return references;
        }
    }
}
