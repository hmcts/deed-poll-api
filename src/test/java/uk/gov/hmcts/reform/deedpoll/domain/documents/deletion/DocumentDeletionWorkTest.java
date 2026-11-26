package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.ccd.SupportingDocumentReference;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deletion.DeletionTestDocumentType;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionRemote.StepOutcome;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.DocumentDeletionCommand;
import uk.gov.hmcts.reform.deedpoll.domain.documents.deletion.DocumentDeletionService.Admission;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.NewDocumentDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.storage.LocalFilesystemDocumentStore;
import uk.gov.hmcts.reform.deedpoll.storage.PermanentDocumentRemoval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Carrying a permanent document deletion out against real Postgres and real transactions (plan
 * 20261125-permanent-document-deletion, Task 4): the remote steps in order with their receipts, the budget, the
 * recovery sweep, crashes, lost leases, duplicate completion, a failed note or audit insert, and two concurrent
 * removals on one case. The remote answers are scripted; the CDAM and CCD clients' own answers are
 * {@code CdamPermanentDeleteTest}'s and {@code PermanentDocumentRemovalTest}'s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentDeletionWorkTest extends AbstractPostgresContainerTest {

    private static final String SUBJECT = "document-deletion-requester";
    private static final String NAME = "Casey Worker";
    private static final CurrentUser CASEWORKER = CurrentUser.currentUser(List.of(Role.CASEWORKER), NAME, SUBJECT);
    private static final String REASON = "Uploaded in error";
    private static final int MAX_ATTEMPTS = 3;

    @Autowired
    private DocumentDeletionService admission;

    @Autowired
    private DocumentDeletions deletions;

    @Autowired
    private DocumentDeletionRequestRepository requests;

    @Autowired
    private DocumentDeletionCompletion completion;

    @Autowired
    private DeedPollSupportingDocumentRepository documentRows;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Value("${deedpoll.documents.local-store-root:data/documents}")
    private String storeRoot;

    private final List<String> fixtures = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();
    private final AtomicReference<LocalDateTime> clock = new AtomicReference<>();
    private final ScriptedRemote remote = new ScriptedRemote();
    private DocumentDeletionWork work;
    private DocumentDeletionRecovery recovery;

    @BeforeEach
    void setUp() {
        clock.set(AuditClock.now().plusSeconds(1).truncatedTo(ChronoUnit.MILLIS));
        DocumentDeletionProperties properties = new DocumentDeletionProperties(
            Duration.ofSeconds(20), Duration.ofSeconds(2), Duration.ofSeconds(8), Duration.ofSeconds(15),
            new DocumentDeletionProperties.Recovery(true, Duration.ofMinutes(1), 10, Duration.ofMinutes(2),
                                                    MAX_ATTEMPTS, Duration.ofMinutes(1), Duration.ofHours(1)));
        work = new DocumentDeletionWork(requests, remote, completion, transactions, properties, clock::get);
        recovery = new DocumentDeletionRecovery(work, properties);
    }

    @AfterEach
    void cleanUp() throws IOException {
        jdbc.execute("DROP TRIGGER IF EXISTS document_deletion_test_note_failure ON deedpoll_notes");
        jdbc.execute("DROP FUNCTION IF EXISTS document_deletion_test_note_failure()");
        jdbc.execute("DROP TRIGGER IF EXISTS document_deletion_test_audit_failure ON deedpoll_audit_event");
        jdbc.execute("DROP FUNCTION IF EXISTS document_deletion_test_audit_failure()");
        for (String id : fixtures) {
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
        DeletionTestDocumentType.removeIfUnused(jdbc);
    }

    // --- success ----------------------------------------------------------------------------------------------

    @Test
    @DisplayName("a metadata-only document completes at once: row gone, one DOCUMENT_DELETED and one note, as the "
        + "requester")
    void metadataOnlyCompletes() {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        DocumentDeletionRequest admitted = admission.request(CASEWORKER, caseId, documentId, command()).request();

        assertThat(carriedOut(admitted.id(), work.deadline())).contains(DocumentDeletionStatus.Completed);

        assertThat(remote.calls).isEmpty();
        assertCompletedOnce(caseId, documentId, admitted.id());
    }

    @Test
    @DisplayName("an attached document of a linked case: the CCD event, then CDAM's permanent delete, then completion")
    void cdamDocumentCompletesInOrder() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, reference, cdamId, null);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.done("DELETED"));

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.Completed);

        assertThat(remote.calls).containsExactly("CCD:" + reference, "CDAM:" + cdamId);
        assertCompletedOnce(caseId, documentId, requestId);
        assertThat(requests.stepsOf(requestId)).allSatisfy(step -> {
            assertThat(step.state()).isEqualTo(DocumentDeletionStepState.Done);
            assertThat(step.locator()).isNull();
        });
    }

    @Test
    @DisplayName("a filesystem document through the staff entry point: the file verified gone, 200's Completed, and "
        + "a replay of the key answers the same request with no second note")
    void filesystemDocumentEndToEnd() throws IOException {
        final String caseId = fixture(null);
        String key = UUID.randomUUID().toString();
        Path file = Paths.get(storeRoot).toAbsolutePath().normalize().resolve(key);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1, 2, 3});
        files.add(file);
        String documentId = document(caseId, "Local", key, null);
        DocumentDeletionCommand command = command();

        Admission outcome = deletions.delete(CASEWORKER, caseId, documentId, command);

        assertThat(outcome.admitted()).isTrue();
        assertThat(outcome.request().status()).isEqualTo(DocumentDeletionStatus.Completed);
        assertThat(file).doesNotExist();
        assertCompletedOnce(caseId, documentId, outcome.request().id());

        Admission replay = deletions.delete(CASEWORKER, caseId, documentId, command);
        assertThat(replay.admitted()).isFalse();
        assertThat(replay.request().id()).isEqualTo(outcome.request().id());
        assertThat(replay.request().status()).isEqualTo(DocumentDeletionStatus.Completed);
        assertCompletedOnce(caseId, documentId, outcome.request().id());
    }

    @Test
    @DisplayName("the staff entry point refuses to run inside a transaction")
    void refusesACallerTransaction() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(
            status -> deletions.delete(CASEWORKER, "any", "any", command())))
            .isInstanceOf(IllegalStateException.class);
    }

    // --- uncertain and refused answers ------------------------------------------------------------------------

    @Test
    @DisplayName("an unknown answer leaves the request InProgress, and the sweep finishes it without repeating CCD")
    void unknownAnswerThenSweep() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        final UUID requestId = admitted(caseId, documentId, reference, cdamId, null);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.retry("UNKNOWN"));
        remote.cdam.add(StepOutcome.done("DELETED"));

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.InProgress);
        DocumentDeletionStep cdamStep = step(requestId, DocumentDeletionStepKind.CDAM_BINARY);
        assertThat(cdamStep.state()).isEqualTo(DocumentDeletionStepState.Pending);
        assertThat(cdamStep.attempts()).isOne();
        assertThat(cdamStep.outcome()).isEqualTo("UNKNOWN");
        DocumentDeletionRequest waiting = requests.findById(requestId).orElseThrow();
        assertThat(waiting.owner()).isNull();
        assertThat(waiting.nextAttemptAt()).isEqualTo(clock.get().plusMinutes(1));
        assertThat(deletedEvents(caseId)).isEmpty();

        assertThat(recovery.run()).as("not yet due").isZero();
        clock.set(clock.get().plusMinutes(2));
        assertThat(recovery.run()).isOne();

        assertThat(remote.calls).containsExactly("CCD:" + reference, "CDAM:" + cdamId, "CDAM:" + cdamId);
        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("a lost answer, then a 404 on the safe re-send: never Completed, the document kept and fenced")
    void lostAnswerThenNotFoundNeedsAttention() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        remote.cdam.add(StepOutcome.retry("UNKNOWN"));
        remote.cdam.add(StepOutcome.failed("NOT_FOUND", DocumentDeletionFailure.CDAM_PURGE_UNPROVEN));

        work.carryOut(requestId, work.deadline());
        clock.set(clock.get().plusMinutes(2));
        recovery.run();

        assertNeedsAttention(caseId, documentId, requestId, DocumentDeletionFailure.CDAM_PURGE_UNPROVEN);
        clock.set(clock.get().plusHours(2));
        assertThat(recovery.run()).as("a request needing attention is not claimed again").isZero();
    }

    @Test
    @DisplayName("CDAM's 403 needs attention at once, without retrying; the CCD receipt stays recorded")
    void permissionNeedsAttention() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, reference, cdamId, null);
        remote.ccd.add(StepOutcome.done("SUBMITTED"));
        remote.cdam.add(StepOutcome.failed("FORBIDDEN", DocumentDeletionFailure.CDAM_PERMISSION));

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.NeedsAttention);

        assertNeedsAttention(caseId, documentId, requestId, DocumentDeletionFailure.CDAM_PERMISSION);
        assertThat(step(requestId, DocumentDeletionStepKind.CCD_REFERENCE).state())
            .isEqualTo(DocumentDeletionStepState.Done);
        assertThat(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).locator()).isEqualTo(cdamId.toString());
    }

    @Test
    @DisplayName("a CCD refusal needs attention, and nothing is sent to CDAM")
    void ccdRefusalNeedsAttention() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, reference, cdamId, null);
        remote.ccd.add(StepOutcome.failed("REFUSED", DocumentDeletionFailure.CCD_REFERENCE_FAILED));

        work.carryOut(requestId, work.deadline());

        assertThat(remote.calls).containsExactly("CCD:" + reference);
        assertNeedsAttention(caseId, documentId, requestId, DocumentDeletionFailure.CCD_REFERENCE_FAILED);
    }

    @Test
    @DisplayName("unknown answers that use up the tries need attention, with the locator kept")
    void exhaustedTries() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            remote.cdam.add(StepOutcome.retry("UNKNOWN"));
        }

        work.carryOut(requestId, work.deadline());
        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            clock.set(clock.get().plusHours(2));
            assertThat(recovery.run()).isOne();
        }

        assertNeedsAttention(caseId, documentId, requestId, DocumentDeletionFailure.CDAM_DELETE_FAILED);
        DocumentDeletionStep failed = step(requestId, DocumentDeletionStepKind.CDAM_BINARY);
        assertThat(failed.state()).isEqualTo(DocumentDeletionStepState.Failed);
        assertThat(failed.attempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(failed.locator()).isEqualTo(cdamId.toString());
    }

    // --- the budget -------------------------------------------------------------------------------------------

    @Test
    @DisplayName("no budget left: no call starts, the request is InProgress and due at once, and the sweep finishes it")
    void budgetRunOut() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);

        assertThat(carriedOut(requestId, clock.get().plusSeconds(5))).contains(DocumentDeletionStatus.InProgress);
        assertThat(remote.calls).isEmpty();
        assertThat(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).attempts()).isZero();
        assertThat(requests.findById(requestId).orElseThrow().nextAttemptAt()).isEqualTo(clock.get());

        remote.cdam.add(StepOutcome.done("DELETED"));
        assertThat(recovery.run()).isOne();
        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("a CCD start that leaves too little for the submission submits nothing and spends no try")
    void ccdSubmissionOutOfTime() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, reference, cdamId, null);
        remote.during = () -> clock.set(clock.get().plusSeconds(10));
        remote.ccd.add(StepOutcome.done("SUBMITTED"));

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.InProgress);

        assertThat(remote.ccd).as("the scripted submission was never used").hasSize(1);
        assertThat(step(requestId, DocumentDeletionStepKind.CCD_REFERENCE).attempts()).isZero();
        assertThat(deletedEvents(caseId)).isEmpty();
    }

    // --- crashes, leases and duplicates -----------------------------------------------------------------------

    @Test
    @DisplayName("a crash after the remote receipt: the sweep takes the lapsed claim over and completes, sending "
        + "nothing again")
    void crashAfterRemoteSuccess() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        UUID crashed = UUID.randomUUID();
        assertThat(requests.claim(requestId, crashed, clock.get(), clock.get().plusMinutes(2))).isTrue();
        assertThat(requests.stepDone(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).id(), "DELETED",
                                     clock.get())).isTrue();

        assertThat(recovery.run()).as("the crashed pod's lease still holds").isZero();
        clock.set(clock.get().plusMinutes(3));
        assertThat(recovery.run()).isOne();

        assertThat(remote.calls).isEmpty();
        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("a lost lease: the try is not written and the new owner carries on")
    void leaseLostDuringAnUnknownAnswer() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        UUID other = UUID.randomUUID();
        remote.during = () -> {
            clock.set(clock.get().plusMinutes(3));
            assertThat(requests.claim(requestId, other, clock.get(), clock.get().plusMinutes(2))).isTrue();
        };
        remote.cdam.add(StepOutcome.retry("UNKNOWN"));

        work.carryOut(requestId, work.deadline());

        DocumentDeletionRequest held = requests.findById(requestId).orElseThrow();
        assertThat(held.owner()).isEqualTo(other);
        assertThat(held.status()).isEqualTo(DocumentDeletionStatus.InProgress);
        assertThat(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).attempts()).isZero();
    }

    @Test
    @DisplayName("a receipt that arrives after the lease is lost is still recorded, and only the new owner completes")
    void receiptAfterLeaseLossThenOneCompletion() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        UUID sweep = UUID.randomUUID();
        remote.during = () -> {
            clock.set(clock.get().plusMinutes(3));
            assertThat(requests.claim(requestId, sweep, clock.get(), clock.get().plusMinutes(2))).isTrue();
        };
        remote.cdam.add(StepOutcome.done("DELETED"));

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.InProgress);
        assertThat(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).state())
            .isEqualTo(DocumentDeletionStepState.Done);
        assertThat(deletedEvents(caseId)).as("the request's own worker lost the claim and completed nothing")
            .isEmpty();

        remote.during = () -> { };
        work.process(requestId, sweep, work.deadline());

        assertThat(remote.calls).hasSize(1);
        assertCompletedOnce(caseId, documentId, requestId);
        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.Completed);
        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("two sweeps racing for one due request: exactly one claims it, and it completes once")
    void racingSweeps() throws Exception {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        UUID requestId = admitted(caseId, documentId, null, null, null);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> run = () -> {
            start.await(20, TimeUnit.SECONDS);
            return recovery.run();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(run);
            Future<Integer> second = pool.submit(run);
            start.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS) + second.get(30, TimeUnit.SECONDS)).isOne();
        } finally {
            pool.shutdownNow();
        }
        assertCompletedOnce(caseId, documentId, requestId);
    }

    // --- local completion failing -----------------------------------------------------------------------------

    @Test
    @DisplayName("a refused note rolls completion back whole; the receipts stay, and the next pass completes once")
    void noteFailureIsRecoverable() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        remote.cdam.add(StepOutcome.done("DELETED"));
        failing("deedpoll_notes", "document_deletion_test_note_failure", "NEW.note LIKE 'Document %'");

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.InProgress);
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
        assertThat(deletedEvents(caseId)).isEmpty();
        assertThat(requests.findById(requestId).orElseThrow().reason()).isPresent();
        assertThat(step(requestId, DocumentDeletionStepKind.CDAM_BINARY).state())
            .isEqualTo(DocumentDeletionStepState.Done);

        jdbc.execute("DROP TRIGGER document_deletion_test_note_failure ON deedpoll_notes");
        clock.set(clock.get().plusMinutes(2));
        assertThat(recovery.run()).isOne();

        assertThat(remote.calls).hasSize(1);
        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("a refused DOCUMENT_DELETED insert that never clears needs attention, with nothing local removed")
    void auditFailureExhausts() {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        UUID requestId = admitted(caseId, documentId, null, null, null);
        failing("deedpoll_audit_event", "document_deletion_test_audit_failure", "NEW.action = 'DOCUMENT_DELETED'");

        work.carryOut(requestId, work.deadline());
        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            clock.set(clock.get().plusHours(2));
            assertThat(recovery.run()).isOne();
        }

        assertNeedsAttention(caseId, documentId, requestId, DocumentDeletionFailure.LOCAL_COMPLETION_REFUSED);
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", caseId)).isZero();
    }

    @Test
    @DisplayName("completion counts its own failures: passes spent on remote retries do not use up its tries")
    void completionBudgetIsItsOwn() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        final UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            remote.cdam.add(StepOutcome.retry("UNKNOWN"));
        }
        remote.cdam.add(StepOutcome.done("DELETED"));
        failing("deedpoll_audit_event", "document_deletion_test_audit_failure", "NEW.action = 'DOCUMENT_DELETED'");

        work.carryOut(requestId, work.deadline());
        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            clock.set(clock.get().plusHours(2));
            assertThat(recovery.run()).isOne();
        }

        DocumentDeletionRequest afterFirstFailure = requests.findById(requestId).orElseThrow();
        assertThat(afterFirstFailure.attempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(afterFirstFailure.status()).isEqualTo(DocumentDeletionStatus.InProgress);

        jdbc.execute("DROP TRIGGER document_deletion_test_audit_failure ON deedpoll_audit_event");
        clock.set(clock.get().plusHours(2));
        assertThat(recovery.run()).isOne();

        assertCompletedOnce(caseId, documentId, requestId);
    }

    @Test
    @DisplayName("a step that throws short of an answer is a try, UNEXPECTED, never a receipt")
    void unexpectedFaultIsATry() {
        String caseId = fixture(null);
        UUID cdamId = UUID.randomUUID();
        String documentId = document(caseId, "Attached", null, cdamId);
        UUID requestId = admitted(caseId, documentId, null, cdamId, null);
        remote.during = () -> {
            throw new IllegalStateException("a fault short of an answer");
        };

        assertThat(carriedOut(requestId, work.deadline())).contains(DocumentDeletionStatus.InProgress);

        DocumentDeletionStep tried = step(requestId, DocumentDeletionStepKind.CDAM_BINARY);
        assertThat(tried.state()).isEqualTo(DocumentDeletionStepState.Pending);
        assertThat(tried.attempts()).isOne();
        assertThat(tried.outcome()).isEqualTo("UNEXPECTED");
        assertThat(requests.findById(requestId).orElseThrow().owner()).isNull();
        assertThat(deletedEvents(caseId)).isEmpty();
    }

    @Test
    @DisplayName("completion with the document row already gone is refused, never Completed, then needs attention")
    void documentRowGoneNeverCompletes() {
        String caseId = fixture(null);
        String documentId = document(caseId, "Local", null, null);
        UUID requestId = admitted(caseId, documentId, null, null, null);
        jdbc.update("DELETE FROM deedpoll_supportingdocument WHERE id = ?", documentId);

        work.carryOut(requestId, work.deadline());
        for (int i = 1; i < MAX_ATTEMPTS; i++) {
            clock.set(clock.get().plusHours(2));
            assertThat(recovery.run()).isOne();
        }

        DocumentDeletionRequest request = requests.findById(requestId).orElseThrow();
        assertThat(request.status()).isEqualTo(DocumentDeletionStatus.NeedsAttention);
        assertThat(request.failure()).isEqualTo(DocumentDeletionFailure.LOCAL_COMPLETION_REFUSED.name());
        assertThat(deletedEvents(caseId)).isEmpty();
        assertThat(count("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", caseId)).isZero();
    }

    // --- two removals on one case -----------------------------------------------------------------------------

    @Test
    @DisplayName("two removals on one case: each event carries the case's other documents read fresh, so neither "
        + "puts the other back")
    void concurrentRemovalsDoNotReattach() {
        String reference = reference();
        String caseId = fixture(reference);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        String firstId = document(caseId, "Attached", null, first);
        String secondId = document(caseId, "Attached", null, second);
        final String keptId = document(caseId, "Attached", null, kept);
        admitted(caseId, firstId, reference, first, null);
        CaseDocumentsEvent event = mock(CaseDocumentsEvent.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SupportingDocumentReference>> sent = ArgumentCaptor.forClass(List.class);
        when(event.submit(eq(reference), sent.capture(), any(BooleanSupplier.class))).thenReturn(true);
        @SuppressWarnings("unchecked")
        PermanentDocumentRemoval removal = new PermanentDocumentRemoval(event, documentRows,
            mock(ObjectProvider.class), (ObjectProvider<LocalFilesystemDocumentStore>) mock(ObjectProvider.class));

        // The second removal is admitted after the first one's request but before its event is sent.
        admitted(caseId, secondId, reference, second, null);
        assertThat(removal.removeCcdReference(caseId, firstId, reference, () -> true))
            .isEqualTo(StepOutcome.done("SUBMITTED"));
        assertThat(removal.removeCcdReference(caseId, secondId, reference, () -> true))
            .isEqualTo(StepOutcome.done("SUBMITTED"));

        assertThat(sent.getAllValues()).hasSize(2).allSatisfy(documents -> assertThat(documents)
            .extracting(SupportingDocumentReference::id).containsExactly(keptId));
    }

    // --- fixtures ---------------------------------------------------------------------------------------------

    private void assertCompletedOnce(String caseId, String documentId, UUID requestId) {
        DocumentDeletionRequest done = requests.findById(requestId).orElseThrow();
        assertThat(done.status()).isEqualTo(DocumentDeletionStatus.Completed);
        assertThat(done.reason()).isEmpty();
        assertThat(done.documentName()).isNull();
        assertThat(done.owner()).isNull();
        assertThat(done.requestedBySubject()).isEqualTo(SUBJECT);
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isZero();
        assertThat(deletedEvents(caseId)).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("actor_id", SUBJECT).containsEntry("actor_name", NAME)
                .containsEntry("entity_id", documentId);
            assertThat((String) event.get("detail")).contains("\"documentDeletionReason\": \"" + REASON + "\"")
                .contains("\"documentDeletionRequestId\": \"" + requestId + "\"");
        });
        List<Map<String, Object>> notes = jdbc.queryForList(
            "SELECT note, \"createdBy\" FROM deedpoll_notes WHERE \"deedPollId\" = ?", caseId);
        assertThat(notes).singleElement().satisfies(note -> {
            assertThat(note).containsEntry("note", "Document Evidence (" + documentId
                + ") permanently deleted. Reason: " + REASON);
            assertThat(note).containsEntry("createdBy", NAME);
        });
        assertThat(count("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action = 'NOTE_CREATED' "
                             + "AND actor_id = ?", caseId, SUBJECT)).isOne();
    }

    private void assertNeedsAttention(String caseId, String documentId, UUID requestId,
                                      DocumentDeletionFailure failure) {
        DocumentDeletionRequest request = requests.findById(requestId).orElseThrow();
        assertThat(request.status()).isEqualTo(DocumentDeletionStatus.NeedsAttention);
        assertThat(request.failure()).isEqualTo(failure.name());
        assertThat(request.owner()).isNull();
        assertThat(request.reason()).isPresent();
        assertThat(count("SELECT count(*) FROM deedpoll_supportingdocument WHERE id = ?", documentId)).isOne();
        assertThat(documentRows.findById(documentId)).as("still fenced").isEmpty();
        assertThat(deletedEvents(caseId)).isEmpty();
    }

    private void failing(String table, String name, String condition) {
        jdbc.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF " + condition
                         + " THEN RAISE EXCEPTION 'forced failure'; END IF; RETURN NEW; END; $$");
        jdbc.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON " + table + " FOR EACH ROW EXECUTE FUNCTION "
                         + name + "()");
    }

    private String fixture(String ccdReference) {
        String id = UUID.randomUUID().toString();
        fixtures.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "displayReference", "ccdCaseReference")
            VALUES (?, now(), 'Adult'::"DeedPollType", 'Enroled', ?, ?)
            """, id, "document-deletion-" + id, ccdReference);
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

    /** A request as admission writes it, with the steps it would find; for stores this context does not serve. */
    private UUID admitted(String caseId, String documentId, String ccdReference, UUID cdamId, String storageKey) {
        UUID id = UUID.randomUUID();
        DocumentDeletionReason reason = DocumentDeletionReason.parse(REASON);
        assertThat(requests.insert(new NewDocumentDeletionRequest(
            id, caseId, documentId, UUID.randomUUID(), reason, "Evidence",
            DocumentDeletionFingerprint.of(documentId, 1, reason), 1, SUBJECT, NAME, AuditClock.now()))).isTrue();
        if (ccdReference != null) {
            requests.insertStep(UUID.randomUUID(), id, DocumentDeletionStepKind.CCD_REFERENCE, ccdReference);
        }
        if (cdamId != null) {
            requests.insertStep(UUID.randomUUID(), id, DocumentDeletionStepKind.CDAM_BINARY, cdamId.toString());
        }
        if (storageKey != null) {
            requests.insertStep(UUID.randomUUID(), id, DocumentDeletionStepKind.LOCAL_FILE, storageKey);
        }
        return id;
    }

    private DocumentDeletionStep step(UUID requestId, DocumentDeletionStepKind kind) {
        return requests.stepsOf(requestId).stream().filter(step -> step.kind() == kind).findFirst().orElseThrow();
    }

    private static DocumentDeletionCommand command() {
        return new DocumentDeletionCommand(UUID.randomUUID(), REASON, 1L);
    }

    private List<Map<String, Object>> deletedEvents(String caseId) {
        return jdbc.queryForList("""
            SELECT actor_id, actor_name, entity_id, detail::text AS detail
              FROM deedpoll_audit_event WHERE case_id = ? AND action = 'DOCUMENT_DELETED'
            """, caseId);
    }

    private int count(String sql, Object... arguments) {
        Integer found = jdbc.queryForObject(sql, Integer.class, arguments);
        return found == null ? 0 : found;
    }

    /** Remote answers scripted per step kind, with a log of the calls made and a hook run during each. */
    /** Carries the request out and answers the status it has afterwards. */
    private Optional<DocumentDeletionStatus> carriedOut(UUID requestId, LocalDateTime deadline) {
        return work.carryOut(requestId, deadline).map(DocumentDeletionRequest::status);
    }

    static final class ScriptedRemote implements DocumentDeletionRemote {
        final Deque<StepOutcome> ccd = new ArrayDeque<>();
        final Deque<StepOutcome> cdam = new ArrayDeque<>();
        final Deque<StepOutcome> local = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        Runnable during = () -> { };

        @Override
        public StepOutcome removeCcdReference(String caseId, String documentId, String caseReference,
                                              BooleanSupplier mayStillSubmit) {
            calls.add("CCD:" + caseReference);
            during.run();
            if (!mayStillSubmit.getAsBoolean()) {
                return StepOutcome.notSent("OUT_OF_TIME");
            }
            return next(ccd);
        }

        @Override
        public StepOutcome deleteCdamPermanently(UUID cdamDocumentId) {
            calls.add("CDAM:" + cdamDocumentId);
            during.run();
            return next(cdam);
        }

        @Override
        public StepOutcome removeLocalFile(String storageKey) {
            calls.add("LOCAL:" + storageKey);
            during.run();
            return next(local);
        }

        private static StepOutcome next(Deque<StepOutcome> script) {
            if (script.isEmpty()) {
                throw new AssertionError("an unscripted remote call");
            }
            return script.poll();
        }
    }
}
