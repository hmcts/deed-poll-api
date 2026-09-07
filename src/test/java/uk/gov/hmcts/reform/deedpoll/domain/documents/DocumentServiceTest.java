package uk.gov.hmcts.reform.deedpoll.domain.documents;

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
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Ports every test block in {@code reference/ts/documents/service.test.ts}.
 *
 * <p><strong>Against a real database and the real local store, not two mocks</strong>, for the reason
 * {@code DeedPollServiceTest}, {@code NoteServiceTest} and {@code WitnessServiceTest} give: the reference
 * asserts the arguments its mocks were called with because that is all a TypeScript unit test can do, and the
 * stored row is the claim those assertions stand for. So each of its shapes translates:
 *
 * <ul>
 *   <li>{@code expect(create).toHaveBeenCalledWith({data: {...dto, deedPollId}})} becomes "the stored document
 *       carries those values, on that parent".</li>
 *   <li>{@code expect(update).toHaveBeenCalledWith({where: {id, deedPollId}, data})} becomes "the patch reached
 *       the row that belongs to that case, and no other row moved" -- the scoping the reference asserts by
 *       inspecting the {@code where} is here a property of which entity was loaded.</li>
 *   <li>{@code expect(create).not.toHaveBeenCalled()} becomes "nothing was stored".</li>
 *   <li>{@code expect(storage.createReadStream).toHaveBeenCalledWith("k1")} becomes "the stream carries the
 *       bytes that were uploaded", which is the claim the argument stands in for.</li>
 * </ul>
 *
 * <p>{@link DocumentStore} is a Mockito <em>spy</em> rather than a mock: every test but one runs the real local
 * store against a temporary directory, so the metadata row and the blob are checked to agree. The one exception
 * is the cleanup-failure pair, where an I/O failure has to be induced -- a full disk cannot be arranged, and
 * the behaviour under test is precisely what happens when one occurs.
 *
 * <p>Not {@code @Transactional}: the writes must really commit so the assertions read committed rows. The
 * tables are cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DocumentServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** The reference's `file` fixture. */
    private static final UploadedFile FILE =
        UploadedFile.of("pdf".getBytes(StandardCharsets.UTF_8), "birth.pdf", "application/pdf");

    /**
     * Blobs go to a throwaway directory, never to the developer's own {@code data/documents}.
     */
    private static final Path STORE_ROOT = temporaryStoreRoot();

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentTypeService documentTypes;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollSupportingDocumentRepository documents;

    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @MockitoSpyBean
    private DocumentStore store;

    /** For the two tests that need a transaction they can roll back around a service call. */
    @Autowired
    private TransactionTemplate transactions;

    private ListAppender<ILoggingEvent> logs;

    @DynamicPropertySource
    static void storeRoot(DynamicPropertyRegistry registry) {
        registry.add("deedpoll.documents.local-store-root", STORE_ROOT::toString);
    }

    private static Path temporaryStoreRoot() {
        try {
            return Files.createTempDirectory("deed-poll-documents");
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not create a temporary document store", failed);
        }
    }

    @BeforeEach
    @AfterEach
    void clearTables() throws IOException {
        documents.deleteAll();
        types.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
        // The blobs go with their rows. Otherwise the store accumulates for the whole class run, and an
        // assertion about what is on disk reads whatever an earlier test left there.
        for (String blob : storedBlobs()) {
            Files.deleteIfExists(STORE_ROOT.resolve(blob));
        }
    }

    @AfterAll
    static void removeTheStoreRoot() throws IOException {
        Files.deleteIfExists(STORE_ROOT);
    }

    @BeforeEach
    void captureLogs() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(DocumentService.class)).addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        ((Logger) LoggerFactory.getLogger(DocumentService.class)).detachAppender(logs);
        logs.stop();
    }

    /** A stored case to hang documents off. */
    private String storedCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    /** A stored document type, since every document needs one. */
    private String storedType() {
        return documentTypes.createDocumentType(CreateDocumentTypeInput.named("Birth certificate")).getId();
    }

    /** The reference's `dto` fixture, with a real type id in place of its "type1". */
    private static CreateDocumentInput dto(String typeId) {
        return CreateDocumentInput.metadata(DocumentDirection.Incoming, "Birth certificate", typeId);
    }

    /** The stored document, read back rather than taken from the returned instance. */
    private DeedPollSupportingDocument stored(String id) {
        return documents.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("creates a document when parent and type both exist")
    void createsADocumentWhenParentAndTypeBothExist() {
        String caseId = storedCase();
        String typeId = storedType();

        String id = documentService.createDocument(caseId, dto(typeId)).getId();

        DeedPollSupportingDocument saved = stored(id);
        assertThat(saved.getDirection()).isEqualTo(DocumentDirection.Incoming);
        assertThat(saved.getName()).isEqualTo("Birth certificate");
        assertThat(saved.getDocumentType().getId()).isEqualTo(typeId);
        assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
        // Metadata only: a row may legitimately exist before its file arrives.
        assertThat(saved.getStorageKey()).isNull();
        assertThat(saved.getSizeBytes()).isNull();
    }

    @Test
    @DisplayName("404s when the parent case is missing")
    void refusesWhenTheParentCaseIsMissing() {
        String typeId = storedType();

        assertThatThrownBy(() -> documentService.createDocument("nope", dto(typeId)))
            .isInstanceOf(NotFoundError.class);

        assertThat(documents.count()).isZero();
    }

    @Test
    @DisplayName("lists documents for a case that exists, with their type included")
    void listsDocumentsForACaseThatExists() {
        String caseId = storedCase();
        String typeId = storedType();
        DeedPollSupportingDocument first = documentService.createDocument(caseId,
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "First", typeId));
        DeedPollSupportingDocument second = documentService.createDocument(caseId,
            CreateDocumentInput.metadata(DocumentDirection.Outgoing, "Second", typeId));
        // Written explicitly rather than relying on two creates landing in different microseconds: the claim
        // under test is the order the query applies, not the clock's resolution.
        setCreatedOn(first.getId(), LocalDateTime.parse("2026-08-01T09:00:00"));
        setCreatedOn(second.getId(), LocalDateTime.parse("2026-08-09T09:00:00"));

        List<DeedPollSupportingDocument> found = documentService.findDocuments(caseId);

        assertThat(found).extracting(DeedPollSupportingDocument::getName).containsExactly("First", "Second");
        // The reference's `include: {documentType: true}`. Read outside the service's transaction, which is
        // where a lazy relation that was not initialised would fail.
        assertThat(found).allSatisfy(document ->
            assertThat(document.getDocumentType().getName()).isEqualTo("Birth certificate"));
    }

    @Test
    @DisplayName("404s listing documents for a case that does not exist, rather than answering an empty list")
    void refusesToListDocumentsForACaseThatDoesNotExist() {
        assertThatThrownBy(() -> documentService.findDocuments("nope")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("returns an empty list for a real case with no documents")
    void returnsAnEmptyListForARealCaseWithNoDocuments() {
        // The other half of the pair: the 404 above must not swallow the legitimately-empty case.
        assertThat(documentService.findDocuments(storedCase())).isEmpty();
    }

    @Test
    @DisplayName("404s when the document type is missing")
    void refusesWhenTheDocumentTypeIsMissing() {
        String caseId = storedCase();

        assertThatThrownBy(() -> documentService.createDocument(caseId,
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Birth certificate",
                "3f0f5b62-1f0a-4c1e-9b3a-2c1d4e5f6a7b")))
            .isInstanceOf(NotFoundError.class);

        assertThat(documents.count()).isZero();
    }

    @Test
    @DisplayName("findDocument 404s across parents")
    void findDocumentRefusesADocumentBelongingToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String documentId = documentService.createDocument(owner, dto(storedType())).getId();

        assertThatThrownBy(() -> documentService.findDocument(other, documentId))
            .isInstanceOf(NotFoundError.class);
        assertThat(documentService.findDocument(owner, documentId).getId()).isEqualTo(documentId);
    }

    @Test
    @DisplayName("removes after an existence check and cleans up the blob")
    void removesAfterAnExistenceCheckAndCleansUpTheBlob() {
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded =
            documentService.uploadDocument(caseId, storedType(), FILE);
        String storageKey = uploaded.getStorageKey();

        documentService.removeDocument(caseId, uploaded.getId());

        assertThat(documents.findById(uploaded.getId())).isEmpty();
        verify(store).delete(storageKey);
        // The blob really went, which is what the reference's `expect(storage.delete).toHaveBeenCalledWith`
        // stands for.
        assertThat(STORE_ROOT.resolve(storageKey)).doesNotExist();
    }

    @Test
    @DisplayName("still succeeds when the blob cleanup fails, because the row is already gone")
    void stillSucceedsWhenTheBlobCleanupFails() {
        // The reference's comment claimed best-effort cleanup while a failed unlink propagated, so a delete that
        // had actually worked answered 500. The caller then retries and gets a 404, with no way to tell the
        // difference. An orphaned blob is recoverable; a contradictory status is not.
        String caseId = storedCase();
        String documentId = documentService.uploadDocument(caseId, storedType(), FILE).getId();
        doThrow(new UncheckedIOException("unlink", new IOException("EACCES: permission denied")))
            .when(store).delete(any());

        documentService.removeDocument(caseId, documentId);

        assertThat(documents.findById(documentId)).isEmpty();
        assertThat(logs.list).isNotEmpty();
    }

    @Test
    @DisplayName("names the document and storage key when cleanup fails, so the orphan can be found")
    void namesTheDocumentAndStorageKeyWhenCleanupFails() {
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, storedType(), FILE);
        doThrow(new UncheckedIOException("unlink", new IOException("EACCES")))
            .when(store).delete(any());

        documentService.removeDocument(caseId, uploaded.getId());

        String logged = logs.list.get(0).getFormattedMessage();
        assertThat(logged).contains(uploaded.getId());
        assertThat(logged).contains(uploaded.getStorageKey());
    }

    @Test
    @DisplayName("removes: keeps the blob when the surrounding transaction rolls back")
    void removesKeepsTheBlobWhenTheTransactionRollsBack() {
        // Not a reference block: there the row delete is its own committed statement, so the unlink cannot
        // run against a delete that did not stick. Here it can -- a wider transaction rolling back after
        // removeDocument restores the metadata row, and an unlink that had already happened would leave it
        // pointing at nothing, downloadable in the view and a permanent 404 in fact. The mirror of
        // uploadsRemovesTheBlobWhenTheTransactionRollsBack, and the unrecoverable half of the same window.
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, storedType(), FILE);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            documentService.removeDocument(caseId, uploaded.getId());
            throw new IllegalStateException("something after the row was deleted");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(documents.findById(uploaded.getId())).isPresent();
        verify(store, never()).delete(any());
        assertThat(STORE_ROOT.resolve(uploaded.getStorageKey())).exists();
    }

    @Test
    @DisplayName("uploads: stores the blob and records metadata")
    void uploadsStoresTheBlobAndRecordsMetadata() {
        String caseId = storedCase();
        String typeId = storedType();

        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, typeId, FILE);

        DeedPollSupportingDocument saved = stored(uploaded.getId());
        assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
        assertThat(saved.getDocumentType().getId()).isEqualTo(typeId);
        assertThat(saved.getDirection()).isEqualTo(DocumentDirection.Incoming);
        assertThat(saved.getName()).isEqualTo("birth.pdf");
        assertThat(saved.getStorageKey()).isNotBlank();
        assertThat(saved.getOriginalName()).isEqualTo("birth.pdf");
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
        assertThat(saved.getSizeBytes()).isEqualTo(3);
        verify(store).save(FILE.content());
    }

    @Test
    @DisplayName("uploads: rejects a request with no file")
    void uploadsRejectsARequestWithNoFile() {
        String caseId = storedCase();
        String typeId = storedType();

        assertThatThrownBy(() -> documentService.uploadDocument(caseId, typeId, null))
            .isInstanceOf(InvalidInputError.class);

        verify(store, never()).save(any());
        assertThat(documents.count()).isZero();
    }

    @Test
    @DisplayName("uploads: takes the caller's name and direction over the defaults")
    void uploadsTakesTheCallersNameAndDirection() {
        // Not a reference block: its "stores the blob and records metadata" exercises only the defaults, and
        // the opts argument goes unasserted. A caseworker sending a sealed deed out has to be able to say so, or
        // every outgoing document is filed as incoming.
        String caseId = storedCase();
        UploadFormInput form = new UploadFormInput(storedType(), Optional.of("Sealed deed"),
            Optional.of(DocumentDirection.Outgoing));

        DeedPollSupportingDocument saved = stored(documentService.uploadDocument(caseId, form, FILE).getId());

        assertThat(saved.getName()).isEqualTo("Sealed deed");
        assertThat(saved.getDirection()).isEqualTo(DocumentDirection.Outgoing);
        // The file's own name is still recorded, so a download can offer it back.
        assertThat(saved.getOriginalName()).isEqualTo("birth.pdf");
    }

    @Test
    @DisplayName("uploads: falls back to the file's name for an empty one, and caps at the column width")
    void uploadsFallsBackAndCaps() {
        // Not a reference block, though its implementation is `opts.name?.slice(0, 100) || file.originalname`.
        // Both halves matter: an empty name in the grid is no more use than no name, and a 200-character one
        // would be a constraint violation on a column of 100.
        String caseId = storedCase();
        String typeId = storedType();

        DeedPollSupportingDocument blank = stored(documentService.uploadDocument(
            caseId, typeId, FILE, Optional.of(""), Optional.empty()).getId());
        DeedPollSupportingDocument long1 = stored(documentService.uploadDocument(
            caseId, typeId, FILE, Optional.of("a".repeat(200)), Optional.empty()).getId());

        assertThat(blank.getName()).isEqualTo("birth.pdf");
        assertThat(long1.getName()).isEqualTo("a".repeat(100));
    }

    @Test
    @DisplayName("uploads: stores nothing when the case does not exist, so no blob is orphaned")
    void uploadsStoresNothingWhenTheCaseDoesNotExist() {
        // Not a reference block -- its mock cannot tell an orphaned blob from a cleaned-up one. Both checks run
        // before the store is touched, which is the ordering this pins.
        String typeId = storedType();

        assertThatThrownBy(() -> documentService.uploadDocument("nope", typeId, FILE))
            .isInstanceOf(NotFoundError.class);

        verify(store, never()).save(any());
    }

    @Test
    @DisplayName("uploads: removes the blob when the surrounding transaction rolls back")
    void uploadsRemovesTheBlobWhenTheTransactionRollsBack() {
        // Not a reference block. Checking the case and the type up front closes the ordinary orphan, but not a
        // failure BETWEEN the blob write and the commit: the row rolls back and the bytes stay, with the only
        // record of the key inside the transaction that vanished -- so unlike removeDocument's orphan there is
        // nothing left to find it by. Rolling back an outer transaction is exactly that window.
        String caseId = storedCase();
        String typeId = storedType();

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            documentService.uploadDocument(caseId, typeId, FILE);
            throw new IllegalStateException("something after the blob was written");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(documents.findAll()).isEmpty();
        assertThat(storedBlobs()).isEmpty();
    }

    @Test
    @DisplayName("uploads: keeps the blob when the transaction commits, which is the ordinary case")
    void uploadsKeepsTheBlobWhenTheTransactionCommits() {
        // The other polarity, because a cleanup hook that fired on commit would delete every upload -- and
        // every other assertion here reads the row rather than the file, so nothing else would notice.
        String caseId = storedCase();
        String typeId = storedType();

        transactions.executeWithoutResult(status -> documentService.uploadDocument(caseId, typeId, FILE));

        assertThat(storedBlobs()).hasSize(1);
    }

    /** The keys currently on disk, as filenames under the store root. */
    private static List<String> storedBlobs() {
        try (var blobs = Files.list(STORE_ROOT)) {
            return blobs.map(path -> path.getFileName().toString()).toList();
        } catch (IOException failed) {
            throw new UncheckedIOException("Could not list the document store", failed);
        }
    }

    @Test
    @DisplayName("download: returns the row and a stream")
    void downloadReturnsTheRowAndAStream() throws IOException {
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, storedType(), FILE);

        DocumentDownload download = documentService.downloadDocument(caseId, uploaded.getId());

        assertThat(download.document().getId()).isEqualTo(uploaded.getId());
        verify(store).open(uploaded.getStorageKey());
        try (InputStream stream = download.stream()) {
            assertThat(stream.readAllBytes()).isEqualTo(FILE.content());
        }
    }

    @Test
    @DisplayName("download: 404s when the row has no attached file")
    void downloadRefusesARowWithNoAttachedFile() {
        String caseId = storedCase();
        String documentId = documentService.createDocument(caseId, dto(storedType())).getId();

        assertThatThrownBy(() -> documentService.downloadDocument(caseId, documentId))
            .isInstanceOf(NotFoundError.class);

        verify(store, never()).open(any());
    }

    @Test
    @DisplayName("download: 404s across parents, so a document is not readable through another case")
    void downloadRefusesAcrossParents() {
        // Not a reference block -- there, download goes through findDocument and the guard is asserted once. Here
        // it is worth asserting on the path that returns bytes: this is the one operation whose failure would
        // disclose another case's document rather than merely 500.
        String owner = storedCase();
        String other = storedCase();
        String documentId = documentService.uploadDocument(owner, storedType(), FILE).getId();

        assertThatThrownBy(() -> documentService.downloadDocument(other, documentId))
            .isInstanceOf(NotFoundError.class);

        verify(store, never()).open(any());
    }

    @Test
    @DisplayName("404s updating a document that belongs to another case, before writing")
    void refusesToUpdateADocumentThatBelongsToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String documentId = documentService.createDocument(owner, dto(storedType())).getId();

        assertThatThrownBy(() -> documentService.updateDocument(other, documentId, rename("renamed")))
            .isInstanceOf(NotFoundError.class);

        assertThat(stored(documentId).getName()).isEqualTo("Birth certificate");
    }

    @Test
    @DisplayName("scopes the update to the parent case, so the write does not rely on the guard above it")
    void scopesTheUpdateToTheParentCase() {
        // The reference asserts deedPollId in the update's own `where`. Here the scope is not repeated at the
        // write: the write goes through the entity the guard loaded by id AND parent, and the repository has no
        // finder that omits the parent -- so an unscoped write is not expressible. What is left to assert is the
        // outcome that scoping buys: the right row moved and the other did not.
        String owner = storedCase();
        String other = storedCase();
        String typeId = storedType();
        String mine = documentService.createDocument(owner,
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Mine", typeId)).getId();
        String theirs = documentService.createDocument(other,
            CreateDocumentInput.metadata(DocumentDirection.Incoming, "Theirs", typeId)).getId();

        documentService.updateDocument(owner, mine, rename("renamed"));

        assertThat(stored(mine).getName()).isEqualTo("renamed");
        assertThat(stored(theirs).getName()).isEqualTo("Theirs");
        assertThat(stored(theirs).getDeedPoll().getId()).isEqualTo(other);
    }

    @Test
    @DisplayName("clears a nullable field the patch names as null, and leaves an omitted one alone")
    void clearsANullableFieldNamedAsNull() {
        // Not a reference block: JavaScript gives Prisma the distinction free. Here it is the whole reason
        // UpdateDocumentInput uses PatchField, and detaching a blob from its row is the write that needs it.
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, storedType(), FILE);

        documentService.updateDocument(caseId, uploaded.getId(), new UpdateDocumentInput(
            PatchField.absent(), PatchField.absent(), PatchField.absent(),
            PatchField.cleared(), PatchField.absent(), PatchField.absent()));

        DeedPollSupportingDocument saved = stored(uploaded.getId());
        assertThat(saved.getStorageKey()).isNull();
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
        assertThat(saved.getOriginalName()).isEqualTo("birth.pdf");
    }

    @Test
    @DisplayName("404s updating a document onto a type that does not exist, before writing")
    void refusesToUpdateOntoATypeThatDoesNotExist() {
        // Not a reference block -- its `if (dto.documentTypeId) await assertType(...)` is asserted only through
        // the create path. Resolving the type before anything is written is what keeps a bad patch from leaving
        // the row half-changed.
        String caseId = storedCase();
        String documentId = documentService.createDocument(caseId, dto(storedType())).getId();

        assertThatThrownBy(() -> documentService.updateDocument(caseId, documentId, new UpdateDocumentInput(
            PatchField.absent(), PatchField.of("renamed"),
            PatchField.of("3f0f5b62-1f0a-4c1e-9b3a-2c1d4e5f6a7b"),
            PatchField.absent(), PatchField.absent(), PatchField.absent())))
            .isInstanceOf(NotFoundError.class);

        assertThat(stored(documentId).getName()).isEqualTo("Birth certificate");
    }

    @Test
    @DisplayName("an empty patch changes nothing")
    void anEmptyPatchChangesNothing() {
        // Not a reference block: there the schema test proves an empty patch parses and the service test stops
        // there. A document has no author column to move, so an empty patch really is a no-op.
        String caseId = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(caseId, storedType(), FILE);

        documentService.updateDocument(caseId, uploaded.getId(), UpdateDocumentInput.EMPTY);

        DeedPollSupportingDocument saved = stored(uploaded.getId());
        assertThat(saved.getName()).isEqualTo("birth.pdf");
        assertThat(saved.getStorageKey()).isEqualTo(uploaded.getStorageKey());
        assertThat(saved.getDirection()).isEqualTo(DocumentDirection.Incoming);
    }

    @Test
    @DisplayName("refuses to remove a document through another case, and leaves it stored")
    void refusesToRemoveADocumentThroughAnotherCase() {
        // Not a reference block -- there the delete's own `where` carries deedPollId and the guard is asserted
        // only on the read paths. Here the scope is what the delete is given, so it is worth asserting that a
        // wrong parent cannot delete somebody else's document, nor unlink its blob.
        String owner = storedCase();
        String other = storedCase();
        DeedPollSupportingDocument uploaded = documentService.uploadDocument(owner, storedType(), FILE);

        assertThatThrownBy(() -> documentService.removeDocument(other, uploaded.getId()))
            .isInstanceOf(NotFoundError.class);

        assertThat(documents.findById(uploaded.getId())).isPresent();
        assertThat(STORE_ROOT.resolve(uploaded.getStorageKey())).exists();
    }

    @Test
    @DisplayName("a case's documents collection sees a document added through the service")
    void theCasesCollectionSeesADocumentAddedThroughTheService() {
        // Not a reference block: Prisma has no managed-collection equivalent. The case detail page reads
        // documents off DeedPoll.documents rather than through this service, so the two paths must agree -- and a
        // mapping that wrote the foreign key without the relation being navigable would pass every other test
        // here.
        String caseId = storedCase();
        documentService.createDocument(caseId, dto(storedType()));

        assertThat(deedPollService.findDeedPoll(caseId).deedPoll().getDocuments())
            .extracting(DeedPollSupportingDocument::getName)
            .containsExactly("Birth certificate");
    }

    /** A patch that renames and nothing else, which is the reference's `{name: "renamed"}`. */
    private static UpdateDocumentInput rename(String name) {
        return new UpdateDocumentInput(
            PatchField.absent(), PatchField.of(name), PatchField.absent(),
            PatchField.absent(), PatchField.absent(), PatchField.absent());
    }

    /** Backdates a stored document, so an ordering assertion does not depend on the clock's resolution. */
    private void setCreatedOn(String documentId, LocalDateTime createdOn) {
        DeedPollSupportingDocument document = stored(documentId);
        document.setCreatedOn(createdOn);
        documents.saveAll(List.of(document));
        documents.flush();
    }
}
