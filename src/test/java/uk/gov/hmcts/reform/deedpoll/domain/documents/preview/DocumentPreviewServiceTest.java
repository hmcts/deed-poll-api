package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.CreateDocumentTypeInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentTypeService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * A preview read through the case or through a referral, against a real database, a real filesystem store, the real
 * renderers and a WireMock converter (Task 4 of the preview plan).
 *
 * <p>What is pinned: the caller's scope is the original download's, unchanged; {@code DOCUMENT_PREVIEWED} is
 * committed once, before the original is opened, and a failed one opens nothing; every refusal upstream of the
 * source reaches neither the store nor the converter; and a preview leaves nothing behind -- no row, file, audit
 * write, permit or scratch directory.
 *
 * <p>Not {@code @Transactional}: the read audit commits in its own transaction, and the preview refuses to run
 * inside one. Audit rows stay, as V36 requires, under case ids no other test uses.
 */
@SpringBootTest
@ActiveProfiles("test")
class DocumentPreviewServiceTest extends AbstractPostgresContainerTest {

    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";
    private static final String CONVERT = "/rs/convert";
    private static final String DOCX = PreviewFormat.DOCX.mimeType();

    /** A subject longer than the 255 characters actor_id holds: the one failure only the audit table refuses. */
    private static final String UNRECORDABLE = "p".repeat(256);

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(Role.CASEWORKER), "Ada Caseworker", "caseworker-preview");
    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Bell", "idam-judge-bell");
    private static final CurrentUser OTHER_JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "District Judge Okafor", "idam-judge-okafor");

    private static final Path STORE_ROOT = temporaryStoreRoot();
    private static final Path SCRATCH = Path.of(System.getProperty("java.io.tmpdir"));
    private static final WireMockServer CONVERTER =
        new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        CONVERTER.start();
    }

    @AfterAll
    static void releaseTheStubAndTheStore() throws IOException {
        CONVERTER.stop();
        try (Stream<Path> entries = Files.walk(STORE_ROOT)) {
            for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
        registry.add("deedpoll.documents.preview.converter.url", () -> "http://localhost:" + CONVERTER.port());
        registry.add("deedpoll.documents.preview.converter.access-key", () -> "synthetic-preview-key");
    }

    @Autowired
    private DocumentPreviewService previews;

    @Autowired
    private ConversionCapacity capacity;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private ReferralService referralService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @MockitoSpyBean
    private DocumentStore store;

    @MockitoSpyBean
    private CorrespondenceDocumentReader correspondence;

    private String caseId;
    private String typeId;
    private List<Path> scratchBefore;
    private int readsBaseline;

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-api-preview-documents");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() {
        clearTables();
        CONVERTER.resetAll();
        CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(convertedPdf(3)));
        caseId = newCase();
        typeId = documentTypes.createDocumentType(CreateDocumentTypeInput.named("Evidence")).getId();
        scratchBefore = scratch();
        readsBaseline = 0;
    }

    @AfterEach
    void tearDown() {
        // Every test, whatever it did, leaves every permit free and no preview directory behind.
        assertThat(capacity.available()).isEqualTo(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
        assertThat(scratch()).isEqualTo(scratchBefore);
        verify(correspondence, never()).downloadDocument(any(), any());
        verify(correspondence, never()).forNotification(any(), any());
        clearTables();
    }

    private void clearTables() {
        referrals.deleteAll();
        notes.deleteAll();
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Nested
    @DisplayName("for a member of staff")
    class Caseworker {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
            "three-pages.pdf, application/pdf, 3",
            "photo.jpeg, image/jpeg, 1",
            "scan.png, image/png, 1",
            "three-frames.tiff, image/tiff, 3",
            "three-pages.docx, application/vnd.openxmlformats-officedocument.wordprocessingml.document, 3"
        })
        @DisplayName("previews every allowed format, recording one DOCUMENT_PREVIEWED and leaving the original alone")
        void previewsEveryFormat(String fixture, String mimeType, int pages) throws IOException {
            byte[] original = DocumentPreviewFixtures.read(fixture);
            String documentId = uploaded(caseId, fixture, mimeType, original);
            final Snapshot before = snapshot(documentId);

            try (RenderedPreview preview = previewAsCaseworker(caseId, documentId)) {
                assertThat(preview.pages()).isEqualTo(PreviewFormat.require(mimeType).countsPages()
                    ? OptionalInt.of(pages) : OptionalInt.empty());
                try (InputStream in = preview.open(); PDDocument pdf = Loader.loadPDF(in.readAllBytes())) {
                    assertThat(pdf.getNumberOfPages()).isEqualTo(pages);
                }
            }

            assertThat(reads("DOCUMENT_PREVIEWED")).singleElement().satisfies(row ->
                assertThat(row).containsEntry("actor_id", CASEWORKER.userId())
                    .containsEntry("entity_type", "Document")
                    .containsEntry("entity_id", documentId));
            assertThat(reads("DOCUMENT_DOWNLOADED")).isEmpty();
            verify(store, times(1)).open(any());
            assertThat(snapshot(documentId)).isEqualTo(before);
            assertThat(sha256(storedBytes(documentId))).isEqualTo(sha256(original));
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl())))
                .hasSize(PreviewFormat.DOCX.mimeType().equals(mimeType) ? 1 : 0);
        }

        @Test
        @DisplayName("records each preview request once, and the PDF's pages add nothing")
        void recordsEachRequest() {
            String documentId = uploadedFixture("three-frames.tiff", "image/tiff");

            previewAsCaseworker(caseId, documentId).close();
            previewAsCaseworker(caseId, documentId).close();

            assertThat(reads("DOCUMENT_PREVIEWED")).hasSize(2);
        }

        @Test
        @DisplayName("a document of another case is the document's own 404, and nothing is recorded, read or sent")
        void anotherCasesDocument() {
            String elsewhere = uploadedFixture(newCase(), "three-pages.docx", DOCX);

            assertThatThrownBy(() -> previewAsCaseworker(caseId, elsewhere))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Document " + elsewhere + " not found");

            assertNothingRead();
        }

        @Test
        @DisplayName("an unknown document is the same 404, before anything is recorded or read")
        void unknownDocument() {
            assertThatThrownBy(() -> previewAsCaseworker(caseId, "no-such-document"))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Document no-such-document not found");

            assertNothingRead();
        }

        @Test
        @DisplayName("a row with no file is the download's 404, before anything is recorded or read")
        void rowWithNoFile() {
            String metadataOnly = documentService.createDocument(caseId,
                CreateDocumentInput.metadata(DocumentDirection.Incoming, "Expected", typeId)).getId();

            assertThatThrownBy(() -> previewAsCaseworker(caseId, metadataOnly))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("This document has no attached file");

            assertNothingRead();
        }

        @Test
        @DisplayName("a stored type outside the allowlist is an invalid document, with nothing recorded or read")
        void typeOutsideTheAllowlist() {
            String legacy = uploadedFixture("three-pages.docx", DOCX);
            relabel(legacy, "application/msword");

            PreviewError refused = catchThrowableOfType(() -> previewAsCaseworker(caseId, legacy));

            assertThat(refused.failure()).isEqualTo(PreviewFailure.INVALID_DOCUMENT);
            assertNothingRead();
        }

        @Test
        @DisplayName("a source the store cannot find keeps the store's own 404, after the audit, and is never sent")
        void missingSource() throws IOException {
            String documentId = uploadedFixture("three-pages.docx", DOCX);
            Files.delete(STORE_ROOT.resolve(storageKey(documentId)));

            assertThatThrownBy(() -> previewAsCaseworker(caseId, documentId))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Stored file not found");

            assertThat(reads("DOCUMENT_PREVIEWED")).hasSize(1);
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        }

        @Test
        @DisplayName("a store read refused to this reader stays a refusal, never a preview failure or a system read")
        void refusedSourceRead() {
            String documentId = uploadedFixture("three-pages.docx", DOCX);
            ForbiddenError refusal = new ForbiddenError("You do not have permission to open this document's file");
            doThrow(refusal).when(store).open(any());

            assertThat(catchThrowable(() -> previewAsCaseworker(caseId, documentId))).isSameAs(refusal);

            assertThat(reads("DOCUMENT_PREVIEWED")).as("a refused read is recorded once, as a refused download is")
                .hasSize(1);
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
            // The tearDown verifies the correspondence reader was never asked instead.
        }

        @Test
        @DisplayName("fails closed: an audit that cannot be committed opens and converts nothing")
        void failsClosed() {
            String documentId = uploadedFixture("three-pages.docx", DOCX);
            CurrentUser unrecordable = CurrentUser.currentUser(List.of(Role.CASEWORKER), "Ada", UNRECORDABLE);

            assertThatThrownBy(() -> previews.caseworkerPreview(caseId, documentId, unrecordable, previews.budget()))
                .isInstanceOf(DataAccessException.class);

            assertNothingRead();
        }

        @Test
        @DisplayName("refuses to run inside a caller's transaction, before anything is recorded or read")
        void refusesATransaction() {
            String documentId = uploadedFixture("photo.jpeg", "image/jpeg");

            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                previewAsCaseworker(caseId, documentId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

            assertNothingRead();
        }

        @Test
        @DisplayName("a DOCX the converter cannot convert is unavailable, after one audited read, and is never retried")
        void converterOutage() {
            String documentId = uploadedFixture("three-pages.docx", DOCX);
            CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(aResponse().withStatus(503)));

            PreviewError refused = catchThrowableOfType(() -> previewAsCaseworker(caseId, documentId));

            assertThat(refused.failure()).isEqualTo(PreviewFailure.UNAVAILABLE);
            assertThat(reads("DOCUMENT_PREVIEWED")).hasSize(1);
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
        }

        @Test
        @DisplayName("an interrupted source transfer is unavailable, and its stream is closed")
        void interruptedTransfer() {
            String documentId = uploadedFixture("three-frames.tiff", "image/tiff");
            AtomicBoolean closed = new AtomicBoolean();
            doAnswer(call -> new BreaksAfter(call.callRealMethod(), 1024, closed)).when(store).open(any());

            PreviewError refused = catchThrowableOfType(() -> previewAsCaseworker(caseId, documentId));

            assertThat(refused.failure()).isEqualTo(PreviewFailure.UNAVAILABLE);
            assertThat(closed).isTrue();
        }

        @Test
        @DisplayName("a spent budget is a limit, refused before anything is recorded or read")
        void spentBeforeStarting() {
            String documentId = uploadedFixture("scan.png", "image/png");
            PreviewBudget budget = PreviewBudget.start(Clock.systemUTC(), Duration.ZERO);

            PreviewError refused = catchThrowableOfType(
                () -> previews.caseworkerPreview(caseId, documentId, CASEWORKER, budget));

            assertThat(refused.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED);

            assertNothingRead();
        }
    }

    @Nested
    @DisplayName("capacity")
    class Capacity {

        @Test
        @DisplayName("over the pod's conversions is busy only after the audited source read; a PDF still passes, "
            + "and every permit comes back")
        void busyWhileConversionsRun() throws Exception {
            String docx = uploadedFixture("three-pages.docx", DOCX);
            String png = uploadedFixture("scan.png", "image/png");
            String pdf = uploadedFixture("three-pages.pdf", "application/pdf");
            CONVERTER.stubFor(post(urlPathEqualTo(CONVERT)).willReturn(convertedPdf(3).withFixedDelay(1500)));

            ExecutorService pool = Executors.newFixedThreadPool(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
            try {
                List<Future<Integer>> running = new ArrayList<>();
                for (int i = 0; i < PreviewProperties.MAX_CONCURRENT_CONVERSIONS; i++) {
                    running.add(pool.submit(() -> {
                        try (RenderedPreview preview = previewAsCaseworker(caseId, docx)) {
                            return pageCount(preview);
                        }
                    }));
                }
                awaitNoPermits();

                PreviewError busy = catchThrowableOfType(() -> previewAsCaseworker(caseId, png));
                assertThat(busy.failure()).isEqualTo(PreviewFailure.BUSY);
                assertThat(busy.retryAfter()).contains(PreviewProperties.BUSY_RETRY_AFTER);

                try (RenderedPreview passthrough = previewAsCaseworker(caseId, pdf)) {
                    assertThat(pageCount(passthrough)).isEqualTo(DocumentPreviewFixtures.PDF_PAGES);
                }

                for (Future<Integer> conversion : running) {
                    assertThat(conversion.get(20, TimeUnit.SECONDS)).isEqualTo(3);
                }
            } finally {
                pool.shutdownNow();
            }

            assertThat(readsOf(png)).hasSize(1);
            assertThat(readsOf(docx)).hasSize(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
            assertThat(readsOf(pdf)).hasSize(1);
            assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl())))
                .hasSize(PreviewProperties.MAX_CONCURRENT_CONVERSIONS);
        }

        private void awaitNoPermits() throws InterruptedException {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (capacity.available() > 0) {
                if (System.nanoTime() > until) {
                    throw new AssertionError("the conversions never took every permit");
                }
                Thread.sleep(10);
            }
        }
    }

    @Nested
    @DisplayName("for a judge, through a referral")
    class Judicial {

        private String referralId;
        private String documentId;

        @BeforeEach
        void raise() {
            referralId = referralService.createReferral(
                    caseId,
                    new CreateReferralInput(List.of(ReferralReason.Minor), Optional.of("Applicant is 14"),
                        Urgency.Normal, Optional.empty()),
                    CASEWORKER)
                .getId().toString();
            documentId = uploadedFixture("three-pages.docx", DOCX);
        }

        @Test
        @DisplayName("refuses to run inside a caller's transaction, before anything is recorded or read")
        void refusesATransaction() {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status ->
                previews.judicialPreview(referralId, documentId, JUDGE, previews.budget()).close()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");

            assertNothingRead();
        }

        @Test
        @DisplayName("no judge previews a document of a referral waiting for assignment (plan D2)")
        void noJudgeOnAnOpenReferral() {
            assertThatThrownBy(() -> previewAsJudge(JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + referralId + " not found");
            assertThatThrownBy(() -> previewAsJudge(OTHER_JUDGE)).isInstanceOf(NotFoundError.class);
            assertNothingRead();
        }

        @Test
        @DisplayName("only the holder previews from a Claimed referral; another judge gets the referral's 404")
        void onlyTheHolderOfAClaim() {
            referralService.claim(caseId, referralId, JUDGE);

            assertThat(previewAsJudge(JUDGE)).isEqualTo(3);
            reset();

            assertThatThrownBy(() -> previewAsJudge(OTHER_JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + referralId + " not found");
            assertNothingRead();
        }

        @Test
        @DisplayName("the directing judge previews from a Decided referral, and nobody once it is Closed")
        void decidedThenClosed() {
            referralService.claim(caseId, referralId, JUDGE);
            referralService.decide(caseId, referralId,
                new DirectionInput(MasterDecision.Approved, Optional.of("Granted"), true), JUDGE);

            assertThat(previewAsJudge(JUDGE)).isEqualTo(3);
            assertThatThrownBy(() -> previewAsJudge(OTHER_JUDGE)).isInstanceOf(NotFoundError.class);

            referralService.close(caseId, referralId, CASEWORKER);
            reset();

            assertThatThrownBy(() -> previewAsJudge(JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + referralId + " not found");
            assertNothingRead();
        }

        @Test
        @DisplayName("nobody previews once the referral is withdrawn")
        void withdrawn() {
            referralService.withdrawReferral(caseId, referralId, CASEWORKER);

            assertThatThrownBy(() -> previewAsJudge(JUDGE))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Referral " + referralId + " not found");
            assertNothingRead();
        }

        @Test
        @DisplayName("a claim with no owner id belongs to nobody, so no judge previews from it")
        void nullOwner() {
            referralService.claim(caseId, referralId, JUDGE);
            jdbc.update("UPDATE deedpoll_referral SET \"claimedById\" = NULL WHERE id = ?::uuid", referralId);

            assertThatThrownBy(() -> previewAsJudge(JUDGE)).isInstanceOf(NotFoundError.class);
            assertThatThrownBy(() -> previewAsJudge(OTHER_JUDGE)).isInstanceOf(NotFoundError.class);
            assertNothingRead();
        }

        @Test
        @DisplayName("a document of another case is the document's 404, so a referral reaches only its own")
        void anotherCasesDocument() {
            referralService.claim(caseId, referralId, JUDGE);
            String elsewhere = uploadedFixture(newCase(), "three-pages.docx", DOCX);

            assertThatThrownBy(() -> previews.judicialPreview(referralId, elsewhere, JUDGE, previews.budget()))
                .isInstanceOf(NotFoundError.class)
                .hasMessage("Document " + elsewhere + " not found");
            assertNothingRead();
        }

        @Test
        @DisplayName("a caseworker is refused with the judicial role's 403, before anything is recorded or read")
        void refusesACaseworker() {
            assertThatThrownBy(() -> previewAsJudge(CASEWORKER))
                .isInstanceOf(ForbiddenError.class)
                .hasMessageContaining(Role.JUDICIAL.claimValue());
            assertNothingRead();
        }

        @Test
        @DisplayName("fails closed for a judge too")
        void failsClosed() {
            CurrentUser unrecordable = CurrentUser.currentUser(List.of(Role.JUDICIAL), "Judge", UNRECORDABLE);
            // Held by that subject, written directly: the claim transition's own audit could not record it either.
            jdbc.update("UPDATE deedpoll_referral SET state = 'Claimed', \"claimedBy\" = 'Judge', "
                            + "\"claimedById\" = ?, \"claimedAt\" = now(), \"lastTouchedAt\" = now() "
                            + "WHERE id = ?::uuid", UNRECORDABLE, referralId);

            assertThatThrownBy(() -> previewAsJudge(unrecordable)).isInstanceOf(DataAccessException.class);
            assertNothingRead();
        }

        private int previewAsJudge(CurrentUser caller) {
            PreviewBudget budget = previews.budget();
            try (RenderedPreview preview = previews.judicialPreview(referralId, documentId, caller, budget)) {
                return pageCount(preview);
            }
        }
    }

    /** The preview's pages, read back by a real parser: the service no longer counts a PDF's or a DOCX's. */
    private static int pageCount(RenderedPreview preview) {
        try (InputStream in = preview.open(); PDDocument pdf = Loader.loadPDF(in.readAllBytes())) {
            return pdf.getNumberOfPages();
        } catch (IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private RenderedPreview previewAsCaseworker(String forCase, String documentId) {
        return previews.caseworkerPreview(forCase, documentId, CASEWORKER, previews.budget());
    }

    /** Forgets what earlier, successful previews in a test recorded, read and sent. */
    private void reset() {
        clearInvocations(store);
        CONVERTER.resetRequests();
        readsBaseline = reads("DOCUMENT_PREVIEWED").size();
    }

    private void assertNothingRead() {
        verify(store, never()).open(any());
        assertThat(reads("DOCUMENT_PREVIEWED")).hasSize(readsBaseline);
        assertThat(CONVERTER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll().getId();
    }

    private String uploadedFixture(String fixture, String mimeType) {
        return uploadedFixture(caseId, fixture, mimeType);
    }

    private String uploadedFixture(String forCase, String fixture, String mimeType) {
        return uploaded(forCase, fixture, mimeType, DocumentPreviewFixtures.read(fixture));
    }

    private String uploaded(String forCase, String filename, String mimeType, byte[] bytes) {
        String id = documentService
            .uploadDocument(forCase, typeId, UploadedFile.of(bytes, filename, mimeType),
                Optional.empty(), Optional.empty())
            .getId();
        clearInvocations(store);
        return id;
    }

    private void relabel(String documentId, String mimeType) {
        documentService.updateDocument(caseId, documentId, new UpdateDocumentInput(
            PatchField.absent(), PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.absent(), PatchField.of(mimeType)));
    }

    private String storageKey(String documentId) {
        return jdbc.queryForObject(
            "SELECT \"storageKey\" FROM deedpoll_supportingdocument WHERE id = ?", String.class, documentId);
    }

    private byte[] storedBytes(String documentId) throws IOException {
        return Files.readAllBytes(STORE_ROOT.resolve(storageKey(documentId)));
    }

    /**
     * Everything a preview must not change: the document row, every document and notification of the case, every
     * write the trail holds for it, and every file in the store.
     */
    private Snapshot snapshot(String documentId) {
        return new Snapshot(
            jdbc.queryForMap("SELECT * FROM deedpoll_supportingdocument WHERE id = ?", documentId).toString(),
            jdbc.queryForObject("SELECT count(*) FROM deedpoll_supportingdocument", Long.class),
            jdbc.queryForObject("SELECT count(*) FROM deedpoll_notification", Long.class),
            jdbc.queryForObject("SELECT count(*) FROM deedpoll_audit_event WHERE case_id = ? AND action NOT IN "
                + "('CASE_VIEWED', 'DOCUMENT_DOWNLOADED', 'DOCUMENT_PREVIEWED')", Long.class, caseId),
            storeFiles());
    }

    private record Snapshot(String row, long documents, long notifications, long auditWrites, List<String> files) {
    }

    private static List<String> storeFiles() {
        try (Stream<Path> entries = Files.walk(STORE_ROOT)) {
            return entries.filter(Files::isRegularFile).map(path -> path + ":" + sha256(read(path))).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static List<Path> scratch() {
        try (Stream<Path> entries = Files.list(SCRATCH)) {
            return entries.filter(path -> path.getFileName().toString().startsWith("preview-")).sorted().toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private List<Map<String, Object>> reads(String action) {
        return jdbc.queryForList(
            "SELECT actor_id, entity_type, entity_id FROM deedpoll_audit_event WHERE case_id = ? AND action = ?",
            caseId, action);
    }

    private List<Map<String, Object>> readsOf(String documentId) {
        return jdbc.queryForList(
            "SELECT id FROM deedpoll_audit_event WHERE entity_id = ? AND action = 'DOCUMENT_PREVIEWED'", documentId);
    }

    private static ResponseDefinitionBuilder convertedPdf(int pages) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/pdf")
            .withBody(DocumentPreviewFixtures.pdf(pages));
    }

    private static PreviewError catchThrowableOfType(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(PreviewError.class);
        return (PreviewError) thrown;
    }

    private static byte[] read(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * The store's stream, failing as a dropped transfer does once {@code limit} bytes have been read.
     */
    private static final class BreaksAfter extends FilterInputStream {

        private final long limit;
        private final AtomicBoolean closed;
        private long read;

        BreaksAfter(Object stream, long limit, AtomicBoolean closed) {
            super((InputStream) stream);
            this.limit = limit;
            this.closed = closed;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (read >= limit) {
                throw new IOException("connection reset");
            }
            int count = super.read(buffer, offset, (int) Math.min(length, limit - read));
            read += Math.max(count, 0);
            return count;
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

}
