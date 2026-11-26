package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentToStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.DocumentDeletionCommand;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionFenceRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionAdmissionRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link DocumentDeletionService}'s admission against real Postgres and real transactions (plan
 * 20261125-permanent-document-deletion, Task 3): the request, its steps and its audit event commit together or not
 * at all, every refusal writes nothing, a replay answers the first request, the admitted document is fenced from new
 * reads and uses while the case's other documents stay usable, and the races with a queued email, a document write
 * and a read come out the same way every time.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentDeletionServiceTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "document-deletion-caseworker";
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(Role.CASEWORKER), "Casey Worker", SUBJECT);
    private static final String REASON = "Uploaded in error";
    private static final int TIMEOUT_SECONDS = 20;

    @Autowired
    private DocumentDeletionService deletions;

    @Autowired
    private DocumentDeletionRequestRepository requests;

    @Autowired
    private DocumentDeletionAdmissionRepository admissionReads;

    @Autowired
    private DeletionAdmissionRepository caseLocks;

    @Autowired
    private DeletionFenceRepository applicationFence;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollSupportingDocumentRepository documentRows;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private AuditTrail audit;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> fixtures = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS document_deletion_test_audit_failure ON deedpoll_audit_event");
        jdbc.execute("DROP FUNCTION IF EXISTS document_deletion_test_audit_failure()");
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id IN (SELECT \"documentId\" "
                            + "FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?)", id);
            jdbc.update("DELETE FROM deedpoll_ownedstage WHERE \"ownerKind\" = 'RECORD' AND \"ownerId\" = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- admission --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("admission writes the request, its file step and one value-free audit event under the caller")
    void admitsAFilesystemDocument() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        UUID key = UUID.randomUUID();

        Admission result = admit(caseId, documentId, key);

        assertThat(result.admitted()).isTrue();
        DocumentDeletionRequest request = result.request();
        assertThat(request.caseId()).isEqualTo(caseId);
        assertThat(request.documentId()).isEqualTo(documentId);
        assertThat(request.idempotencyKey()).isEqualTo(key);
        assertThat(request.reason()).contains(DocumentDeletionReason.parse(REASON));
        assertThat(request.documentName()).isEqualTo("Evidence");
        assertThat(request.expectedDocumentVersion()).isEqualTo(1);
        assertThat(request.fingerprint())
            .isEqualTo(DocumentDeletionFingerprint.of(documentId, 1, DocumentDeletionReason.parse(REASON)));
        assertThat(request.requestedBySubject()).isEqualTo(SUBJECT);
        assertThat(request.requestedByName()).isEqualTo("Casey Worker");
        assertThat(request.status()).isEqualTo(DocumentDeletionStatus.InProgress);
        assertThat(request.completedAt()).isNull();
        assertThat(requests.stepsOf(request.id())).singleElement().satisfies(step -> {
            assertThat(step.kind()).isEqualTo(DocumentDeletionStepKind.LOCAL_FILE);
            assertThat(step.state()).isEqualTo(DocumentDeletionStepState.Pending);
            assertThat(step.locator()).startsWith("files/");
        });

        List<Map<String, Object>> events = requestedEvents(caseId);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("actor_id", SUBJECT).containsEntry("actor_name", "Casey Worker")
                .containsEntry("entity_type", "Document").containsEntry("entity_id", documentId)
                .containsEntry("value_rows", 0L);
            assertThat((String) event.get("detail")).contains("\"documentDeletionReason\": \"" + REASON + "\"")
                .contains("\"documentDeletionRequestId\": \"" + request.id() + "\"");
        });
        // The document itself is untouched until cleanup: admission removes nothing.
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
    }

    @Test
    @DisplayName("every staff role may delete, with no capability beyond the area; a judge or nobody may not")
    void staffOnly() {
        @SuppressWarnings("deprecation")
        List<Role> staff = List.of(Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER, Role.DEEDPOLL_ADMIN);
        for (Role role : staff) {
            String caseId = fixture();
            String documentId = document(caseId, "Local", null, null, 0);
            CurrentUser caller = CurrentUser.currentUser(List.of(role), "Staff " + role, "subject-" + role);
            assertThat(deletions.request(caller, caseId, documentId, command(UUID.randomUUID())).admitted())
                .as(role.name()).isTrue();
        }

        String caseId = fixture();
        String documentId = document(caseId, "Local", null, null, 0);
        CurrentUser judge = CurrentUser.currentUser(List.of(Role.JUDICIAL), "Master Judge", "judge-subject");
        assertThatThrownBy(() -> deletions.request(judge, caseId, documentId, command(UUID.randomUUID())))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> deletions.request(CurrentUser.ANONYMOUS, caseId, documentId,
                                                   command(UUID.randomUUID())))
            .isInstanceOf(ForbiddenError.class);
        // Refused before the body is read: no reason is a 403, not a 400.
        assertThatThrownBy(() -> deletions.request(judge, caseId, documentId,
                                                   new DocumentDeletionCommand(null, null, null)))
            .isInstanceOf(ForbiddenError.class);
        CurrentUser noSubject = CurrentUser.currentUser(List.of(Role.CASEWORKER), "No Subject", null);
        assertThatThrownBy(() -> deletions.request(noSubject, caseId, documentId, command(UUID.randomUUID())))
            .isInstanceOf(UnauthorizedError.class);
        assertThatThrownBy(() -> deletions.read(judge, caseId, UUID.randomUUID())).isInstanceOf(ForbiddenError.class);
        assertThat(requestCount(caseId)).isZero();
    }

    @Test
    @DisplayName("a missing key, a reason outside its rule and a missing version are 400s that never echo the reason")
    void validatesTheBody() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", null, null, 0);
        String unsafe = "Uploaded‮in error";

        assertThatThrownBy(() -> deletions.request(CASEWORKER, caseId, documentId,
                                                   new DocumentDeletionCommand(null, unsafe, null)))
            .isInstanceOfSatisfying(InvalidInputError.class, refused -> {
                assertThat(refused.errors()).extracting(FieldError::field).containsExactly(
                    DocumentDeletionService.KEY_FIELD, DocumentDeletionReason.FIELD,
                    DocumentDeletionService.EXPECTED_DOCUMENT_VERSION_FIELD);
                assertThat(refused.getMessage()).doesNotContain("Uploaded");
                assertThat(refused.errors()).extracting(FieldError::message)
                    .noneMatch(message -> message.contains("Uploaded"));
            });
        assertThatThrownBy(() -> deletions.request(CASEWORKER, caseId, documentId,
                                                   new DocumentDeletionCommand(UUID.randomUUID(), "  ", 1L)))
            .isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> deletions.request(CASEWORKER, caseId, documentId,
                                                   new DocumentDeletionCommand(UUID.randomUUID(), REASON, 0L)))
            .isInstanceOf(InvalidInputError.class);
        assertThat(requestCount(caseId)).isZero();
    }

    @Test
    @DisplayName("an unknown case, another case's document, an unknown document and a fenced case are 404s")
    void notFound() {
        String caseId = fixture();
        String other = fixture();
        String documentId = document(caseId, "Local", null, null, 0);
        String othersDocument = document(other, "Local", null, null, 0);

        assertThatThrownBy(() -> admit("no-such-case", documentId, UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> admit(caseId, othersDocument, UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class).hasMessage("Document " + othersDocument + " not found");
        assertThatThrownBy(() -> admit(caseId, UUID.randomUUID().toString(), UUID.randomUUID()))
            .isInstanceOf(NotFoundError.class);
        applicationDeletionRequest(caseId);
        assertThatThrownBy(() -> admit(caseId, documentId, UUID.randomUUID())).isInstanceOf(NotFoundError.class)
            .hasMessage("DeedPoll not found");
        assertThat(requestCount(caseId)).isZero();
        assertThat(requestCount(other)).isZero();
    }

    @Test
    @DisplayName("a version other than the reviewed one is DOCUMENT_CHANGED, and the new one is then accepted")
    void staleVersion() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", null, null, 0);
        jdbc.update("UPDATE deedpoll_supportingdocument SET name = 'Renamed' WHERE id = ?", documentId);

        assertRefused(() -> admit(caseId, documentId, UUID.randomUUID()), DocumentDeletionRefusal.DOCUMENT_CHANGED);
        assertThat(requestCount(caseId)).isZero();
        assertThat(requestedEvents(caseId)).isEmpty();

        Admission admitted = deletions.request(CASEWORKER, caseId, documentId,
                                               new DocumentDeletionCommand(UUID.randomUUID(), REASON, 2L));
        assertThat(admitted.request().documentName()).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("storage in flight or uncertain, or held in a store this deployment does not serve, is unresolved")
    void unresolvedStorage() {
        String caseId = fixture();
        for (String state : List.of("UploadPending", "Uploaded", "AttachUncertain")) {
            String documentId = document(caseId, state, null, UUID.randomUUID(), 1);
            assertRefused(() -> admit(caseId, documentId, UUID.randomUUID()),
                          DocumentDeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
        }
        String mayResend = document(caseId, "UploadUncertain", null, null, 1);
        assertRefused(() -> admit(caseId, mayResend, UUID.randomUUID()),
                      DocumentDeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
        // This deployment is the filesystem store, so an attached CDAM document's bytes are out of its reach.
        String attached = document(caseId, "Attached", null, UUID.randomUUID(), 1);
        assertRefused(() -> admit(caseId, attached, UUID.randomUUID()),
                      DocumentDeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
        assertThat(requestCount(caseId)).isZero();

        // Settled with nothing to remove: metadata only, so admitted with no step.
        for (String documentId : List.of(document(caseId, "UploadUncertain", null, null, 2),
                                         document(caseId, "Failed", null, null, 1),
                                         document(caseId, "Local", null, null, 0))) {
            Admission admitted = admit(caseId, documentId, UUID.randomUUID());
            assertThat(requests.stepsOf(admitted.request().id())).isEmpty();
        }
    }

    @Test
    @DisplayName("an attached document on a CDAM deployment gets the CCD reference step when linked, then CDAM's")
    void cdamSteps() {
        DocumentDeletionService onCdam = new DocumentDeletionService(
            requests, admissionReads, caseLocks, applicationFence, deedPolls, new ServesOnly(), audit);
        String linked = fixture();
        String reference = "1777000022224444";
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?", reference, linked);
        UUID cdam = UUID.randomUUID();
        String attached = document(linked, "Attached", cdam.toString(), cdam, 1);
        String unlinked = fixture();
        UUID unlinkedCdam = UUID.randomUUID();
        String unlinkedDocument = document(unlinked, "Attached", unlinkedCdam.toString(), unlinkedCdam, 1);
        String local = document(unlinked, "Local", "files/" + UUID.randomUUID(), null, 0);

        Admission first = transaction().execute(status -> onCdam.request(
            CASEWORKER, linked, attached, command(UUID.randomUUID())));
        assertThat(requests.stepsOf(first.request().id()))
            .extracting(DocumentDeletionStep::kind, DocumentDeletionStep::locator)
            .containsExactly(tuple(DocumentDeletionStepKind.CCD_REFERENCE, reference),
                             tuple(DocumentDeletionStepKind.CDAM_BINARY,
                                                                 cdam.toString()));
        Admission second = transaction().execute(status -> onCdam.request(
            CASEWORKER, unlinked, unlinkedDocument, command(UUID.randomUUID())));
        assertThat(requests.stepsOf(second.request().id())).extracting(DocumentDeletionStep::kind)
            .containsExactly(DocumentDeletionStepKind.CDAM_BINARY);
        // A filesystem file on a CDAM deployment is out of its reach.
        assertRefused(() -> transaction().execute(status -> onCdam.request(
            CASEWORKER, unlinked, local, command(UUID.randomUUID()))),
                      DocumentDeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
    }

    @Test
    @DisplayName("another current row holding the same file or CDAM document is DOCUMENT_SHARED")
    void sharedBinary() {
        String caseId = fixture();
        String other = fixture();
        String key = "files/" + UUID.randomUUID();
        String documentId = document(caseId, "Local", key, null, 0);
        document(other, "Local", key, null, 0);
        assertRefused(() -> admit(caseId, documentId, UUID.randomUUID()), DocumentDeletionRefusal.DOCUMENT_SHARED);

        UUID cdam = UUID.randomUUID();
        DocumentDeletionService onCdam = new DocumentDeletionService(
            requests, admissionReads, caseLocks, applicationFence, deedPolls, new ServesOnly(), audit);
        String attached = document(caseId, "Attached", cdam.toString(), cdam, 1);
        document(other, "Failed", null, cdam, 1);
        assertRefused(() -> transaction().execute(status -> onCdam.request(
            CASEWORKER, caseId, attached, command(UUID.randomUUID()))), DocumentDeletionRefusal.DOCUMENT_SHARED);
        assertThat(requestCount(caseId)).isZero();
    }

    @Test
    @DisplayName("a pending email, a retained deed preparation or a promoting stage is DOCUMENT_IN_USE; history is not")
    void inUse() {
        String caseId = fixture();
        String emailed = document(caseId, "Local", null, null, 0);
        final UUID email = emailNaming(caseId, emailed);
        assertRefused(() -> admit(caseId, emailed, UUID.randomUUID()), DocumentDeletionRefusal.DOCUMENT_IN_USE);
        assertThat(DocumentDeletionRefusal.DOCUMENT_IN_USE.message()).isEqualTo(
            "This document is currently being used and cannot be deleted. "
                + "Try again once the operation has finished.");

        String deed = document(caseId, "Local", null, null, 0);
        jdbc.update("""
            INSERT INTO deedpoll_deedpreparation
                ("deedPollId", "documentId", template, "originalName", "mimeType", "sizeBytes", checksum)
            VALUES (?, ?, 'ADULT', 'deed.docx', 'application/test', 3, encode(sha256(decode('010203', 'hex')), 'hex'))
            """, caseId, deed);
        payload(deed);
        assertRefused(() -> admit(caseId, deed, UUID.randomUUID()), DocumentDeletionRefusal.DOCUMENT_IN_USE);

        String promoted = document(caseId, "Local", null, null, 0);
        final String stage = stage(caseId, promoted, "Promoting");
        assertRefused(() -> admit(caseId, promoted, UUID.randomUUID()), DocumentDeletionRefusal.DOCUMENT_IN_USE);
        assertThat(requestCount(caseId)).isZero();

        // Once each settles it is history, and blocks nothing.
        jdbc.update("UPDATE deedpoll_notification SET state = 'Sent' WHERE id = ?", email);
        jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", deed);
        jdbc.update("UPDATE deedpoll_ownedstage SET state = 'Consumed' WHERE id = ?", stage);
        assertThat(admit(caseId, emailed, UUID.randomUUID()).admitted()).isTrue();
        assertThat(admit(caseId, deed, UUID.randomUUID()).admitted()).isTrue();
        assertThat(admit(caseId, promoted, UUID.randomUUID()).admitted()).isTrue();
    }

    @Test
    @DisplayName("every document key any template declares is searched, from EmailTemplate itself")
    void documentKeysComeFromTheTemplates() {
        assertThat(DocumentDeletionService.documentKeys()).contains("deed_document").isNotEmpty();
    }

    @Test
    @DisplayName("a replay is the first request; the same key with another body is KEY_REUSED; nothing is duplicated")
    void replays() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", null, null, 0);
        final String another = document(caseId, "Local", null, null, 0);
        UUID key = UUID.randomUUID();
        DocumentDeletionRequest first = admit(caseId, documentId, key).request();

        Admission same = admit(caseId, documentId, key);
        assertThat(same.admitted()).isFalse();
        assertThat(same.request().id()).isEqualTo(first.id());

        assertRefused(() -> deletions.request(CASEWORKER, caseId, documentId,
                                              new DocumentDeletionCommand(key, "Duplicate", 1L)),
                      DocumentDeletionRefusal.KEY_REUSED);
        assertRefused(() -> admit(caseId, another, key), DocumentDeletionRefusal.KEY_REUSED);

        // Another member of staff, another key, the same body: that request, its requester kept.
        CurrentUser second = CurrentUser.currentUser(List.of(Role.TEAM_LEADER), "Terry Leader", "team-leader");
        Admission otherTab = deletions.request(second, caseId, documentId, command(UUID.randomUUID()));
        assertThat(otherTab.admitted()).isFalse();
        assertThat(otherTab.request().id()).isEqualTo(first.id());
        assertThat(otherTab.request().requestedBySubject()).isEqualTo(SUBJECT);
        // Another key and another reason meets a document that has gone, for everyone else.
        assertThatThrownBy(() -> deletions.request(second, caseId, documentId,
                                                   new DocumentDeletionCommand(UUID.randomUUID(), "Duplicate", 1L)))
            .isInstanceOf(NotFoundError.class);

        // The replay answers after the document row is gone.
        jdbc.update("DELETE FROM deedpoll_supportingdocument WHERE id = ?", documentId);
        assertThat(admit(caseId, documentId, key).request().id()).isEqualTo(first.id());

        assertThat(requestCount(caseId)).isOne();
        assertThat(requestedEvents(caseId)).hasSize(1);
        assertThat(deletions.read(CASEWORKER, caseId, first.id()).id()).isEqualTo(first.id());
        String other = fixture();
        assertThatThrownBy(() -> deletions.read(CASEWORKER, other, first.id())).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("an audit insert that fails leaves no request, no step and no fence")
    void auditFailureRollsBack() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        jdbc.execute("""
            CREATE FUNCTION document_deletion_test_audit_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.action = 'DOCUMENT_DELETION_REQUESTED' THEN
                    RAISE EXCEPTION 'audit refused for the test';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("CREATE TRIGGER document_deletion_test_audit_failure BEFORE INSERT ON deedpoll_audit_event "
                         + "FOR EACH ROW EXECUTE FUNCTION document_deletion_test_audit_failure()");

        assertThatThrownBy(() -> admit(caseId, documentId, UUID.randomUUID())).isInstanceOf(RuntimeException.class);

        assertThat(requestCount(caseId)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_document_deletion_step")).isZero();
        assertThat(documentService.readableDocument(caseId, documentId).getId()).isEqualTo(documentId);
    }

    // --- the fence ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("from admission the document is gone to reads, previews, writes and new emails; its siblings are not")
    void fencesTheDocumentOnly() {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        final String sibling = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        admit(caseId, documentId, UUID.randomUUID());

        assertThatThrownBy(() -> documentService.findDocument(caseId, documentId)).isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documentService.readableDocument(caseId, documentId))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documentService.previewDocument(caseId, documentId, CASEWORKER))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documentService.downloadDocument(caseId, documentId, CASEWORKER))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documentService.updateDocument(caseId, documentId, rename("Changed")))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> documentService.removeDocument(caseId, documentId)).isInstanceOf(NotFoundError.class);
        assertThat(documentService.findDocuments(caseId)).extracting("id").containsExactly(sibling);
        Optional<DeedPollSupportingDocument> locked = transaction().execute(
            status -> documentRows.findUnfencedByIdAndDeedPollIdForUpdate(documentId, caseId));
        assertThat(locked).isEmpty();
        // A new email naming it is refused at commit; one naming the sibling is not.
        assertThatThrownBy(() -> emailNaming(caseId, documentId)).isInstanceOf(RuntimeException.class);
        emailNaming(caseId, sibling);

        assertThat(documentService.readableDocument(caseId, sibling).getId()).isEqualTo(sibling);
        assertThat(documentService.updateDocument(caseId, sibling, rename("Still usable")).getName())
            .isEqualTo("Still usable");
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
        assertThat(jdbc.queryForObject("SELECT name FROM deedpoll_supportingdocument WHERE id = ?", String.class,
                                       documentId)).isEqualTo("Evidence");
    }

    // --- races ------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("an email naming the document that commits first makes admission DOCUMENT_IN_USE with no side effect")
    void queuedEmailBeforeAdmission() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch enqueued = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> sender = pool.submit(() -> transaction().executeWithoutResult(status -> {
                emailNaming(caseId, documentId);
                enqueued.countDown();
                await(release);
            }));
            assertThat(enqueued.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> admission = pool.submit(() -> outcome(() -> admit(caseId, documentId,
                                                                               UUID.randomUUID())));
            awaitLockWaiters(1);
            release.countDown();
            sender.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                DocumentDeletionConflictError.class,
                refused -> assertThat(refused.refusal()).isEqualTo(DocumentDeletionRefusal.DOCUMENT_IN_USE));
        } finally {
            pool.shutdownNow();
        }
        assertThat(requestCount(caseId)).isZero();
        assertThat(requestedEvents(caseId)).isEmpty();
        assertThat(documentService.readableDocument(caseId, documentId).getId()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("an email naming the document that waits on admission is refused at commit, and writes nothing")
    void queuedEmailAfterAdmission() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        String sibling = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> admission = pool.submit(() -> transaction().executeWithoutResult(status -> {
                admit(caseId, documentId, UUID.randomUUID());
                admitted.countDown();
                await(release);
            }));
            assertThat(admitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> naming = pool.submit(() -> outcome(() -> emailNaming(caseId, documentId)));
            final Future<Object> other = pool.submit(() -> outcome(() -> emailNaming(caseId, sibling)));
            awaitLockWaiters(2);
            release.countDown();
            admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(naming.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                RuntimeException.class, refused -> assertThat(rootMessage(refused))
                    .contains("The document is being deleted"));
            assertThat(other.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(UUID.class);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("SELECT count(*) FROM deedpoll_notification WHERE \"deedPollId\" = ? "
                             + "AND personalisation ->> 'deed_document' = ?", caseId, documentId)).isZero();
        assertThat(requestCount(caseId)).isOne();
    }

    @Test
    @DisplayName("a document write that commits while admission waits makes it DOCUMENT_CHANGED")
    void writeBeforeAdmission() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch written = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            // As the attach handler does: the case's key-share lock, then the document's change.
            final Future<?> writer = pool.submit(() -> transaction().executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR KEY SHARE", caseId);
                jdbc.update("UPDATE deedpoll_supportingdocument SET name = 'Replaced' WHERE id = ?", documentId);
                written.countDown();
                await(release);
            }));
            assertThat(written.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> admission = pool.submit(() -> outcome(() -> admit(caseId, documentId,
                                                                               UUID.randomUUID())));
            awaitLockWaiters(1);
            release.countDown();
            writer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOfSatisfying(
                DocumentDeletionConflictError.class,
                refused -> assertThat(refused.refusal()).isEqualTo(DocumentDeletionRefusal.DOCUMENT_CHANGED));
        } finally {
            pool.shutdownNow();
        }
        assertThat(requestCount(caseId)).isZero();
    }

    @Test
    @DisplayName("a replace, a soft removal and a state move waiting on admission find nothing; a new upload lands")
    void writesAfterAdmission() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> admission = pool.submit(() -> transaction().executeWithoutResult(status -> {
                admit(caseId, documentId, UUID.randomUUID());
                admitted.countDown();
                await(release);
            }));
            assertThat(admitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            final Future<Object> replace = pool.submit(() -> outcome(
                () -> documentService.updateDocument(caseId, documentId, rename("Replaced"))));
            final Future<Object> remove = pool.submit(() -> outcome(() -> {
                documentService.removeDocument(caseId, documentId);
                return "removed";
            }));
            final Future<Object> move = pool.submit(() -> outcome(() -> transaction().execute(
                status -> documentRows.findUnfencedByIdAndDeedPollIdForUpdate(documentId, caseId))));
            // A new upload's insert takes the case's key-share lock and waits; a new document is not fenced.
            final Future<Object> upload = pool.submit(() -> outcome(
                () -> document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0)));
            awaitLockWaiters(4);
            release.countDown();
            admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertThat(replace.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(NotFoundError.class);
            assertThat(remove.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isInstanceOf(NotFoundError.class);
            assertThat(move.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(Optional.empty());
            Object uploaded = upload.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(uploaded).isInstanceOf(String.class);
            assertThat(documentService.readableDocument(caseId, (String) uploaded)).isNotNull();
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT name FROM deedpoll_supportingdocument WHERE id = ?", String.class,
                                       documentId)).isEqualTo("Evidence");
        assertThat(count("SELECT count(*) FROM deedpoll_removed_document WHERE id = ?", documentId)).isZero();
    }

    @Test
    @DisplayName("a read is never blocked by admission, and once admission commits a new read is refused")
    void readsAreNotLeased() throws Exception {
        String caseId = fixture();
        String documentId = document(caseId, "Local", "files/" + UUID.randomUUID(), null, 0);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch admitted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            final Future<?> admission = pool.submit(() -> transaction().executeWithoutResult(status -> {
                admit(caseId, documentId, UUID.randomUUID());
                admitted.countDown();
                await(release);
            }));
            assertThat(admitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            // Uncommitted: a reader neither waits nor is refused, because nothing locks around reads.
            final Future<Object> during = pool.submit(() -> outcome(
                () -> documentService.readableDocument(caseId, documentId).getId()));
            assertThat(during.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(documentId);
            release.countDown();
            admission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThatThrownBy(() -> documentService.readableDocument(caseId, documentId))
            .isInstanceOf(NotFoundError.class);
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    /**
     * A store that serves only CDAM's attached rows, as {@code CdamDocumentStore} does; it is never called.
     */
    private static final class ServesOnly implements DocumentStore {

        @Override
        public StorageReceipt save(DocumentToStore document) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream open(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(String storageKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean serves(DocumentStorageState state) {
            return state == DocumentStorageState.Attached;
        }
    }

    private String fixture() {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference")
            VALUES (?, now(), 'Adult'::"DeedPollType", 'Enroled', ?)
            """, id, "document-deletion-" + id);
        return id;
    }

    private String document(String caseId, String state, String storageKey, UUID cdamId, int uploads) {
        DeletionTestDocumentType.ensure(jdbc);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "cdamDocumentId", "cdamHashToken",
                "storageUploadAttempts")
            VALUES (?, ?, 'Incoming', 'Evidence', ?, ?, now(), ?, ?, ?, ?)
            """, id, caseId, DeletionTestDocumentType.ID, storageKey, state, cdamId,
            "Uploaded".equals(state) || "AttachUncertain".equals(state) ? "a".repeat(64) : null, uploads);
        return id;
    }

    /** A pending forms email whose frozen personalisation names the document, through the outbox's own insert. */
    private UUID emailNaming(String caseId, String documentId) {
        UUID id = UUID.randomUUID();
        Integer written = transaction().execute(status -> notifications.enqueue(
            id, caseId, UUID.randomUUID(), null, "FORMS_FOR_COMPLETION_ADULT", "FORMS_FOR_COMPLETION_TEST_" + id,
            "applicant@example.com", "{\"deed_document\": \"" + documentId + "\"}",
            LocalDateTime.now(ZoneOffset.UTC), "Fixture"));
        assertThat(written).isOne();
        return id;
    }

    private void payload(String id) {
        jdbc.update("""
            INSERT INTO deedpoll_stagedpayload (id, content, "sizeBytes", checksum)
            VALUES (?, decode('010203', 'hex'), 3, encode(sha256(decode('010203', 'hex')), 'hex'))
            """, id);
    }

    private String stage(String caseId, String documentId, String state) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_ownedstage
              (id, "ownerKind", "ownerId", subject, "operationId", "originalName", "mimeType", "sizeBytes",
               checksum, "createdOn", "expiresOn", state, "caseId", "documentId", "documentTypeId")
            VALUES (?, 'RECORD', ?, 'synthetic-subject', ?, 'synthetic.pdf', 'application/pdf', 3,
                    encode(sha256(decode('010203', 'hex')), 'hex'),
                    CURRENT_TIMESTAMP - interval '2 hours', CURRENT_TIMESTAMP + interval '1 hour', ?, ?, ?, ?)
            """, id, caseId, UUID.randomUUID(), state, caseId, documentId, DeletionTestDocumentType.ID);
        return id;
    }

    private void applicationDeletionRequest(String caseId) {
        jdbc.update("""
            INSERT INTO deedpoll_deletion_request (id, case_id, idempotency_key, reason, note, fingerprint,
                expected_last_changed_on, requested_by_subject, requested_by_name, requested_at, restorable_until,
                status)
            VALUES (?, ?, ?, 'DUPLICATE', 'Fixture fence for a document deletion', repeat('a', 64),
                    now(), 'dm-subject', 'Dee Manager', now(), now() + interval '14 days', 'Pending')
            """, UUID.randomUUID(), caseId, UUID.randomUUID());
    }

    private static UpdateDocumentInput rename(String name) {
        return new UpdateDocumentInput(PatchField.absent(), PatchField.of(name), PatchField.absent(),
                                       PatchField.absent(), PatchField.absent(), PatchField.absent());
    }

    private static DocumentDeletionCommand command(UUID key) {
        return new DocumentDeletionCommand(key, REASON, 1L);
    }

    private Admission admit(String caseId, String documentId, UUID key) {
        return deletions.request(CASEWORKER, caseId, documentId, command(key));
    }

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactions);
    }

    private int requestCount(String caseId) {
        return count("SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?", caseId);
    }

    private int count(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }

    private List<Map<String, Object>> requestedEvents(String caseId) {
        return jdbc.queryForList("""
            SELECT actor_id, actor_name, entity_type, entity_id, detail::text AS detail,
                   (SELECT count(*) FROM deedpoll_audit_event_value v WHERE v.event_id = e.id) AS value_rows
              FROM deedpoll_audit_event e WHERE case_id = ? AND action = 'DOCUMENT_DELETION_REQUESTED'
            """, caseId);
    }

    private static void assertRefused(Runnable call, DocumentDeletionRefusal expected) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(
            DocumentDeletionConflictError.class, refused -> {
                assertThat(refused.refusal()).isEqualTo(expected);
                assertThat(refused.getMessage()).isEqualTo(expected.message());
            });
    }

    private static String rootMessage(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            messages.append(cause.getMessage()).append('\n');
        }
        return messages.toString();
    }

    private static Object outcome(Supplier<Object> call) {
        try {
            return call.get();
        } catch (RuntimeException refused) {
            return refused;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("never released");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void awaitLockWaiters(int count) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity
                WHERE wait_event_type = 'Lock' AND datname = current_database()
                """, Integer.class);
            if (waiting != null && waiting >= count) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
        throw new IllegalStateException("the writers never waited on admission's locks");
    }
}
