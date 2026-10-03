package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedPreparations;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStore;
import uk.gov.hmcts.reform.deedpoll.storage.CdamUploadFailure;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageStates;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "deedpoll.documents.store=cdam", "case_document_am.url=http://localhost:4455",
    "deedpoll.documents.staging.max-total-bytes=10485760",
    "notify.templates.forms-for-completion-adult=adult-template",
    "notify.templates.forms-for-completion-minor=minor-template",
    "notify.email-reply-to-id=reply-to", "notify.document-retention-weeks=17"
})
@ActiveProfiles("test")
class CdamFormsSendTest extends AbstractPostgresContainerTest {
    private static final CurrentUser CALLER = CurrentUser.currentUser(List.of(), "Worker", "worker-id");
    @Autowired private CaseEmailService emails;
    @Autowired private DeedPollService cases;
    @Autowired private DeedPollRepository caseRows;
    @Autowired private DeedPollSupportingDocumentRepository documents;
    @Autowired private NotificationRepository notifications;
    @MockitoSpyBean private DeedPreparations preparations;
    @Autowired private DocumentStorageStates states;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private CdamDocumentStore store;
    @MockitoBean private CorrespondenceDocumentReader reader;
    @MockitoBean private CaseDocumentsEvent event;
    @MockitoSpyBean private NoteService notes;
    private final List<String> created = new ArrayList<>();
    private Runnable duringUpload = () -> { };

    @BeforeEach
    void remoteAnswers() {
        when(reader.downloadDocument(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String caseId = call.getArgument(0);
            String documentId = call.getArgument(1);
            return new DocumentDownload(
                documents.findByIdAndDeedPollId(documentId, caseId).orElseThrow(),
                new ByteArrayInputStream(preparations.content(caseId, documentId)));
        });
        when(store.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            duringUpload.run();
            return new StorageReceipt(UUID.randomUUID().toString(), Optional.of("transient-hash"));
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(event).submit(anyString(), any());
    }

    @AfterEach
    void cleanup() {
        for (String id : created) {
            jdbc.update("""
                DELETE FROM deedpoll_stagedpayload WHERE id IN
                    (SELECT "documentId" FROM deedpoll_deedpreparation WHERE "deedPollId" = ?)
                """, id);
            caseRows.deleteById(id);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DeedTemplate.class)
    void bothDeedsEnqueueAndLostRequestReusesReceipt(DeedTemplate deed) {
        String id = linkedCase(deed);
        UUID key = UUID.randomUUID();
        var first = send(id, deed, key);
        var repeated = send(id, deed, key);
        assertThat(first.enqueued()).isTrue();
        assertThat(repeated.enqueued()).isFalse();
        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).singleElement()
            .satisfies(row -> assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached));
        verify(store).save(any());
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_stagedpayload p JOIN deedpoll_deedpreparation d ON p.id = d."documentId"
            WHERE d."deedPollId" = ?
            """, Long.class, id)).isZero();
        assertThatThrownBy(() -> emails.send(id, template(deed), Map.of("additional_notes", "changed"), key, CALLER))
            .isInstanceOf(ConflictError.class);
        verify(store).save(any());
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    void attachedLegacyDeedCanEnqueueWithoutInventingProvenance(DeedTemplate deed) {
        String id = linkedCase(deed);
        var prepared = preparations.prepare(id, deed);
        jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", prepared.documentId());
        jdbc.update("DELETE FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?", id);
        UUID cdamId = UUID.randomUUID();
        jdbc.update("""
            UPDATE deedpoll_supportingdocument SET "storageState" = 'Attached',
                "cdamDocumentId" = ?, "storageKey" = ? WHERE id = ?
            """, cdamId, cdamId.toString(), prepared.documentId());
        UUID key = UUID.randomUUID();
        var first = send(id, deed, key);
        assertThat(first.enqueued()).isTrue();
        assertThat(send(id, deed, key).id()).isEqualTo(first.id());
        assertThat(notifications.findById(first.id()).orElseThrow().getPersonalisation())
            .containsEntry("deed_document", prepared.documentId());
        assertThat(caseRows.hasDeedPreparation(id)).isFalse();
        verifyNoInteractions(store, event, reader);
    }

    @Test
    void disappearingPreparedDocumentRefusesWithoutRemoteWork() {
        String id = linkedCase(DeedTemplate.ADULT);
        doAnswer(call -> {
            var prepared = (uk.gov.hmcts.reform.deedpoll.domain.deeds.PreparedDeed) call.callRealMethod();
            documents.deleteById(prepared.documentId());
            return prepared;
        }).when(preparations).prepare(id, DeedTemplate.ADULT);
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class)
            .hasMessageContaining("prepared deed is unavailable");
        verifyNoInteractions(store, event, reader);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
    }

    @Test
    void noLinkRefusesBeforeGenerationOrNetwork() {
        String id = linkedCase(DeedTemplate.ADULT);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = NULL WHERE id = ?", id);
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).isEmpty();
        verifyNoInteractions(store, event);
    }

    @Test
    void springProxyRefusesOuterTransaction() {
        String id = linkedCase(DeedTemplate.ADULT);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> send(id)))
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store, event);
    }

    @Test
    void materialEditDuringUploadKeepsAttachmentWithoutEnqueueOrRegeneration() {
        String id = linkedCase(DeedTemplate.ADULT);
        duringUpload = () -> jdbc.update(
            "UPDATE deedpoll_deedpoll SET \"newForename\" = 'Changed' WHERE id = ?", id);
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class).hasMessageContaining("case changed");
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).singleElement()
            .satisfies(row -> assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.Attached));
        verify(store).save(any());
    }

    @Test
    void failedFinalNoteRollsBackEmailButRetainsAttachment() {
        String id = linkedCase(DeedTemplate.ADULT);
        UUID key = UUID.randomUUID();
        doThrow(new IllegalStateException("note failed")).when(notes).createNote(anyString(), any(), any());
        assertThatThrownBy(() -> send(id, DeedTemplate.ADULT, key)).isInstanceOf(IllegalStateException.class);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
        reset(notes);
        assertThat(send(id, DeedTemplate.ADULT, key).enqueued()).isTrue();
        verify(store).save(any());
    }

    @Test
    void uploadRefusalKeepsPreparationAndRetryCannotUploadAgain() {
        when(store.save(any())).thenThrow(CdamUploadFailure.nothingStored("refused"));
        assertParkedAfterFailure(DocumentStorageState.Failed, 1);
    }

    @Test
    void twoLostUploadsExhaustTheBudgetAcrossRequests() {
        when(store.save(any())).thenThrow(CdamUploadFailure.uncertain("lost"));
        assertParkedAfterFailure(DocumentStorageState.UploadUncertain, 2);
    }

    @Test
    void firstLostUploadCanSucceedOnItsOneFreshSend() {
        when(store.save(any())).thenThrow(CdamUploadFailure.uncertain("lost"))
            .thenReturn(new StorageReceipt(UUID.randomUUID().toString(), Optional.of("hash")));
        assertThat(send(linkedCase(DeedTemplate.ADULT)).enqueued()).isTrue();
        verify(store, times(2)).save(any());
    }

    @Test
    void lostAttachResponseRemainsUnusableWithNoFreshUploadBudget() {
        doThrow(new RuntimeException("lost")).when(event).submit(anyString(), any());
        assertParkedAfterFailure(DocumentStorageState.AttachUncertain, 1);
        verify(event, times(2)).submit(anyString(), any());
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStorageState.class, names = {"UploadPending", "Uploaded", "Failed"})
    void crashOrExpiredUnattachedCopyParksInsteadOfReuploading(DocumentStorageState state) {
        String id = linkedCase(DeedTemplate.ADULT);
        var prepared = preparations.prepare(id, DeedTemplate.ADULT);
        var claim = states.claimPrepared(id, prepared.documentId());
        if (state != DocumentStorageState.UploadPending) {
            states.uploaded(claim, UUID.randomUUID(), "old-token");
            if (state == DocumentStorageState.Failed) {
                states.failed(claim);
            }
        }
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class);
        verifyNoInteractions(store, event);
        assertThat(preparations.content(id, prepared.documentId())).isNotEmpty();
    }

    @Test
    void attachRefusalPreservesTheUploadedCopy() {
        doThrow(new CaseDocumentsEvent.NotSubmitted("refused")).when(event).submit(anyString(), any());
        assertParkedAfterFailure(DocumentStorageState.Failed, 1);
        verify(event).submit(anyString(), any());
    }

    @Test
    void handlerCommitWinsOverLostAttachResponse() {
        String id = linkedCase(DeedTemplate.ADULT);
        doAnswer(call -> {
            var row = documents.findByDeedPollIdOrderByCreatedOnAsc(id).getFirst();
            new TransactionTemplate(transactions).executeWithoutResult(status ->
                states.attachedOnTheCase(id, row.getId(), row.getCdamDocumentId()));
            throw new RuntimeException("response lost after commit");
        }).when(event).submit(anyString(), any());
        assertThat(send(id).enqueued()).isTrue();
        verify(event).submit(anyString(), any());
        verify(store).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"applicantEmail", "type", "direction"})
    void finalValidationRefusesChangedRecipientRoutingOrDocumentDirection(String field) {
        String id = linkedCase(DeedTemplate.ADULT);
        duringUpload = () -> {
            switch (field) {
                case "applicantEmail" -> jdbc.update(
                    "UPDATE deedpoll_deedpoll SET \"applicantEmail\" = 'other@example.test' WHERE id = ?", id);
                case "type" -> jdbc.update("UPDATE deedpoll_deedpoll SET type = 'Minor' WHERE id = ?", id);
                case "direction" -> jdbc.update(
                    "UPDATE deedpoll_supportingdocument SET direction = 'Incoming' WHERE \"deedPollId\" = ?", id);
                default -> throw new AssertionError(field);
            }
        };
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
        verify(store).save(any());
    }

    @Test
    void harmlessAuditEditDoesNotInvalidateDeed() {
        String id = linkedCase(DeedTemplate.ADULT);
        duringUpload = () -> jdbc.update("UPDATE deedpoll_deedpoll SET \"modifiedBy\" = 'Other' WHERE id = ?", id);
        assertThat(send(id).enqueued()).isTrue();
    }

    @Test
    void sameKeyCannotChangeTemplateAndContradictoryOccupantPrecedesPreparation() {
        String id = linkedCase(DeedTemplate.ADULT);
        UUID key = UUID.randomUUID();
        send(id, DeedTemplate.ADULT, key);
        jdbc.update("UPDATE deedpoll_deedpoll SET type = 'Minor' WHERE id = ?", id);
        assertThatThrownBy(() -> send(id, DeedTemplate.MINOR, key))
            .isInstanceOf(ConflictError.class).hasMessage(CaseEmailService.REQUEST_ALREADY_USED);
        assertThatThrownBy(() -> send(id, DeedTemplate.MINOR, UUID.randomUUID()))
            .isInstanceOf(ConflictError.class).hasMessageContaining("already requested");
        verify(store).save(any());
    }

    @Test
    void racingReplicaCannotAcquireAnotherUploadClaim() throws Exception {
        String id = linkedCase(DeedTemplate.ADULT);
        UUID key = UUID.randomUUID();
        var uploading = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        duringUpload = () -> {
            uploading.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out awaiting concurrent request");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = pool.submit(() -> send(id, DeedTemplate.ADULT, key));
            try {
                assertThat(uploading.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> send(id, DeedTemplate.ADULT, key)).isInstanceOf(ConflictError.class);
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, java.util.concurrent.TimeUnit.SECONDS).enqueued()).isTrue();
        }
        assertThat(send(id, DeedTemplate.ADULT, key).enqueued()).isFalse();
        verify(store).save(any());
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).hasSize(1);
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).hasSize(1);
    }

    @Test
    void failedReadBackRetainsExactBytesAndRetryNeverUploadsAgain() {
        String id = linkedCase(DeedTemplate.ADULT);
        doThrow(new UpstreamFailureError("unavailable")).when(reader).downloadDocument(anyString(), anyString());
        assertThatThrownBy(() -> send(id)).isInstanceOf(UpstreamFailureError.class);
        var prepared = preparations.prepare(id, DeedTemplate.ADULT);
        byte[] original = preparations.content(id, prepared.documentId());
        assertThat(original).isNotEmpty();
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
        doAnswer(call -> new DocumentDownload(
            documents.findById(prepared.documentId()).orElseThrow(), new ByteArrayInputStream(original)))
            .when(reader).downloadDocument(id, prepared.documentId());
        assertThat(send(id).enqueued()).isTrue();
        verify(store).save(any());
        assertThatThrownBy(() -> preparations.content(id, prepared.documentId())).isInstanceOf(ConflictError.class);
    }

    @Test
    void checksumMismatchRetainsPayloadAndClosesStream() {
        String id = linkedCase(DeedTemplate.ADULT);
        var closed = new AtomicBoolean();
        doAnswer(call ->
            new DocumentDownload(
                documents.findById((String) call.getArgument(1)).orElseThrow(),
                new ByteArrayInputStream(new byte[]{1, 2, 3}) {
                    @Override
                    public void close() {
                        closed.set(true);
                    }
                })).when(reader).downloadDocument(anyString(), anyString());
        assertThatThrownBy(() -> send(id)).isInstanceOf(UpstreamFailureError.class);
        assertThat(closed).isTrue();
        var prepared = preparations.prepare(id, DeedTemplate.ADULT);
        assertThat(preparations.content(id, prepared.documentId())).isNotEmpty();
        assertThatThrownBy(() -> send(id)).isInstanceOf(UpstreamFailureError.class);
        verify(store).save(any());
    }

    private void assertParkedAfterFailure(DocumentStorageState state, int sends) {
        String id = linkedCase(DeedTemplate.ADULT);
        assertThatThrownBy(() -> send(id)).isInstanceOf(UpstreamFailureError.class)
            .hasMessage("This document could not be confirmed in document storage. "
                + "Its preparation has been kept; review its storage outcome before retrying.");
        assertThatThrownBy(() -> send(id)).isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).singleElement()
            .satisfies(row -> assertThat(row.getStorageState()).isEqualTo(state));
        assertThat(notifications.findByDeedPollIdOrderByCreatedOnDesc(id)).isEmpty();
        verify(store, times(sends)).save(any());
    }

    private SendReceipt send(String id) {
        return send(id, DeedTemplate.ADULT, UUID.randomUUID());
    }

    private SendReceipt send(String id, DeedTemplate deed, UUID key) {
        return emails.send(id, template(deed), Map.of(), key, CALLER);
    }

    private static EmailTemplate template(DeedTemplate deed) {
        return deed == DeedTemplate.ADULT ? EmailTemplate.FORMS_FOR_COMPLETION_ADULT
            : EmailTemplate.FORMS_FOR_COMPLETION_MINOR;
    }

    private String linkedCase(DeedTemplate deed) {
        String id = cases.createDeedPoll(DeedPollPatch.of(Map.of(
            "dateReceived", "2026-07-01T00:00:00.000Z", "type", deed == DeedTemplate.ADULT ? "Adult" : "Minor",
            "applicantEmail", "applicant@example.test"))).deedPoll().getId();
        created.add(id);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?",
            String.valueOf(1000000000000000L + Math.abs((long) id.hashCode())), id);
        return id;
    }
}
