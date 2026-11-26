package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.ApplicationDeletionService.DeletionCommand;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRefusal;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionRequest;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.DocumentDeletionCommand;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.CaseLastChanged;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.NewDocumentDeletionRequest;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Permanent document deletion meeting whole-application deletion, against real Postgres and real transactions (plan
 * 20261125-permanent-document-deletion, Task 5): a document request that has not completed refuses application
 * admission and writes nothing; a completed one is recognised as its own receipt and listed nowhere, while historical
 * V50 tombstones keep their conservative lines; the application's fence and restore leave a permanently deleted
 * document deleted; and the two admissions racing on one case let exactly one through.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentDeletionApplicationDeletionTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "document-and-application-deletion";
    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(Role.CASEWORKER), "Casey Worker", SUBJECT);
    private static final CurrentUser DELIVERY_MANAGER =
        CurrentUser.currentUser(List.of(Role.DELIVERY_MANAGER), "Dee Manager", SUBJECT + "-dm");
    private static final String REASON = "Uploaded in error";
    private static final String NOTE = "Logged twice from the same postal application";
    private static final int TIMEOUT_SECONDS = 20;

    @Autowired
    private DocumentDeletionService documentAdmission;

    @Autowired
    private DocumentDeletions documentDeletions;

    @Autowired
    private DocumentDeletionRequestRepository documentRequests;

    @Autowired
    private DocumentDeletionCompletion completion;

    @Autowired
    private ApplicationDeletionService applications;

    @Autowired
    private DeletionRequestRepository applicationRequests;

    @Autowired
    private DeedPollSupportingDocumentRepository documentRows;

    @Autowired
    private CaseLastChanged lastChanged;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    private final List<String> fixtures = new ArrayList<>();
    private final DocumentDeletionWorkTest.ScriptedRemote remote = new DocumentDeletionWorkTest.ScriptedRemote();
    private DocumentDeletionWork work;

    @BeforeEach
    void setUp() {
        DocumentDeletionProperties properties = new DocumentDeletionProperties(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(15),
            new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), 10, Duration.ofMinutes(2), 3,
                                                    Duration.ofMinutes(1), Duration.ofHours(1)));
        work = new DocumentDeletionWork(documentRequests, remote, completion, transactions, properties,
                                        () -> AuditClock.now().truncatedTo(ChronoUnit.MILLIS));
    }

    @AfterEach
    void cleanUp() {
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deletion_request WHERE case_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- unresolved document deletions refuse the application ----------------------------------------------------

    @Test
    @DisplayName("an admitted document deletion still InProgress refuses application admission, writing nothing")
    void inProgressRefusesTheApplication() {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        DocumentDeletionRequest admitted = documentAdmission.request(CASEWORKER, caseId, documentId,
                                                                     documentCommand()).request();
        assertThat(admitted.status()).isEqualTo(DocumentDeletionStatus.InProgress);

        refusesTheApplication(caseId, DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED);

        assertThat(documentRequests.findById(admitted.id()).orElseThrow().status())
            .isEqualTo(DocumentDeletionStatus.InProgress);
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
    }

    @Test
    @DisplayName("a document deletion that needs attention (an unproven CDAM purge) refuses application admission "
        + "until it is resolved")
    void needsAttentionRefusesTheApplication() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        DocumentDeletionRequest admitted = cdamAdmitted(caseId, documentId, reference, cdamId);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.failed("NOT_FOUND", DocumentDeletionFailure.CDAM_PURGE_UNPROVEN));

        assertThat(carriedOut(admitted.id(), work.deadline())).contains(DocumentDeletionStatus.NeedsAttention);

        refusesTheApplication(caseId, DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED);
        assertThat(documentRequests.stepsOf(admitted.id()))
            .filteredOn(step -> step.kind() == DocumentDeletionStepKind.CDAM_BINARY)
            .singleElement().satisfies(step -> assertThat(step.locator()).isEqualTo(cdamId.toString()));
    }

    @Test
    @DisplayName("a partly done document deletion (CCD confirmed, CDAM unknown) refuses the application as well")
    void partlyDoneRefusesTheApplication() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        DocumentDeletionRequest admitted = cdamAdmitted(caseId, documentId, reference, cdamId);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.retry("SERVER_ERROR"));

        assertThat(carriedOut(admitted.id(), work.deadline())).contains(DocumentDeletionStatus.InProgress);

        refusesTheApplication(caseId, DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED);
    }

    // --- completed document deletions are receipts -----------------------------------------------------------------

    @Test
    @DisplayName("after a completed CDAM and a completed metadata-only deletion the application is admitted, listing "
        + "neither, while the case's other documents and historical tombstones keep their lines")
    void completedDeletionsAreReceipts() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID deletedCdam = UUID.randomUUID();
        String cdamDocument = document(caseId, "Attached", null, deletedCdam);
        final String metadataOnly = document(caseId, "Local", null, null);
        UUID keptCdam = UUID.randomUUID();
        final String keptAttached = document(caseId, "Attached", null, keptCdam);
        final String keptLocal = document(caseId, "Local", "files/kept-" + caseId, null);
        tombstone(caseId, "removed-local-" + caseId, "Local", "files/removed-" + caseId);
        String softRemoved = UUID.randomUUID().toString();
        tombstone(caseId, "removed-cdam-" + caseId, "Attached", softRemoved);

        DocumentDeletionRequest cdam = cdamAdmitted(caseId, cdamDocument, reference, deletedCdam);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.done("DELETED"));
        assertThat(carriedOut(cdam.id(), work.deadline())).contains(DocumentDeletionStatus.Completed);
        assertThat(documentDeletions.delete(CASEWORKER, caseId, metadataOnly, documentCommand()).request().status())
            .isEqualTo(DocumentDeletionStatus.Completed);

        DeletionRequest application = admitApplication(caseId).request();

        assertThat(applicationRequests.stepsOf(application.id()))
            .extracting(step -> step.kind() + ":" + step.itemId() + ":" + step.locator())
            .containsExactlyInAnyOrder(
                "CDAM_DOCUMENT:" + keptAttached + ":" + keptCdam,
                "LOCAL_DOCUMENT:" + keptLocal + ":files/kept-" + caseId,
                "LOCAL_DOCUMENT:removed-local-" + caseId + ":files/removed-" + caseId,
                "CDAM_REMOVED_DOCUMENT:removed-cdam-" + caseId + ":" + softRemoved,
                "CCD_DISPOSAL:null:" + reference);
        assertThat(documentRequests.findById(cdam.id()).orElseThrow().status())
            .isEqualTo(DocumentDeletionStatus.Completed);
    }

    @Test
    @DisplayName("a tombstone that names a completed permanent deletion is recognised by its receipt and never sent "
        + "to the dm-store team's manual purge; a historical soft removal still is")
    void receiptOverridesATombstone() {
        String reference = reference();
        String caseId = fixture(reference);
        String documentId = document(caseId, "Local", null, null);
        assertThat(documentDeletions.delete(CASEWORKER, caseId, documentId, documentCommand()).request().status())
            .isEqualTo(DocumentDeletionStatus.Completed);
        tombstone(caseId, documentId, "Attached", UUID.randomUUID().toString());
        String softRemoved = UUID.randomUUID().toString();
        tombstone(caseId, "removed-cdam-" + caseId, "Attached", softRemoved);

        DeletionRequest application = admitApplication(caseId).request();

        assertThat(applicationRequests.stepsOf(application.id()))
            .extracting(step -> step.kind() + ":" + step.itemId())
            .containsExactlyInAnyOrder("CDAM_REMOVED_DOCUMENT:removed-cdam-" + caseId, "CCD_DISPOSAL:null");
    }

    // --- the application's fence and restore ------------------------------------------------------------------------

    @Test
    @DisplayName("an admitted application fences document deletion; a restore keeps the permanently deleted document "
        + "deleted with its receipt and note, and admits document deletion again")
    void fenceAndRestore() {
        String caseId = fixture(null);
        String deleted = document(caseId, "Local", null, null);
        String other = document(caseId, "Local", null, null);
        DocumentDeletionRequest receipt = documentDeletions.delete(CASEWORKER, caseId, deleted, documentCommand())
            .request();
        assertThat(receipt.status()).isEqualTo(DocumentDeletionStatus.Completed);

        DeletionRequest application = admitApplication(caseId).request();
        assertThatThrownBy(() -> documentAdmission.request(CASEWORKER, caseId, other, documentCommand()))
            .isInstanceOf(NotFoundError.class);
        assertThat(count("SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?", caseId))
            .isOne();

        assertThat(applications.restore(DELIVERY_MANAGER, application.id())).isEqualTo(caseId);

        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", deleted)).isZero();
        assertThat(documentRows.findById(deleted)).isEmpty();
        assertThat(documentRequests.findById(receipt.id()).orElseThrow().status())
            .isEqualTo(DocumentDeletionStatus.Completed);
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'DOCUMENT_DELETED'",
                         caseId)).isOne();
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ? AND note LIKE ?", caseId,
                         "Document % (" + deleted + ") permanently deleted.%")).isOne();
        assertThat(documentDeletions.delete(CASEWORKER, caseId, other, documentCommand()).request().status())
            .isEqualTo(DocumentDeletionStatus.Completed);
    }

    // --- both admissions racing -------------------------------------------------------------------------------------

    @Test
    @DisplayName("an application admission queued first on the case lock wins; the document deletion behind it is "
        + "refused and writes nothing")
    void applicationFirstWins() throws Exception {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        DeletionCommand application = applicationCommand(caseId);

        List<Object> outcomes = race(caseId,
            () -> applications.request(DELIVERY_MANAGER, caseId, application),
            () -> documentAdmission.request(CASEWORKER, caseId, documentId, documentCommand()));

        assertThat(outcomes.get(0)).isInstanceOfSatisfying(Admission.class,
            admitted -> assertThat(admitted.admitted()).isTrue());
        assertThat(outcomes.get(1)).isInstanceOfSatisfying(DocumentDeletionConflictError.class,
            refused -> assertThat(refused.refusal()).isEqualTo(DocumentDeletionRefusal.APPLICATION_DELETION_PENDING));
        assertThat(count("SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?", caseId))
            .isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? "
                             + "AND action = 'DOCUMENT_DELETION_REQUESTED'", caseId)).isZero();
    }

    @Test
    @DisplayName("a document deletion queued first on the case lock wins; the application admission behind it is "
        + "refused and writes nothing, and is refused for the unresolved deletion once it reviews the case again")
    void documentFirstWins() throws Exception {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        DeletionCommand application = applicationCommand(caseId);

        List<Object> outcomes = race(caseId,
            () -> documentAdmission.request(CASEWORKER, caseId, documentId, documentCommand()),
            () -> applications.request(DELIVERY_MANAGER, caseId, application));

        assertThat(outcomes.get(0)).isInstanceOfSatisfying(DocumentDeletionService.Admission.class,
            admitted -> assertThat(admitted.admitted()).isTrue());
        // The document admission's audited request moved lastChangedOn, so the stale check answers first.
        assertThat(outcomes.get(1)).isInstanceOfSatisfying(DeletionConflictError.class,
            refused -> assertThat(refused.refusal()).isIn(DeletionRefusal.STALE_CONFIRMATION,
                                                          DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED));
        assertThat(count("SELECT count(*) FROM deedpoll_deletion_request WHERE case_id = ?", caseId)).isZero();
        refusesTheApplication(caseId, DeletionRefusal.DOCUMENT_DELETION_UNRESOLVED);
    }

    // --- fixtures ---------------------------------------------------------------------------------------------------

    private void refusesTheApplication(String caseId, DeletionRefusal expected) {
        int documentRequestsBefore = count(
            "SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?", caseId);
        assertThatThrownBy(() -> admitApplication(caseId))
            .isInstanceOfSatisfying(DeletionConflictError.class, error -> {
                assertThat(error.refusal()).isEqualTo(expected);
                assertThat(error.getMessage()).isEqualTo(expected.message()).doesNotContain(caseId);
            });
        assertThat(count("SELECT count(*) FROM deedpoll_deletion_request WHERE case_id = ?", caseId)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? "
                             + "AND action = 'CASE_DELETION_REQUESTED'", caseId)).isZero();
        assertThat(count("SELECT count(*) FROM deedpoll_document_deletion_request WHERE case_id = ?", caseId))
            .isEqualTo(documentRequestsBefore);
    }

    private Admission admitApplication(String caseId) {
        return applications.request(DELIVERY_MANAGER, caseId, applicationCommand(caseId));
    }

    /** As the Delivery Manager reviewed the case: its lastChangedOn now. */
    private DeletionCommand applicationCommand(String caseId) {
        return new DeletionCommand(UUID.randomUUID(), "DUPLICATE", NOTE, lastChanged.of(caseId).orElseThrow());
    }

    private static DocumentDeletionCommand documentCommand() {
        return new DocumentDeletionCommand(UUID.randomUUID(), REASON, 1L);
    }

    /**
     * Holds the case lock until the first call waits on it, then queues the second behind it, then lets both go:
     * Postgres grants a row lock to its waiters in the order they queued.
     */
    private List<Object> race(String caseId, Call first, Call second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM deedpoll_deedpoll WHERE id = ? FOR UPDATE", caseId);
                futures.add(pool.submit(() -> attempt(first)));
                awaitLockWaiters(1);
                futures.add(pool.submit(() -> attempt(second)));
                awaitLockWaiters(2);
            });
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface Call {
        Object run();
    }

    private static Object attempt(Call call) {
        try {
            return call.run();
        } catch (RuntimeException refused) {
            return refused;
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
        throw new IllegalStateException("the admissions never waited on the case lock");
    }

    private String fixture(String ccdReference) {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "ccdCaseReference")
            VALUES (?, now(), 'Adult'::"DeedPollType", 'Enroled', ?, ?)
            """, id, "both-deletions-" + id, ccdReference);
        return id;
    }

    private static String reference() {
        return String.valueOf(ThreadLocalRandom.current().nextLong(1_000_000_000_000_000L, 9_999_999_999_999_999L));
    }

    private String document(String caseId, String state, String storageKey, UUID cdamId) {
        DeletionTestDocumentType.ensure(jdbc);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument (id, "deedPollId", direction, name, "documentTypeId",
                "storageKey", "modifiedOn", "storageState", "cdamDocumentId", "originalName", "createdOn")
            VALUES (?, ?, 'Incoming', 'Evidence', ?, ?, now(), ?, ?, 'evidence.pdf', clock_timestamp())
            """, id, caseId, DeletionTestDocumentType.ID, storageKey == null && cdamId != null
                ? cdamId.toString() : storageKey, state, cdamId);
        return id;
    }

    /**
     * A CDAM document's request as admission writes it, with its CCD and CDAM steps: this context serves the
     * filesystem store, so its admission would refuse a CDAM document as storage it cannot remove.
     */
    private DocumentDeletionRequest cdamAdmitted(String caseId, String documentId, String reference, UUID cdamId) {
        UUID id = UUID.randomUUID();
        DocumentDeletionReason reason = DocumentDeletionReason.parse(REASON);
        assertThat(documentRequests.insert(new NewDocumentDeletionRequest(
            id, caseId, documentId, UUID.randomUUID(), reason, "Evidence",
            DocumentDeletionFingerprint.of(documentId, 1, reason), 1, SUBJECT, "Casey Worker",
            AuditClock.now()))).isTrue();
        documentRequests.insertStep(UUID.randomUUID(), id, DocumentDeletionStepKind.CCD_REFERENCE, reference);
        documentRequests.insertStep(UUID.randomUUID(), id, DocumentDeletionStepKind.CDAM_BINARY, cdamId.toString());
        return documentRequests.findById(id).orElseThrow();
    }

    private void tombstone(String caseId, String documentId, String state, String locator) {
        jdbc.update("""
            INSERT INTO deedpoll_removed_document (id, case_id, storage_state, locator, removed_at)
            VALUES (?, ?, ?, ?, now())
            """, documentId, caseId, state, locator);
    }

    private int count(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }

    /** Carries the request out and answers the status it has afterwards. */
    private Optional<DocumentDeletionStatus> carriedOut(UUID requestId, LocalDateTime deadline) {
        return work.carryOut(requestId, deadline).map(DocumentDeletionRequest::status);
    }
}
