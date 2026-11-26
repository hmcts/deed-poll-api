package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentStore;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository.LockedCase;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionFenceRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionAdmissionRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionAdmissionRepository.LockedDocument;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DocumentDeletionRequestRepository.NewDocumentDeletionRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Admits a member of staff's request to delete one supporting document permanently, and reads it back (plan
 * 20261125-permanent-document-deletion, Task 3; contracts in docs/DOCUMENT-DELETION.md).
 *
 * <p><strong>Admission is not deletion.</strong> It writes, in one short transaction under the case's
 * {@code FOR UPDATE} lock and then the document's: the request (which is the document's fence), its remote steps
 * with the locators they remove, and {@code DOCUMENT_DELETION_REQUESTED} under the verified caller with the request's
 * id and reason. The audit insert joins the transaction, so a failed audit leaves no request and no fence. Nothing
 * remote is called and nothing is removed here.
 *
 * <p><strong>Who.</strong> Any staff role the {@code /caseworker/**} rule admits, with no capability beyond it and no
 * uploader ownership; a judge, a caller with no deed poll role and an anonymous one are refused before anything is
 * read. Masters are read-only (Steve, 2026-11-25), and the judicial surface reaches none of this.
 *
 * <p><strong>The order is fixed.</strong> The staff role, then the caller's subject, then the body, then the case as
 * the caller can see it (an application deletion's fence reads as an unknown case), then the case's lock, and the
 * application fence again under it ({@link DocumentDeletionRefusal#APPLICATION_DELETION_PENDING}); then a replay by
 * key, which answers even after the document is gone; then a request another key already admitted for the document;
 * then the document's lock, and the refusals, each a {@link DocumentDeletionConflictError} that writes nothing:
 * version, storage, shared binary, in use.
 *
 * <p><strong>Why the in-use check needs no lease.</strong> Every queued use (an email enqueue, a deed preparation, a
 * promotion) holds a case lock or inserts a child row, which takes the case's key-share lock. Admission's
 * {@code FOR UPDATE} waits for it to commit, and its checks are fresh statements afterwards, so they see it. A use
 * that starts later waits for admission's commit and then meets the fence: the entity restriction hides the document
 * from a JPA read in a statement after the lock, writers that lock only the document ask again
 * ({@code DeedPollSupportingDocumentRepository.findUnfencedByIdAndDeedPollIdForUpdate}), and V51's deferred triggers
 * refuse whatever slips past at commit. A read already streaming may finish; nothing promises otherwise.
 */
@Service
public class DocumentDeletionService {

    /** The name a refusal gives the Idempotency-Key header. */
    public static final String KEY_FIELD = "Idempotency-Key";
    public static final String EXPECTED_DOCUMENT_VERSION_FIELD = "expectedDocumentVersion";

    /**
     * {@code StaffArea.CASEWORKER}'s roles, the deprecated admin role included while it still opens cases: whoever
     * may open the case may delete its documents.
     */
    @SuppressWarnings("deprecation")
    private static final Role[] STAFF = {Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER,
        Role.DEEDPOLL_ADMIN};

    /** An uncertain upload with fewer sends than this may still be sent again (application deletion's predicate). */
    private static final int SETTLED_UPLOAD_ATTEMPTS = 2;

    private static final String NO_SUCH_CASE = "DeedPoll not found";
    private static final String NO_SUCH_REQUEST = "Document deletion request not found";

    private final DocumentDeletionRequestRepository requests;
    private final DocumentDeletionAdmissionRepository documents;
    private final DeletionAdmissionRepository cases;
    private final DeletionFenceRepository applicationFence;
    private final DeedPollRepository deedPolls;
    private final DocumentStore store;
    private final AuditTrail audit;

    // Sonar's S9352 reads `store` as ambiguous between CdamDocumentStore and LocalFilesystemDocumentStore, but each is
    // @ConditionalOnProperty on deedpoll.documents.store with a different value, so exactly one bean ever exists
    // (as DocumentService's constructor says).
    @SuppressWarnings("java:S9352")
    public DocumentDeletionService(DocumentDeletionRequestRepository requests,
                                   DocumentDeletionAdmissionRepository documents, DeletionAdmissionRepository cases,
                                   DeletionFenceRepository applicationFence, DeedPollRepository deedPolls,
                                   DocumentStore store, AuditTrail audit) {
        this.requests = requests;
        this.documents = documents;
        this.cases = cases;
        this.applicationFence = applicationFence;
        this.deedPolls = deedPolls;
        this.store = store;
        this.audit = audit;
    }

    /**
     * The body of a document deletion request as the transport received it, unvalidated.
     *
     * @param reason the staff member's own words
     * @param expectedDocumentVersion the document's {@code documentVersion} the confirmation page showed
     */
    public record DocumentDeletionCommand(UUID idempotencyKey, String reason, Long expectedDocumentVersion) {

        /** Never the reason. */
        @Override
        public String toString() {
            return "DocumentDeletionCommand[idempotencyKey=" + idempotencyKey + ", expectedDocumentVersion="
                + expectedDocumentVersion + "]";
        }
    }

    /**
     * What admission answered.
     *
     * @param admitted whether this call wrote the request; false for every replay
     */
    public record Admission(DocumentDeletionRequest request, boolean admitted) {
    }

    /**
     * Admits a permanent deletion of {@code documentId} on {@code caseId}, or answers the request it already has.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError unless the caller holds a staff role, before anything
     *     is read
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller has no subject
     * @throws InvalidInputError for a missing key, a reason outside its rule or a missing or impossible version
     * @throws NotFoundError when the case or the document is unknown, of another case, or fenced, in today's words
     * @throws DocumentDeletionConflictError for a refusal, writing nothing
     */
    @Transactional
    public Admission request(CurrentUser caller, String caseId, String documentId, DocumentDeletionCommand command) {
        caller.requireAnyRole(STAFF);
        final String subject = caller.requireUserId();
        Body body = validate(documentId, command);

        // The case as the caller sees it: a case an application deletion fenced reads as unknown (the entity's
        // restriction), so admission, replay and status alike answer 404 for it.
        if (caseId == null || deedPolls.findById(caseId).isEmpty()) {
            throw new NotFoundError(NO_SUCH_CASE);
        }
        final LockedCase lockedCase = cases.lock(caseId).orElseThrow(() -> new NotFoundError(NO_SUCH_CASE));
        // Asked again in a statement of its own after the lock, for an application request admitted meanwhile.
        if (applicationFence.hasRequest(caseId)) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.APPLICATION_DELETION_PENDING);
        }

        Optional<DocumentDeletionRequest> replayed = requests.findByCaseAndKey(caseId, body.key());
        if (replayed.isPresent()) {
            return replay(replayed.get(), body);
        }
        Optional<DocumentDeletionRequest> other = requests.findByDocument(documentId)
            .filter(found -> found.caseId().equals(caseId));
        if (other.isPresent()) {
            // Another key already admitted it: the same body is that request, keeping its requester and reason;
            // any other body meets a document that, for everyone else, has gone.
            if (other.get().fingerprint().equals(body.fingerprint())) {
                return new Admission(other.get(), false);
            }
            throw noSuchDocument(documentId);
        }

        LockedDocument document = documents.lockDocument(caseId, documentId)
            .orElseThrow(() -> noSuchDocument(documentId));
        if (requests.fences(documentId)) {
            // Unreachable while admission holds the case lock: every request is admitted under it.
            throw noSuchDocument(documentId);
        }
        refuseIfIneligible(document, body);

        LocalDateTime now = AuditClock.now();
        NewDocumentDeletionRequest row = new NewDocumentDeletionRequest(
            UUID.randomUUID(), caseId, documentId, body.key(), body.reason(), document.name(), body.fingerprint(),
            body.expectedVersion(), subject, caller.name() == null ? "" : caller.name(), now);
        if (!requests.insert(row)) {
            // Unreachable while the case lock is held; answered rather than assumed.
            return replay(requests.findByCaseAndKey(caseId, body.key())
                              .or(() -> requests.findByDocument(documentId))
                              .orElseThrow(), body);
        }
        steps(row.id(), document, lockedCase);
        audit.record(AuditEntry.of(AuditAction.DOCUMENT_DELETION_REQUESTED, AuditActor.of(caller),
                                   AuditEntities.DOCUMENT, documentId, caseId)
                         .withDocumentDeletion(body.reason(), row.id()),
                     List.of());
        return new Admission(requests.findByCaseAndId(caseId, row.id()).orElseThrow(), true);
    }

    /**
     * One document deletion request of a case the caller can open, by its own id, whether or not its document still
     * exists. Access is decided again here: a request id is not permission.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError unless the caller holds a staff role, before the id is
     *     looked up
     * @throws NotFoundError for an unknown or fenced case, or a null, unknown or other case's request
     */
    @Transactional(readOnly = true)
    public DocumentDeletionRequest read(CurrentUser caller, String caseId, UUID requestId) {
        caller.requireAnyRole(STAFF);
        if (caseId == null || deedPolls.findById(caseId).isEmpty()) {
            throw new NotFoundError(NO_SUCH_CASE);
        }
        return Optional.ofNullable(requestId).flatMap(id -> requests.findByCaseAndId(caseId, id))
            .orElseThrow(() -> new NotFoundError(NO_SUCH_REQUEST));
    }

    /**
     * The personalisation keys any template carries a document under, from {@link EmailTemplate} itself, so a new
     * document-carrying template is in-use evidence without anyone remembering.
     */
    static Set<String> documentKeys() {
        Set<String> keys = new TreeSet<>();
        Arrays.stream(EmailTemplate.values()).forEach(template -> keys.addAll(template.documentKeys()));
        return keys;
    }

    private record Body(UUID key, DocumentDeletionReason reason, long expectedVersion, String fingerprint) {

        @Override
        public String toString() {
            return "Body[key=" + key + ", expectedVersion=" + expectedVersion + "]";
        }
    }

    private static Body validate(String documentId, DocumentDeletionCommand command) {
        Objects.requireNonNull(command, "command");
        List<FieldError> errors = new ArrayList<>();
        if (command.idempotencyKey() == null) {
            errors.add(new FieldError(KEY_FIELD, "Send a UUID Idempotency-Key with a document deletion request"));
        }
        DocumentDeletionReason reason = null;
        try {
            reason = DocumentDeletionReason.parse(command.reason());
        } catch (InvalidInputError refused) {
            errors.addAll(refused.errors());
        }
        Long version = command.expectedDocumentVersion();
        if (version == null || version < 1) {
            errors.add(new FieldError(EXPECTED_DOCUMENT_VERSION_FIELD,
                                      "Send the document's documentVersion, as you reviewed it"));
        }
        if (documentId == null || documentId.isBlank() || documentId.indexOf('\n') >= 0) {
            throw noSuchDocument(documentId);
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError("The document deletion request is not valid", errors);
        }
        return new Body(command.idempotencyKey(), reason, version,
                        DocumentDeletionFingerprint.of(documentId, version, reason));
    }

    /** The same key: the same body is that request, whatever has happened to the document since. */
    private static Admission replay(DocumentDeletionRequest existing, Body body) {
        if (!existing.fingerprint().equals(body.fingerprint())) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.KEY_REUSED);
        }
        return new Admission(existing, false);
    }

    /** Each refusal reads; none writes, so a refused request leaves the document usable and the case as it was. */
    private void refuseIfIneligible(LockedDocument document, Body body) {
        if (document.version() != body.expectedVersion()) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.DOCUMENT_CHANGED);
        }
        if (!storageSettled(document)) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
        }
        if (documents.sharesBinary(document)) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.DOCUMENT_SHARED);
        }
        if (documents.hasPendingEmailNaming(document.caseId(), document.id(), documentKeys())
            || documents.hasRetainedDeedPreparation(document.id())
            || documents.hasPromotingStage(document.id())) {
            throw new DocumentDeletionConflictError(DocumentDeletionRefusal.DOCUMENT_IN_USE);
        }
    }

    /**
     * Whether whose bytes these are is settled, and this deployment can remove them: a stored binary in the store
     * this deployment serves, or a row with nothing to remove (metadata only).
     */
    private boolean storageSettled(LockedDocument document) {
        return switch (document.storageState()) {
            case Local -> document.storageKey() == null || store.serves(DocumentStorageState.Local);
            case Attached -> document.cdamDocumentId() != null && store.serves(DocumentStorageState.Attached);
            case Failed -> true;
            case UploadUncertain -> document.uploadAttempts() >= SETTLED_UPLOAD_ATTEMPTS;
            case UploadPending, Uploaded, AttachUncertain -> false;
        };
    }

    /**
     * The remote steps, fixed now with the locators they remove. A {@code Failed} row or a settled uncertain upload
     * has no copy against the case (a landed one expires on CDAM's upload TTL), and a {@code Local} row with no key
     * has no file, so each of those is metadata only and has none.
     */
    private void steps(UUID requestId, LockedDocument document, LockedCase lockedCase) {
        if (document.storageState() == DocumentStorageState.Attached) {
            if (lockedCase.ccdCaseReference() != null) {
                requests.insertStep(UUID.randomUUID(), requestId, DocumentDeletionStepKind.CCD_REFERENCE,
                                    lockedCase.ccdCaseReference());
            }
            requests.insertStep(UUID.randomUUID(), requestId, DocumentDeletionStepKind.CDAM_BINARY,
                                document.cdamDocumentId().toString());
        } else if (document.storageState() == DocumentStorageState.Local && document.storageKey() != null) {
            requests.insertStep(UUID.randomUUID(), requestId, DocumentDeletionStepKind.LOCAL_FILE,
                                document.storageKey());
        }
    }

    private static NotFoundError noSuchDocument(String documentId) {
        return new NotFoundError("Document " + documentId + " not found");
    }
}
