package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;

import java.util.List;
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
 * <p>The blob itself lives behind {@link DocumentStore}. Real storage is CDAM, in plan 03; the only
 * implementation in this plan is the local filesystem, for development.
 *
 * <p>The service returns entities, not view models, exactly as the reference does. Formatting is
 * {@link DocumentsView}'s job, and the caseworker API maps to its own DTOs.
 */
@Service
public class DocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);

    /**
     * The direction an upload gets when the caller does not say: an applicant-supplied document.
     *
     * <p>The reference defaults the same way, and the reason is which way round the traffic mostly goes --
     * the court receives far more than it sends, and a default that is wrong for the rarer case is corrected
     * by a caseworker who has to think about direction anyway when sending something out.
     */
    private static final DocumentDirection DEFAULT_DIRECTION = DocumentDirection.Incoming;

    private final DeedPollRepository deedPolls;
    private final DeedPollSupportingDocumentRepository documents;
    private final DeedPollSupportingDocumentTypeRepository types;
    private final DocumentStore store;

    public DocumentService(DeedPollRepository deedPolls,
                           DeedPollSupportingDocumentRepository documents,
                           DeedPollSupportingDocumentTypeRepository types,
                           DocumentStore store) {
        this.deedPolls = deedPolls;
        this.documents = documents;
        this.types = types;
        this.store = store;
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
        return documents.save(document);
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
        return documents.save(document);
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
     * @throws NotFoundError if there is no such document, or it belongs to another case
     */
    @Transactional
    public void removeDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = findDocument(deedPollId, id);
        String storageKey = document.getStorageKey();
        documents.delete(document);
        removeBlobOnceTheDeleteCommits(id, storageKey);
    }

    /** Uploads a file with the form's own options, which is the shape a multipart request arrives in. */
    @Transactional
    public DeedPollSupportingDocument uploadDocument(String deedPollId, UploadFormInput form, UploadedFile file) {
        return uploadDocument(deedPollId, form.documentTypeId(), file, form.name(), form.direction());
    }

    /** Uploads a file, taking the default direction and the file's own name. */
    @Transactional
    public DeedPollSupportingDocument uploadDocument(String deedPollId, String documentTypeId, UploadedFile file) {
        return uploadDocument(deedPollId, documentTypeId, file, Optional.empty(), Optional.empty());
    }

    /**
     * Stores a file and records its metadata row in one step.
     *
     * <p>The blob is written first, then the row records the returned storage key plus the original filename,
     * declared MIME type and size. Both the case and the type are checked before anything is stored, so a bad
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
     * <p>The stored name is the caller's, capped at the column's 100 characters, falling back to the file's own
     * name when the caller gives none or gives an empty one -- the reference's {@code ||} rather than
     * {@code ??}, because an empty name in the grid is no more use than no name at all.
     *
     * @throws InvalidInputError if there is no file
     * @throws NotFoundError if there is no such case, or no such document type
     */
    @Transactional
    public DeedPollSupportingDocument uploadDocument(String deedPollId,
                                                    String documentTypeId,
                                                    UploadedFile file,
                                                    Optional<String> name,
                                                    Optional<DocumentDirection> direction) {
        if (file == null) {
            throw new InvalidInputError("Select a file to upload");
        }
        DeedPoll parent = requireCase(deedPollId);
        DeedPollSupportingDocumentType type = requireType(documentTypeId);

        String storageKey = store.save(file.content());
        removeBlobIfTheWriteDoesNotCommit(storageKey);

        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(parent);
        document.setDocumentType(type);
        document.setDirection(direction.orElse(DEFAULT_DIRECTION));
        document.setName(cap(name.filter(given -> !given.isEmpty()).orElse(file.originalName())));
        document.setStorageKey(storageKey);
        document.setOriginalName(file.originalName());
        document.setMimeType(file.mimeType());
        document.setSizeBytes(file.size());
        return documents.save(document);
    }

    /**
     * A document's metadata plus a stream over its blob.
     *
     * <p>404s on a row with no attached file, because a metadata-only row is a legitimate state and "this
     * document has no file yet" is a truer answer than an empty body.
     *
     * @throws NotFoundError if there is no such document, it belongs to another case, or it has no attached
     *     file
     */
    @Transactional(readOnly = true)
    public DocumentDownload downloadDocument(String deedPollId, String id) {
        DeedPollSupportingDocument document = findDocument(deedPollId, id);
        if (document.getStorageKey() == null || document.getStorageKey().isBlank()) {
            throw new NotFoundError("This document has no attached file");
        }
        return new DocumentDownload(document, store.open(document.getStorageKey()));
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
    private void removeBlobOnceTheDeleteCommits(String id, String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            unlink(id, storageKey);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
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
            LOG.error("Deleted document {} but could not remove its stored file (key {})", id, storageKey, failed);
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
                if (status == STATUS_COMMITTED) {
                    return;
                }
                try {
                    store.delete(storageKey);
                } catch (RuntimeException failed) {
                    LOG.error("Upload rolled back but its stored file could not be removed (key {})",
                              storageKey, failed);
                }
            }
        });
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
     * The reference's {@code .slice(0, 100)}: the column's width, applied rather than left to fail.
     */
    private static String cap(String name) {
        return name.length() <= DocumentSchemas.MAX_NAME_LENGTH
            ? name
            : name.substring(0, DocumentSchemas.MAX_NAME_LENGTH);
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
