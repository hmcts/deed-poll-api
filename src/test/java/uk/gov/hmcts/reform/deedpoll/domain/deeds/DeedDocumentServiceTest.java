package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UpdateDocumentInput;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Storing a case's deed: once, as the court's own outgoing document, and never again.
 *
 * <p><strong>Against a real database and the real local store</strong>, because every claim here is about what
 * is on disk and in the row after the transaction commits -- "one row and one blob" cannot be asserted against
 * a mock that was asked to save twice. {@link DeedGenerator} is a Mockito spy so that "the second request did
 * not regenerate" can be stated about the generator rather than inferred from the bytes, which are identical
 * either way: the generator is deterministic, which is exactly what makes the difference invisible in the
 * output.
 *
 * <p>Not {@code @Transactional}: the writes must really commit, both so the rows read back are committed ones
 * and because {@code DocumentService}'s blob cleanup is registered against the transaction and would otherwise
 * never run.
 *
 * <p>The filename and the case-type lists are asserted container-free too, in {@code DeedFilenameTest} and
 * {@code DeedDocumentTypesTest}, because CI has no Docker and both are read by somebody outside this service.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeedDocumentServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** The reference the first case of 2026 is allocated, which is what the filename carries. */
    private static final String REFERENCE = "KBDP2026-00001";

    /**
     * Blobs go to a throwaway directory, never to the developer's own {@code data/documents}.
     */
    private static final Path STORE_ROOT = temporaryStoreRoot();

    @Autowired
    private DeedDocumentService deedDocuments;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactions;

    @MockitoSpyBean
    private DeedGenerator generator;

    private ListAppender<ILoggingEvent> logs;

    @DynamicPropertySource
    static void storeRoot(DynamicPropertyRegistry registry) {
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
    }

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-deeds");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        clearTables();
        captureLogs();
    }

    @AfterEach
    void tearDown() throws IOException {
        releaseLogs();
        clearTables();
    }

    private void clearTables() throws IOException {
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        for (String blob : storedBlobs()) {
            Files.deleteIfExists(STORE_ROOT.resolve(blob));
        }
    }

    @AfterAll
    static void removeTheStoreRoot() throws IOException {
        Files.deleteIfExists(STORE_ROOT);
    }

    private void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(DeedDocumentService.class)).addAppender(logs);
    }

    private void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(DeedDocumentService.class)).detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("stores the deed as an outgoing document with its own mime type, size and filename")
    void storesTheDeedAsAnOutgoingDocument() {
        String caseId = storedCase();

        DeedPollSupportingDocument document = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        DeedPollSupportingDocument saved = stored(caseId, document.getId());
        // The court sending a document out, not an applicant supplying one -- which is the opposite of
        // DocumentService's default, so it has to be said explicitly and therefore asserted.
        assertThat(saved.getDirection()).isEqualTo(DocumentDirection.Outgoing);
        assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
        assertThat(saved.getDocumentType().getName()).isEqualTo("LOC020 Change of name deed");
        assertThat(saved.getName()).isEqualTo("LOC020 Change of name deed");
        // A name the applicant will recognise among their downloads, not the UUID the store keys blobs by.
        assertThat(saved.getOriginalName())
            .isEqualTo("LOC020-change-of-name-deed-" + REFERENCE + ".docx");
        assertThat(saved.getOriginalName()).isNotEqualTo(saved.getStorageKey());
        // From the format rather than written out here: decision 5's deferral lives in DeedOutputFormat.
        assertThat(saved.getMimeType()).isEqualTo(DeedOutputFormat.DOCX.mimeType());
        assertThat(saved.getSizeBytes()).isEqualTo(expectedDeed(caseId, DeedTemplate.ADULT).length);
    }

    @Test
    @DisplayName("the stored bytes are what the generator produced")
    void theStoredBytesAreWhatTheGeneratorProduced() throws IOException {
        String caseId = storedCase();

        DeedPollSupportingDocument document = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        // Answerable twice only because the generator is byte-deterministic (task 2's settled point 5): the
        // same case and template produce the same zip, so a second generate is a fair comparison.
        assertThat(blobOf(document)).isEqualTo(expectedDeed(caseId, DeedTemplate.ADULT));
    }

    @Test
    @DisplayName("generating twice for one case yields one row, one blob and one generate")
    void generatingTwiceYieldsOneRowAndOneBlob() {
        String caseId = storedCase();

        DeedPollSupportingDocument first = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);
        DeedPollSupportingDocument second = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        // Decision 6: LOC020 *becomes* the sealed deed, so the bytes the court holds and the paper the
        // applicant signed cannot be allowed to differ.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(documents.count()).isOne();
        assertThat(storedBlobs()).hasSize(1);
        verify(generator, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("refuses the other deed for a case that already holds one, rather than handing back the wrong one")
    void refusesTheOtherDeedForACaseThatAlreadyHoldsOne() {
        String caseId = storedCase();
        DeedPollSupportingDocument first = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        // A case gets one deed, and the caller asking for the other one is about to put it in front of an
        // applicant under the other deed's covering words. Returning what the case holds would email a minor's
        // forms carrying an adult's deed -- reachable whenever the first forms email failed PERMANENT, because
        // V20's index deliberately frees the slot for one of those. So it refuses.
        assertThatThrownBy(() -> deedDocuments.storeDeed(caseId, DeedTemplate.MINOR))
            .isInstanceOf(ConflictError.class)
            .hasMessageContaining("LOC020 Change of name deed")
            .hasMessageContaining("LOC022");

        // And it neither replaced nor added to the stored deed: the bytes an applicant may already have signed.
        assertThat(documents.count()).isOne();
        assertThat(stored(caseId, first.getId()).getDocumentType().getName())
            .isEqualTo("LOC020 Change of name deed");
        assertThat(storedBlobs()).hasSize(1);
        verify(generator, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("a case carrying the applicant's own documents still gets a deed generated for it")
    void generatesADeedForACaseThatAlreadyHasOtherDocuments() {
        String caseId = storedCase();
        // The ordinary state of a deed poll case by the time forms go out: evidence the applicant sent in. The
        // type-name filter is the only thing separating "this case already has its deed" from "this case has
        // documents", and if it ever stopped discriminating the applicant would be emailed their own upload as
        // the deed to print, sign and swear -- with no deed ever generated and nothing else failing.
        DeedPollSupportingDocumentType other = new DeedPollSupportingDocumentType();
        other.setName("Evidence of citizenship");
        String otherId = documentService.uploadDocument(
            caseId, types.save(other).getId(),
            UploadedFile.of("a passport scan".getBytes(StandardCharsets.UTF_8),
                            "citizenship.pdf", "application/pdf")).getId();

        DeedPollSupportingDocument deed = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        assertThat(deed.getId()).isNotEqualTo(otherId);
        assertThat(deed.getDocumentType().getName()).isEqualTo("LOC020 Change of name deed");
        assertThat(documents.count()).isEqualTo(2);
        verify(generator, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("refuses rather than emailing an incoming document a caseworker filed under a deed's own type")
    void refusesAnIncomingDocumentFiledUnderADeedType() {
        String caseId = storedCase();
        // Reachable from the first deed generated anywhere in an environment onward: the type row is real, so
        // GET /caseworker/document-types publishes it and the upload endpoint takes it as any other
        // documentTypeId. The obvious thing to file under "LOC020 Change of name deed" is the signed deed coming
        // back from the applicant -- and the lookup keys on the type's NAME, which cannot tell that row from a
        // generated one. Answered with, it would email the applicant their own returned paperwork as the deed to
        // print, sign before witnesses and swear, with no deed ever generated and nothing else failing.
        DeedPollSupportingDocumentType deedType = new DeedPollSupportingDocumentType();
        deedType.setName("LOC020 Change of name deed");
        String misfiledId = documentService.uploadDocument(
            caseId, types.save(deedType).getId(),
            UploadedFile.of("a signed deed coming back".getBytes(StandardCharsets.UTF_8),
                            "returned-deed.pdf", "application/pdf")).getId();
        assertThat(stored(caseId, misfiledId).getDirection()).isEqualTo(DocumentDirection.Incoming);

        assertThatThrownBy(() -> deedDocuments.storeDeed(caseId, DeedTemplate.ADULT))
            .isInstanceOf(ConflictError.class)
            .hasMessageContaining("LOC020 Change of name deed")
            .hasMessageContaining("did not send out");

        // Refused rather than ignored: an Incoming row under a deed's type is either this, or a generated deed
        // whose direction a caseworker patched, and generating over the second is decision 6's failure. So
        // nothing was generated and nothing was stored either way.
        assertThat(documents.count()).isOne();
        assertThat(storedBlobs()).hasSize(1);
        verify(generator, times(0)).generate(any(), any());
    }

    @Test
    @DisplayName("a generated deed whose direction was patched is refused, never regenerated")
    void refusesRatherThanRegeneratingWhenTheDeedsDirectionWasPatched() {
        String caseId = storedCase();
        DeedPollSupportingDocument deed = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        // PATCH /caseworker/cases/{id}/documents/{id} can write `direction`, so this is a real state. It is why the
        // lookup reads both directions instead of narrowing to Outgoing: a narrowed query would answer "no deed
        // yet" here and store a SECOND legal instrument for the same applicant, which is the one failure decision 6
        // exists to prevent. Refusing costs a caseworker one correction; a second deed cannot be un-sworn.
        documentService.updateDocument(caseId, deed.getId(), incoming());

        assertThatThrownBy(() -> deedDocuments.storeDeed(caseId, DeedTemplate.ADULT))
            .isInstanceOf(ConflictError.class)
            .hasMessageContaining("did not send out");

        assertThat(documents.count()).isOne();
        assertThat(storedBlobs()).hasSize(1);
        verify(generator, times(1)).generate(any(), any());
    }

    @Test
    @DisplayName("files the minor's deed under its own type, with its own applicable case types")
    void filesTheMinorDeedUnderItsOwnType() {
        String caseId = storedCase();

        DeedPollSupportingDocument document = deedDocuments.storeDeed(caseId, DeedTemplate.MINOR);

        DeedPollSupportingDocument saved = stored(caseId, document.getId());
        DeedPollSupportingDocumentType type = saved.getDocumentType();
        assertThat(type.getName()).isEqualTo("LOC022 Change of name deed for a child");
        assertThat(type.getApplicableTypes()).containsExactly(3, 4);
        assertThat(saved.getOriginalName())
            .isEqualTo("LOC022-change-of-name-deed-for-a-child-" + REFERENCE + ".docx");
    }

    @Test
    @DisplayName("creates the document type the first time it is needed, and reuses it afterwards")
    void createsTheDocumentTypeOnceAndReusesIt() {
        // Nothing seeds deedpoll_supportingdocumenttype, and there is no endpoint to create a row with -- so a
        // fresh environment's first deed has to make its own type, and its second must not make another.
        assertThat(types.count()).isZero();

        deedDocuments.storeDeed(storedCase(), DeedTemplate.ADULT);
        deedDocuments.storeDeed(storedCase(), DeedTemplate.ADULT);

        assertThat(types.count()).isOne();
        assertThat(types.findAll().getFirst().getApplicableTypes()).containsExactly(1, 2);
        assertThat(documents.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("creates only the type it files under, not one per template")
    void createsOnlyTheTypeItFilesUnder() {
        deedDocuments.storeDeed(storedCase(), DeedTemplate.ADULT);

        // The "has this case a deed already" lookup asks by name, so it does not have to make a row for the
        // other deed to find out that there is none.
        assertThat(types.findAll()).extracting(DeedPollSupportingDocumentType::getName)
            .containsExactly("LOC020 Change of name deed");
    }

    @Test
    @DisplayName("404s for a case that does not exist, storing nothing")
    void refusesACaseThatDoesNotExist() {
        assertThatThrownBy(() -> deedDocuments.storeDeed("nope", DeedTemplate.ADULT))
            .isInstanceOf(NotFoundError.class);

        assertThat(documents.count()).isZero();
        assertThat(storedBlobs()).isEmpty();
    }

    @Test
    @DisplayName("the deed reads back through the ordinary document paths")
    void theDeedReadsBackThroughTheOrdinaryDocumentPaths() throws IOException {
        String caseId = storedCase();

        DeedPollSupportingDocument document = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);

        // Written through DocumentService rather than around it, so the documents grid and the download both
        // find it with no special case for a generated document.
        assertThat(documentService.findDocuments(caseId)).extracting(DeedPollSupportingDocument::getId)
            .containsExactly(document.getId());
        try (InputStream download = documentService.downloadDocument(caseId, document.getId()).stream()) {
            assertThat(download.readAllBytes()).isEqualTo(expectedDeed(caseId, DeedTemplate.ADULT));
        }
    }

    @Test
    @DisplayName("deleting the document through the existing path leaves no orphaned blob")
    void deletingTheDocumentLeavesNoOrphanedBlob() {
        String caseId = storedCase();
        DeedPollSupportingDocument document = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);
        assertThat(storedBlobs()).hasSize(1);

        documentService.removeDocument(caseId, document.getId());

        assertThat(documents.count()).isZero();
        assertThat(storedBlobs()).isEmpty();
    }

    @Test
    void missingFilesystemDeedIsRefusedWithoutRegeneration() throws IOException {
        String caseId = storedCase();
        var first = deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);
        Files.delete(STORE_ROOT.resolve(first.getStorageKey()));
        assertThatThrownBy(() -> deedDocuments.storeDeed(caseId, DeedTemplate.ADULT))
            .isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(caseId)).hasSize(1);
        verify(generator, times(1)).generate(any(), any());
    }

    @Test
    void failedFilesystemEnqueueRollsBackTheRowAndBlob() {
        String caseId = storedCase();
        assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status -> {
                deedDocuments.storeDeed(caseId, DeedTemplate.ADULT);
                throw new IllegalStateException("Final enqueue refused");
            })).isInstanceOf(IllegalStateException.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(caseId)).isEmpty();
        assertThat(storedBlobs()).isEmpty();
    }

    /** The patch a caseworker sends to re-file a document as one that came in. */
    private static UpdateDocumentInput incoming() {
        return new UpdateDocumentInput(
            PatchField.of(DocumentDirection.Incoming),
            PatchField.absent(),
            PatchField.absent(),
            PatchField.absent(),
            PatchField.absent(),
            PatchField.absent());
    }

    /** A stored case with enough on it for a deed to print, and a reference number for the filename. */
    private String storedCase() {
        String id = deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
        DeedPoll deedPoll = deedPolls.findById(id).orElseThrow();
        deedPoll.setNewForename("Robin");
        deedPoll.setNewSurname("Okonkwo-Hart");
        deedPoll.setOldForename("Robert");
        deedPoll.setOldSurname("Smith");
        deedPoll.setMaritalStatus(MaritalStatus.MarriedCivilPartnership);
        deedPoll.setNationality(Nationality.BritishCitizen);
        deedPoll.setAgeOfMinor(9);
        deedPoll.setParents("Robert Smith and Ada Smith");
        deedPolls.saveAndFlush(deedPoll);
        return id;
    }

    /** The deed the generator produces for that case, generated independently of the service. */
    private byte[] expectedDeed(String caseId, DeedTemplate template) {
        return new DeedGenerator().generate(deedPolls.findById(caseId).orElseThrow(), template).content();
    }

    private byte[] blobOf(DeedPollSupportingDocument document) throws IOException {
        return Files.readAllBytes(STORE_ROOT.resolve(document.getStorageKey()));
    }

    /**
     * The stored row, read back through the service so its type relation is loaded -- the test has no
     * transaction of its own, and a lazy relation read outside one fails rather than answering.
     */
    private DeedPollSupportingDocument stored(String caseId, String id) {
        return documentService.findDocument(caseId, id);
    }

    /** The keys currently on disk, as filenames under the store root. */
    private static List<String> storedBlobs() {
        try (var blobs = Files.list(STORE_ROOT)) {
            return blobs.map(path -> path.getFileName().toString()).toList();
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not list the document store", failed);
        }
    }
}
