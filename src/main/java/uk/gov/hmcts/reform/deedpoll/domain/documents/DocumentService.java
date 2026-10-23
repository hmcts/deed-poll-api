package uk.gov.hmcts.reform.deedpoll.domain.documents;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.PatchField;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.ReadAudit;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Nested CRUD for supporting-document metadata, plus upload and download of the blob behind it.
 *
 * <p>Ported from {@code reference/ts/documents/service.ts}. Every operation is scoped to a parent case, so a
 * document can never be read or mutated outside the case it belongs to. Both the parent case and the
 * referenced type are checked, so a bad foreign key yields a 404 naming what is missing rather than a
 * constraint violation naming a column.
 *
 * <p><strong>How the reference's belt-and-braces scoping ports.</strong> The reference applies the scope
 * twice on the write paths -- a {@code findFirst} guard that produces the {@code NotFoundError}, and
 * {@code deedPollId} repeated in the {@code where} of the {@code update} / {@code delete} that follow. Here,
 * as in {@code NoteService} and {@code WitnessService}, that becomes structural: the write goes through the
 * entity the guard loaded, that entity was fetched by id <em>and</em> parent, and
 * {@code DeedPollSupportingDocumentRepository} offers no finder that omits the parent. There is no path that
 * writes a document fetched by id alone.
 *
 * <p><strong>Metadata and blob are separate on purpose.</strong> A row may exist with no
 * {@code storageKey} -- {@link #createDocument} makes one -- because the source form lets a caseworker record
 * that a document is expected before it arrives. So {@link #downloadDocument} 404s on a row with no attached
 * file, and {@code DocumentsView} renders such a row as not downloadable rather than linking to that 404.
 *
 * <p>The blob itself lives behind {@link DocumentStore}: the local filesystem by default, for development, or CDAM
 * (plan 03a) when {@code deedpoll.documents.store} is {@code cdam}. A CDAM upload is not one step inside a
 * transaction, so it goes through {@link RemoteDocumentUploads} instead; see {@link #uploadDocument}.
 *
 * <p><strong>Not a port: every write records one audit event</strong>, through {@link DocumentUploadAudit}, in the
 * write's transaction. The metadata create, update and delete have no route, so they are the service account's.
 *
 * <p>The service returns entities, not view models, exactly as the reference does. Formatting is
 * {@link DocumentsView}'s job, and the caseworker API maps to its own DTOs.
 */
@Slf4j
@Service
public class DocumentService {
    /**
     * The 404 for a row whose bytes another store wrote: an environment switched between stores keeps the rows the
     * first one wrote, and this store cannot read them. See {@link DocumentStore#serves}.
     */
    static final String NOT_IN_THIS_STORE = "This document's file is held in a store this service no longer uses";

    private final DeedPollRepository deedPolls;
    private final DeedPollSupportingDocumentRepository documents;
    private final DeedPollSupportingDocumentTypeRepository types;
    private final DocumentStore store;
    private final ObjectProvider<RemoteDocumentUploads> remoteUploads;
    private final DocumentUploadAudit uploadAudit;
    private final ReadAudit readAudit;
    private final TransactionTemplate inTransaction;
    private final TransactionTemplate readOnly;

    public DocumentService(DeedPollRepository deedPolls,
                           DeedPollSupportingDocumentRepository documents,
                           DeedPollSupportingDocumentTypeRepository types,
                           DocumentStore store,
                           ObjectProvider<RemoteDocumentUploads> remoteUploads,
                           DocumentUploadAudit uploadAudit,
                           ReadAudit readAudit,
                           PlatformTransactionManager transactions) {
        this.deedPolls = deedPolls;
        this.documents = documents;
        this.types = types;
        this.store = store;
        this.remoteUploads = remoteUploads;
        this.uploadAudit = uploadAudit;
        this.readAudit = readAudit;
        // PROPAGATION_REQUIRED, the @Transactional default: a filesystem upload joins a caller's transaction, as
        // DeedDocumentService.storeDeed's, exactly as it did when the method carried the annotation.
        this.inTransaction = new TransactionTemplate(transactions);
        // The download's read. It joins a caller's transaction too, and is never handed an exception to mark it
        // rollback-only with: see downloadDocument.
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
    }

    /**
     * Records a document on a case, with no file attached yet.
     *
     * @throws NotFoundError if there is no such case, or no such document type
     */
    @Transactional
    public DeedPollSupportingDocument createDocument(String deedPollId, CreateDocumentInput dto) {
        DeedPoll parent = requireCase(deedPollId);
        DeedPollSupportingDocumentType type = requireType(dto.documentTypeId());

        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(parent);
        document.setDocumentType(type);
        document.setDirection(dto.direction());
        document.setName(dto.name());
        document.setStorageKey(dto.storageKey());
        document.setOriginalName(dto.originalName());
        document.setMimeType(dto.mimeType());
        DeedPollSupportingDocument saved = documents.save(document);
        uploadAudit.created(saved);
        return saved;
    }

    /**
     * Documents for a case, oldest first, each with its type loaded.
     *
     * <p>The parent is checked, so a missing case is a 404 rather than an empty list -- the same reasoning
     * {@code NoteService.findNotes} sets out: filtering by {@code deedPollId} alone answers an empty list both
     * for a case that does not exist and for a real case with no documents, and only one of those is true.
     *
     * <p>The type relation is initialised before the entities leave the transaction, reproducing the
     * reference's {@code include: {documentType: true}}. Without it {@link DocumentsView} would fail on a lazy
     * proxy with the session closed, which is a runtime failure where the reference has a query option.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<DeedPollSupportingDocument> findDocuments(String deedPollId) {
        requireCase(deedPollId);
        List<DeedPollSupportingDocument> found = documents.findByDeedPollIdOrderByCreatedOnAsc(deedPollId);
        found.forEach(DocumentService::initialiseType);
        return found;
    }

    /**
     * The documents of a case a download would serve, oldest first, each with its type loaded.
     *
     * <p>{@link #findDocuments} narrowed by the same file and store checks {@link #readableDocument} makes, so a
     * list built from this offers no link whose download would 404. The judicial application view is the caller:
     * a judge is shown attached files only.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<DeedPollSupportingDocument> readableDocuments(String deedPollId) {
        return findDocuments(deedPollId).stream().filter(this::servable).toList();
    }

    /**
     * One document of one case, with its type loaded.
     *
     * @throws NotFoundError if there is no such document, or it belongs to another case
     */
    @Transactional(readOnly = true)
    public DeedPollSupportingDocument findDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = documents.findByIdAndDeedPollId(id, deedPollId)
            .orElseThrow(() -> new NotFoundError("Document " + id + " not found"));
        initialiseType(document);
        return document;
    }

    /**
     * Amends a document's metadata.
     *
     * <p>Only the fields the patch names are written, and a named-as-null nullable field is cleared rather
     * than left alone -- see {@link UpdateDocumentInput}. A patch that moves the document to another type has
     * that type checked first, so an unknown one is a 404 and not a constraint violation.
     *
     * @throws NotFoundError if there is no such document, it belongs to another case, or the new type does
     *     not exist
     */
    @Transactional
    public DeedPollSupportingDocument updateDocument(String deedPollId, String id, UpdateDocumentInput dto) {
        DeedPollSupportingDocument document = findDocument(deedPollId, id);
        final Map<String, Object> before = DocumentUploadAudit.metadata(document);
        // Resolved before anything is written, so a bad type cannot leave a half-applied patch behind.
        Optional<DeedPollSupportingDocumentType> type = dto.documentTypeId().present()
            ? Optional.of(requireType(dto.documentTypeId().value()))
            : Optional.empty();

        dto.direction().ifPresent(document::setDirection);
        dto.name().ifPresent(document::setName);
        type.ifPresent(document::setDocumentType);
        dto.storageKey().ifPresent(document::setStorageKey);
        dto.originalName().ifPresent(document::setOriginalName);
        dto.mimeType().ifPresent(document::setMimeType);
        // Stamped here rather than left to @PreUpdate, which Hibernate only fires when its dirty check
        // finds a changed property -- so an empty patch, or one resending the values already stored, would
        // leave modifiedOn where it was. The reference's `prisma.update` writes @updatedAt on every call.
        document.setModifiedOn(AuditClock.now());
        DeedPollSupportingDocument saved = documents.save(document);
        uploadAudit.updated(before, saved, patched(dto));
        return saved;
    }

    /** The fields a metadata patch named, in the order the patch declares them. */
    private static List<String> patched(UpdateDocumentInput dto) {
        Map<String, PatchField<?>> named = new LinkedHashMap<>();
        named.put("direction", dto.direction());
        named.put("name", dto.name());
        named.put("documentTypeId", dto.documentTypeId());
        named.put("storageKey", dto.storageKey());
        named.put("originalName", dto.originalName());
        named.put("mimeType", dto.mimeType());
        return named.entrySet().stream().filter(field -> field.getValue().present()).map(Map.Entry::getKey).toList();
    }

    /**
     * Deletes a document and, best-effort, the blob behind it.
     *
     * <p><strong>The blob cleanup is genuinely best-effort, and that is a correction the reference records.</strong>
     * Its comment used to claim best-effort while a failed unlink propagated: the row was already gone by
     * then, so a failed delete of the file turned a completed delete into a 500 -- the caller retried, got a
     * 404, and had no way to tell the delete had in fact worked. An orphaned blob is recoverable and cheap; a
     * status code that contradicts what happened is neither. The failure is logged with both ids so the orphan
     * can be found.
     *
     * <p><strong>The unlink waits for the commit</strong>, which is the mirror of what
     * {@link #removeBlobIfTheWriteDoesNotCommit} does for an upload. The reference has no enclosing
     * transaction, so its row delete is already committed by the time it unlinks and its comment leans on
     * exactly that -- "the row is already gone at this point". Here the delete is one statement in a
     * transaction that has not committed yet, so unlinking first would let a failed commit (a lost
     * connection, or a wider {@code @Transactional} caller that rolls back later) restore the metadata row
     * while its bytes were permanently gone: a row that renders as downloadable and 404s for ever. Of the
     * two failure modes only the orphaned blob is recoverable, so that is the one this keeps.
     *
     * <p><strong>On a remote store, the document also comes off the CCD case first</strong> (plan 03a, task 5):
     * {@link RemoteDocumentUploads#removal} reads, inside this transaction, what the case keeps, and its work runs
     * after the commit ahead of the unlink. It is best effort on the same reasoning, and makes no remote call before
     * the commit.
     *
     * @throws NotFoundError if there is no such document, or it belongs to another case
     */
    @Transactional
    public void removeDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = findDocument(deedPollId, id);
        String storageKey = cleanupLocator(document);
        RemoteDocumentUploads remote = remoteUploads.getIfAvailable();
        Runnable removal = remote == null ? () -> {
        } : remote.removal(document);
        uploadAudit.deleted(document);
        documents.delete(document);
        removeBlobOnceTheDeleteCommits(id, storageKey, removal);
    }

    /**
     * Uploads a file for this caller with the form's own options, which is the shape a multipart request arrives in.
     */
    public DeedPollSupportingDocument uploadDocument(String deedPollId, UploadFormInput form, UploadedFile file,
                                                    CurrentUser caller) {
        return upload(deedPollId, form.documentTypeId(), file, form.name(), form.direction(), AuditActor.of(caller));
    }

    /** As the service account, with the form's own options. */
    public DeedPollSupportingDocument uploadDocument(String deedPollId, UploadFormInput form, UploadedFile file) {
        return uploadDocument(deedPollId, form.documentTypeId(), file, form.name(), form.direction());
    }

    /** As the service account, taking the default direction and the file's own name. */
    public DeedPollSupportingDocument uploadDocument(String deedPollId, String documentTypeId, UploadedFile file) {
        return uploadDocument(deedPollId, documentTypeId, file, Optional.empty(), Optional.empty());
    }

    /** As the service account, with every option given. */
    public DeedPollSupportingDocument uploadDocument(String deedPollId,
                                                    String documentTypeId,
                                                    UploadedFile file,
                                                    Optional<String> name,
                                                    Optional<DocumentDirection> direction) {
        return upload(deedPollId, documentTypeId, file, name, direction, AuditActor.system());
    }

    /**
     * Stores a file and records its metadata row.
     *
     * <p><strong>On the filesystem store, in one step.</strong> The blob is written first, then the row records the
     * returned storage key plus the original filename, declared MIME type and size, in one transaction -- the
     * caller's, when there is one. Both the case and the type are checked before anything is stored, so a bad
     * request does not leave an orphaned blob behind.
     *
     * <p><strong>A rollback after the blob is written removes it.</strong> Checking the case and the type up
     * front closes the ordinary orphan; what it cannot close is a failure between the write and the commit --
     * a flush error, a constraint violation, a lost connection -- which rolls the row back and leaves bytes on
     * disk that nothing references. Unlike {@link #removeDocument}'s orphan, this one is unrecoverable by
     * hand, because the only record of the key was in the transaction that vanished. So the cleanup is
     * registered against the transaction and is best-effort in the same way and for the same reason: a failed
     * unlink is logged with the key, never allowed to replace the error that caused the rollback.
     *
     * <p><strong>On a remote store, through {@link RemoteDocumentUploads}</strong>, which commits the row before it
     * calls out and commits again after, and refuses to run inside a transaction. That is why this method carries no
     * {@code @Transactional} of its own: the filesystem path opens (or joins) one here, and the remote path must not
     * be given one. A caller that already holds a transaction -- {@code DeedDocumentService.storeDeed} -- therefore
     * works on the filesystem store only; moving generated deeds onto CDAM is plan 03b's.
     *
     * <p><strong>Either way, the row's first commit carries its audit event</strong> ({@link DocumentUploadAudit}),
     * so no uploaded row exists without one.
     *
     * @throws InvalidInputError if there is no file
     * @throws NotFoundError if there is no such case, or no such document type
     */
    private DeedPollSupportingDocument upload(String deedPollId,
                                              String documentTypeId,
                                              UploadedFile file,
                                              Optional<String> name,
                                              Optional<DocumentDirection> direction,
                                              AuditActor actor) {
        if (file == null) {
            throw new InvalidInputError("Select a file to upload");
        }
        UploadContentPolicy.verify(file);
        DocumentUpload upload = DocumentUpload.of(deedPollId, documentTypeId, file, name, direction);
        RemoteDocumentUploads remote = remoteUploads.getIfAvailable();
        if (remote != null) {
            return remote.upload(upload, actor);
        }
        return inTransaction.execute(status -> storeAndRecord(upload, actor));
    }

    private DeedPollSupportingDocument storeAndRecord(DocumentUpload upload, AuditActor actor) {
        DeedPoll parent = requireCase(upload.deedPollId());
        DeedPollSupportingDocumentType type = requireType(upload.documentTypeId());

        String storageKey = store.save(upload.toStore(DocumentOwner.of(parent))).storageKey();
        removeBlobIfTheWriteDoesNotCommit(storageKey);

        DeedPollSupportingDocument document = upload.newRow(parent, type);
        document.setStorageKey(storageKey);
        DeedPollSupportingDocument saved = documents.save(document);
        uploadAudit.uploaded(saved, actor);
        return saved;
    }

    /**
     * A document's metadata plus a stream over its blob.
     *
     * <p>404s on a row with no attached file, because a metadata-only row is a legitimate state and "this
     * document has no file yet" is a truer answer than an empty body. The same for a row this store does not serve:
     * on CDAM that is every row not yet {@code Attached}, so an upload whose answer was lost is never served, and a
     * row the filesystem store wrote before the switch is not handed to CDAM as if its key were a CDAM id.
     *
     * <p><strong>The row is found by both ids before the store is asked</strong>, so a document belonging to another
     * case is a 404 here and CDAM is never called for it. CDAM then applies its own permission check to the caller,
     * whose token the CDAM store sends.
     *
     * <p><strong>The store is opened after the read, never inside it</strong> (plan 03a): a CDAM download is a remote
     * call, and a remote call must not hold a database transaction open. A caller that brings its own transaction
     * still holds it -- the Notify drain does, and moving its documents onto CDAM is plan 03b's.
     *
     * <p><strong>A failure here must not roll back the caller's transaction.</strong> This method writes nothing,
     * so there is nothing of its own to roll back -- but a <em>participating</em> transactional boundary that
     * throws marks the caller's transaction rollback-only, and the caller's commit then fails whatever the caller
     * did about the exception. So the method carries no {@code @Transactional}, and the read inside it returns an
     * empty answer rather than throwing: every refusal is raised outside any boundary of this service's own. (It was
     * {@code noRollbackFor = Exception.class} until the store call had to leave the transaction.)
     * {@code DocumentUploads} is the caller that made that reachable: it catches a missing blob and answers a
     * retryable refusal, inside a drain that runs a whole batch in one transaction -- so a document that would not
     * read took rows Notify had already accepted down with it and emailed those applicants again on the next
     * drain, which is the hazard {@code CLAUDE.md} names for the drain's case note.
     *
     * @throws NotFoundError if there is no such document, it belongs to another case, or it has no attached
     *     file
     */
    public DocumentDownload downloadDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = readableDocument(deedPollId, id);
        return new DocumentDownload(document, store.open(locator(document)));
    }

    /**
     * {@link #downloadDocument(String, String)} for a member of staff, recorded as {@code DOCUMENT_DOWNLOADED}.
     *
     * <p>The row is committed after the checks and <strong>before the store is opened</strong>, so a refused
     * download records nothing and a download that could not be recorded opens nothing: it fails closed. The
     * service's own reads (the outbox, deed regeneration checks) use the two-argument form and record nothing,
     * because nobody downloaded anything.
     *
     * @throws NotFoundError as {@link #downloadDocument(String, String)} does
     */
    public DocumentDownload downloadDocument(String deedPollId, String id, CurrentUser reader) {
        DeedPollSupportingDocument document = readableDocument(deedPollId, id);
        readAudit.documentDownloaded(deedPollId, document.getId(), reader);
        return new DocumentDownload(document, store.open(locator(document)));
    }

    /**
     * The same document {@link #downloadDocument(String, String, CurrentUser)} would serve this reader, for a
     * preview: the same ownership, file and store checks, now, and the same store opened under the reader's own
     * authority, later.
     *
     * <p>Nothing is recorded or opened here. {@link DocumentPreviewRead#open} commits {@code DOCUMENT_PREVIEWED} and
     * only then opens the store, so a preview refused before it reads -- busy, unconfigured, out of time -- records
     * nothing, and one whose audit cannot be committed opens nothing. It never takes the system reader's path:
     * a store read this reader is refused stays refused.
     *
     * @throws NotFoundError as {@link #downloadDocument(String, String)} does, before anything is recorded
     */
    public DocumentPreviewRead previewDocument(String deedPollId, String id, CurrentUser reader) {
        DeedPollSupportingDocument document = readableDocument(deedPollId, id);
        String locator = locator(document);
        return new DocumentPreviewRead(document, () -> {
            readAudit.documentPreviewed(deedPollId, document.getId(), reader);
            return store.open(locator);
        });
    }

    /** Shared ownership and readiness checks; authority is selected only by the calling reader. */
    public DeedPollSupportingDocument readableDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = readOnly.execute(status ->
                documents.findByIdAndDeedPollId(id, deedPollId).map(found -> {
                    initialiseType(found);
                    return found;
                }))
            .orElseThrow(() -> new NotFoundError("Document " + id + " not found"));
        if (!hasFile(document)) {
            throw new NotFoundError("This document has no attached file");
        }
        if (!store.serves(document.getStorageState())) {
            throw new NotFoundError(NOT_IN_THIS_STORE);
        }
        return document;
    }

    /**
     * Whether {@link #readableDocument} would serve this row: it has a file, and this store wrote it.
     */
    private boolean servable(DeedPollSupportingDocument document) {
        return hasFile(document) && store.serves(document.getStorageState());
    }

    private static boolean hasFile(DeedPollSupportingDocument document) {
        return document.getStorageKey() != null && !document.getStorageKey().isBlank();
    }

    /**
     * Deletes a blob once the row that referenced it is really gone.
     *
     * <p>Runs the unlink inline when there is no transaction to hang it on -- a direct call outside one has
     * already had its delete committed by the repository, so waiting would mean never running.
     *
     * <p>A failed unlink is logged and swallowed either way. It used to propagate while claiming to be
     * best-effort, and the reference records the correction: the row was already gone, so a failed unlink
     * turned a completed delete into a 500, the caller retried and got a 404, and had no way to tell the
     * delete had in fact worked. Both ids are logged so the orphan can be found.
     */
    private void removeBlobOnceTheDeleteCommits(String id, String storageKey, Runnable removal) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            removal.run();
            unlink(id, storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                removal.run();
                unlink(id, storageKey);
            }
        });
    }

    private void unlink(String id, String storageKey) {
        try {
            // Called unconditionally, as the reference does: the store no-ops on a null key, so a
            // metadata-only row needs no special case here.
            store.delete(storageKey);
        } catch (RuntimeException failed) {
            log.error("Deleted document {} but could not remove its stored file (key {})", id, storageKey, failed);
        }
    }

    /**
     * Deletes a just-written blob if the transaction that was going to reference it rolls back.
     *
     * <p>No-ops when there is no transaction to hang the cleanup on -- a direct call outside one owns its own
     * ordering, and there is nothing to be told about.
     */
    private void removeBlobIfTheWriteDoesNotCommit(String storageKey) {
        if (storageKey == null || !TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                // Only a KNOWN rollback deletes the file. STATUS_UNKNOWN means the commit's outcome was never
                // established -- the database acknowledged nothing either way, typically a connection lost
                // mid-commit -- and the row may well be there. Deleting on that would leave a committed
                // document permanently listed and permanently un-downloadable, which nothing can repair,
                // whereas keeping the file leaves at worst an orphaned blob whose key is logged by the
                // delete path and which costs only disk. The asymmetry is the point: of the two ways to be
                // wrong here, only one is recoverable.
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    store.delete(storageKey);
                } catch (RuntimeException failed) {
                    log.error("Upload rolled back but its stored file could not be removed (key {})",
                              storageKey, failed);
                }
            }
        });
    }

    /** Only clean up bytes belonging to the selected backend, including remote uploads that never attached. */
    private String cleanupLocator(DeedPollSupportingDocument document) {
        if (document.getStorageState() == DocumentStorageState.Local) {
            return store.serves(DocumentStorageState.Local) ? document.getStorageKey() : null;
        }
        return store.serves(DocumentStorageState.Attached) && document.getCdamDocumentId() != null
            ? document.getCdamDocumentId().toString() : null;
    }

    /**
     * What the store is given to open a row's bytes by: the CDAM document id when CDAM holds them, and the
     * storage key otherwise.
     *
     * <p>Never the storage key for a CDAM row, although an {@code Attached} row carries its CDAM id there too: the
     * key is patchable through {@link #updateDocument}, and a CDAM id must never be taken from a request (plan 03a,
     * decision 7). The id column is written by the CDAM store alone. Readability and backend ownership are checked
     * before this locator is used.
     */
    private static String locator(DeedPollSupportingDocument document) {
        return document.getCdamDocumentId() != null
            ? document.getCdamDocumentId().toString()
            : document.getStorageKey();
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }

    private DeedPollSupportingDocumentType requireType(String documentTypeId) {
        return types.findById(documentTypeId)
            .orElseThrow(() -> new NotFoundError("Document type " + documentTypeId + " not found"));
    }

    /**
     * Touches the lazy type relation so it is loaded before the entity leaves the transaction.
     *
     * <p>One query per document rather than a fetch join, which is what the reference's {@code include} does
     * too -- and a join would be wasted here, since the same handful of types recur across a case's documents
     * and the session already has them after the first.
     */
    private static void initialiseType(DeedPollSupportingDocument document) {
        document.getDocumentType().getName();
    }
}
