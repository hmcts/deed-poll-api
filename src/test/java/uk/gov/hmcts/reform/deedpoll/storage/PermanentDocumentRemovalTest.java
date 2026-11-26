package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionFailure;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PermanentDocumentRemoval}'s classification of each step's answer (plan 20261125-permanent-document-deletion,
 * Task 4). The fresh-list property against real rows is {@code DocumentDeletionWorkTest}'s.
 */
class PermanentDocumentRemovalTest {

    private static final String REFERENCE = "1234567890123456";

    private final CaseDocumentsEvent event = mock(CaseDocumentsEvent.class);
    private final DeedPollSupportingDocumentRepository documents = mock(DeedPollSupportingDocumentRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<CdamPermanentDelete> cdam = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<LocalFilesystemDocumentStore> files = mock(ObjectProvider.class);

    private final PermanentDocumentRemoval removal = new PermanentDocumentRemoval(event, documents, cdam, files);

    @Test
    @DisplayName("CCD: submitted is the receipt; out of time sends nothing; a start failure, a refusal and an unknown")
    void ccdOutcomes() {
        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class))).thenReturn(true, false);
        assertThat(ccd()).isEqualTo(StepOutcome.done("SUBMITTED"));
        assertThat(ccd()).isEqualTo(StepOutcome.notSent("OUT_OF_TIME"));

        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class)))
            .thenThrow(new CaseDocumentsEvent.NotSubmitted("Not submitted"));
        assertThat(ccd()).isEqualTo(StepOutcome.retry("NOT_SUBMITTED"));

        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class)))
            .thenThrow(CdamDocumentStoreTest.answered(422));
        assertThat(ccd()).isEqualTo(StepOutcome.failed("REFUSED", DocumentDeletionFailure.CCD_REFERENCE_FAILED));

        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class)))
            .thenThrow(CdamDocumentStoreTest.answered(502));
        assertThat(ccd()).isEqualTo(StepOutcome.retry("UNKNOWN"));

        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class)))
            .thenThrow(CdamDocumentStoreTest.noResponse());
        assertThat(ccd()).isEqualTo(StepOutcome.retry("UNKNOWN"));
    }

    @Test
    @DisplayName("CDAM: the permanent delete's own answer, and a deployment without CDAM retried visibly")
    void cdamOutcomes() {
        UUID id = UUID.randomUUID();
        when(cdam.getIfAvailable()).thenReturn(null);
        assertThat(removal.deleteCdamPermanently(id)).isEqualTo(StepOutcome.retry("STORE_UNAVAILABLE"));

        CdamPermanentDelete client = mock(CdamPermanentDelete.class);
        when(cdam.getIfAvailable()).thenReturn(client);
        when(client.delete(id)).thenReturn(StepOutcome.done("DELETED"));
        assertThat(removal.deleteCdamPermanently(id)).isEqualTo(StepOutcome.done("DELETED"));
    }

    @Test
    @DisplayName("filesystem: deleted and already absent are verified receipts; a missing root or store is not")
    void localOutcomes(@TempDir Path root) throws IOException {
        LocalFilesystemDocumentStore store = new LocalFilesystemDocumentStore(root.toString());
        when(files.getIfAvailable()).thenReturn(store);
        Files.write(root.resolve("present"), new byte[] {1});

        assertThat(removal.removeLocalFile("present")).isEqualTo(StepOutcome.done("DELETED"));
        assertThat(root.resolve("present")).doesNotExist();
        assertThat(removal.removeLocalFile("present")).isEqualTo(StepOutcome.done("ALREADY_ABSENT"));
        assertThat(removal.removeLocalFile("../escape"))
            .isEqualTo(StepOutcome.failed("INVALID_STORAGE_KEY", DocumentDeletionFailure.LOCAL_FILE_UNREMOVED));

        LocalFilesystemDocumentStore unmounted =
            new LocalFilesystemDocumentStore(root.resolve("not-mounted").toString());
        when(files.getIfAvailable()).thenReturn(unmounted);
        assertThat(removal.removeLocalFile("present")).isEqualTo(StepOutcome.retry("REMOVAL_FAILED"));

        when(files.getIfAvailable()).thenReturn(null);
        assertThat(removal.removeLocalFile("present")).isEqualTo(StepOutcome.retry("STORE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("the CCD event never carries the document being deleted")
    void ccdListExcludesTheDocument() {
        when(documents.findByDeedPollIdAndStorageStateOrderByCreatedOnAsc(any(), any())).thenReturn(List.of());
        when(event.submit(eq(REFERENCE), anyList(), any(BooleanSupplier.class))).thenReturn(true);

        ccd();

        verify(event).submit(eq(REFERENCE), eq(List.of()), any(BooleanSupplier.class));
        verify(event, never()).submit(eq(REFERENCE), eq(List.of()));
    }

    private StepOutcome ccd() {
        return removal.removeCcdReference("case", "document", REFERENCE, () -> true);
    }
}
