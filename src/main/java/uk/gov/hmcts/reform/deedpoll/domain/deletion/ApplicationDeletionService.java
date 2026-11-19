package uk.gov.hmcts.reform.deedpoll.domain.deletion;

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
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettingsService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.CaseLastChanged;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository.DocumentLocation;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionAdmissionRepository.LockedCase;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeletionRequestRepository.NewDeletionRequest;
import uk.gov.hmcts.reform.deedpoll.repository.RemovedDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.RemovedDocumentRepository.RemovedDocument;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Admits a Delivery Manager's request to delete an application permanently, and reads it back (plan
 * 20261118-dm-application-deletion, Task 3).
 *
 * <p><strong>Admission is not deletion.</strong> It writes, in one short transaction under the case's
 * {@code FOR UPDATE} lock: the request (which is the fence the rest of the service honours), the immutable cleanup
 * inventory, and {@code CASE_DELETION_REQUESTED} under the verified caller with the reason, note and request id. The
 * audit trail's insert joins that transaction, so a failed audit leaves no request and no fence. Nothing remote is
 * called and nothing is removed here; the case stays stored until the worker's verified cleanup finalises it.
 *
 * <p><strong>The grace period is frozen here.</strong> {@code restorable_until} is the admission time plus the
 * Delivery Manager's {@link ProcessSettingsService#deletionGracePeriod()}, read once, now, and never again: a later
 * change to the setting moves no admitted request's date. The request is first due then, and the worker's claim
 * refuses it before then whatever else says it is due.
 *
 * <p><strong>The order is fixed.</strong> The capability, before any lookup or payload check, so a caller without it
 * learns nothing about the case or the body; then the caller's subject; then the body; then an existing request for
 * the case, which answers a replay even after the case is gone; then the lock, and the existing request again under
 * it, so a concurrent admission is answered as a replay rather than as a second deletion; then the refusals, each
 * a {@link DeletionConflictError} that writes nothing.
 *
 * <p><strong>Replays.</strong> The case's one request (V48's {@code UNIQUE (case_id)}) is matched by key: the same
 * key and body is that request, the same key with another body is {@link DeletionRefusal#KEY_REUSED}. Another key for
 * the same reason is that request too, keeping the first Delivery Manager, note and key; for the other reason it is
 * {@link DeletionRefusal#CONFLICTING_REASON}. A replay never compares the confirmation timestamp with the case, so it
 * never fails because cleanup has changed or removed it.
 */
@Service
public class ApplicationDeletionService {

    /** The name a refusal gives the Idempotency-Key header. */
    public static final String KEY_FIELD = "Idempotency-Key";
    public static final String REASON_FIELD = "reason";
    public static final String EXPECTED_LAST_CHANGED_ON_FIELD = "expectedLastChangedOn";

    private final DeletionRequestRepository requests;
    private final DeletionAdmissionRepository admission;
    private final DeedPollRepository deedPolls;
    private final AuditTrail audit;
    private final CaseLastChanged lastChanged;
    private final RemovedDocumentRepository removedDocuments;
    private final ProcessSettingsService settings;

    public ApplicationDeletionService(DeletionRequestRepository requests, DeletionAdmissionRepository admission,
                                      DeedPollRepository deedPolls, AuditTrail audit, CaseLastChanged lastChanged,
                                      RemovedDocumentRepository removedDocuments, ProcessSettingsService settings) {
        this.requests = requests;
        this.admission = admission;
        this.deedPolls = deedPolls;
        this.audit = audit;
        this.lastChanged = lastChanged;
        this.removedDocuments = removedDocuments;
        this.settings = settings;
    }

    /**
     * The body of a deletion request as the transport received it, unvalidated.
     *
     * @param reason the reason's name, {@code CREATED_IN_ERROR} or {@code DUPLICATE}
     * @param note the Delivery Manager's own words, which must say why and must not name the applicant
     * @param expectedLastChangedOn the case detail's {@code lastChangedOn} the Delivery Manager reviewed
     */
    public record DeletionCommand(UUID idempotencyKey, String reason, String note,
                                  LocalDateTime expectedLastChangedOn) {

        /** Never the note. */
        @Override
        public String toString() {
            return "DeletionCommand[idempotencyKey=" + idempotencyKey + ", reason=" + reason + "]";
        }
    }

    /**
     * What admission answered.
     *
     * @param admitted whether this call wrote the request; false for every replay
     */
    public record Admission(DeletionRequest request, boolean admitted) {
    }

    /**
     * Admits a deletion of {@code caseId}, or answers the request it already has.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError without {@link Capability#DELETE_APPLICATION}
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller has no subject
     * @throws InvalidInputError for a missing key, an unknown reason, a note outside its rule or no timestamp
     * @throws NotFoundError when there is no such case and no request for it
     * @throws DeletionConflictError for a stale confirmation, a guard, unresolved work or a conflicting replay
     */
    @Transactional
    public Admission request(CurrentUser caller, String caseId, DeletionCommand command) {
        caller.require(Capability.DELETE_APPLICATION);
        final String subject = caller.requireUserId();
        Body body = validate(command);

        Optional<DeletionRequest> existing = requests.findByCase(caseId);
        if (existing.isPresent()) {
            return replay(existing.get(), body);
        }
        Optional<LockedCase> locked = admission.lock(caseId);
        // Read again under the lock: a concurrent admission that held it first has committed by now.
        existing = requests.findByCase(caseId);
        if (existing.isPresent()) {
            return replay(existing.get(), body);
        }
        LockedCase target = locked.orElseThrow(() -> new NotFoundError("DeedPoll not found"));
        refuseIfIneligible(target, body);

        LocalDateTime now = AuditClock.now();
        LocalDateTime restorableUntil = now.plus(settings.deletionGracePeriod()).truncatedTo(ChronoUnit.MILLIS);
        NewDeletionRequest row = new NewDeletionRequest(
            UUID.randomUUID(), caseId, body.key(), body.reason(), body.note(), body.fingerprint(),
            DeletionFingerprint.truncate(body.expectedLastChangedOn()), subject, caller.name(), now,
            target.ccdCaseReference(), restorableUntil);
        if (!requests.insert(row)) {
            // Unreachable while the lock is held, since every admission takes it; answered rather than assumed.
            return replay(requests.findByCase(caseId).orElseThrow(), body);
        }
        inventory(row.id(), target);
        audit.record(AuditEntry.of(AuditAction.CASE_DELETION_REQUESTED, AuditActor.of(caller), AuditEntities.CASE,
                                   caseId, caseId)
                         .withDeletion(body.reason(), body.note(), row.id()),
                     List.of());
        return new Admission(requests.findById(row.id()).orElseThrow(), true);
    }

    /**
     * One deletion request, by its own id, whether or not its case still exists.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError without {@link Capability#DELETE_APPLICATION},
     *     before the id is looked up
     * @throws NotFoundError when there is no such request
     */
    @Transactional(readOnly = true)
    public DeletionRequest read(CurrentUser caller, UUID requestId) {
        caller.require(Capability.DELETE_APPLICATION);
        return requests.findById(requestId).orElseThrow(ApplicationDeletionService::noSuchRequest);
    }

    /**
     * What a restore answered.
     *
     * @param caseId the application that is visible again
     */
    public record Restoration(String caseId) {
    }

    /**
     * Restores an application during its deletion's grace period, by deleting its request: the fence is an
     * {@code EXISTS} over the request, so the case is visible and writable again at commit. Nothing outside this
     * database is touched before the grace period ends, so nothing remote needs undoing.
     *
     * <p><strong>The order is fixed.</strong> The capability, before the id is looked up; then the caller's subject;
     * then the request, unlocked, only to learn its case; then the <strong>case row's lock, then the
     * request's</strong>, which is admission's order, so a restore and an admission of the same case cannot deadlock;
     * then the rule ({@link DeletionRestoreRule}) under both locks, which a worker that claimed the request meanwhile
     * fails; then the delete, its SQL repeating the rule; then {@code CASE_DELETION_RESTORED} under the caller, with
     * the request's id and reason and never its note, in the same transaction, so a failed audit restores nothing.
     *
     * <p>The worker's claim skips a locked request, and claims only once {@code restorable_until} has passed, which
     * the rule refuses, so whichever commits first wins and the other answers as if it had come second.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError without {@link Capability#DELETE_APPLICATION},
     *     before the id is looked up
     * @throws uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError if the caller has no subject
     * @throws NotFoundError when there is no such request, including one already restored
     * @throws DeletionConflictError {@link DeletionRefusal#CLEANUP_STARTED} or
     *     {@link DeletionRefusal#RESTORE_WINDOW_CLOSED}, writing nothing
     */
    @Transactional
    public Restoration restore(CurrentUser caller, UUID requestId) {
        caller.require(Capability.DELETE_APPLICATION);
        caller.requireUserId();
        DeletionRequest seen = requests.findById(requestId).orElseThrow(ApplicationDeletionService::noSuchRequest);
        admission.lock(seen.caseId());
        DeletionRequest locked = requests.lockById(requestId)
            .orElseThrow(ApplicationDeletionService::noSuchRequest);
        LocalDateTime now = AuditClock.now();
        Optional<DeletionRefusal> refusal = DeletionRestoreRule.refusal(locked, now);
        if (refusal.isPresent()) {
            throw new DeletionConflictError(refusal.get());
        }
        if (!requests.deleteRestorable(requestId, now)) {
            // Unreachable while the request's lock is held and the rule has just admitted it; refused, not assumed.
            throw new IllegalStateException("A restorable deletion request was not deleted");
        }
        audit.record(AuditEntry.of(AuditAction.CASE_DELETION_RESTORED, AuditActor.of(caller), AuditEntities.CASE,
                                   locked.caseId(), locked.caseId())
                         .withRestoration(locked.reason(), locked.id()),
                     List.of());
        return new Restoration(locked.caseId());
    }

    private static NotFoundError noSuchRequest() {
        return new NotFoundError("Deletion request not found");
    }

    private record Body(UUID key, DeletionReason reason, DeletionNote note, LocalDateTime expectedLastChangedOn,
                        String fingerprint) {

        @Override
        public String toString() {
            return "Body[key=" + key + ", reason=" + reason + "]";
        }
    }

    private static Body validate(DeletionCommand command) {
        List<FieldError> errors = new ArrayList<>();
        if (command.idempotencyKey() == null) {
            errors.add(new FieldError(KEY_FIELD, "Send a UUID Idempotency-Key with a deletion request"));
        }
        Optional<DeletionReason> reason = DeletionReason.fromName(command.reason());
        if (reason.isEmpty()) {
            errors.add(new FieldError(REASON_FIELD, "Choose why this application is being deleted"));
        }
        DeletionNote note = null;
        try {
            note = DeletionNote.parse(command.note());
        } catch (InvalidInputError refused) {
            errors.addAll(refused.errors());
        }
        if (command.expectedLastChangedOn() == null) {
            errors.add(new FieldError(EXPECTED_LAST_CHANGED_ON_FIELD,
                                      "Send the case's lastChangedOn, as you reviewed it"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError("The deletion request is not valid", errors);
        }
        return new Body(command.idempotencyKey(), reason.get(), note, command.expectedLastChangedOn(),
                        DeletionFingerprint.of(reason.get(), note, command.expectedLastChangedOn()));
    }

    private static Admission replay(DeletionRequest existing, Body body) {
        if (existing.idempotencyKey().equals(body.key())) {
            if (!existing.fingerprint().equals(body.fingerprint())) {
                throw new DeletionConflictError(DeletionRefusal.KEY_REUSED);
            }
            return new Admission(existing, false);
        }
        if (existing.reason() != body.reason()) {
            throw new DeletionConflictError(DeletionRefusal.CONFLICTING_REASON);
        }
        return new Admission(existing, false);
    }

    /** Each refusal reads; none writes, so a refused request leaves the case and its children as they were. */
    private void refuseIfIneligible(LockedCase target, Body body) {
        String id = target.id();
        // Its own statement after the lock, so a change committed while admission waited is counted. Child writes do
        // not lock the case, and their times are stamped before they commit, so two races are left: one committing
        // after this read and before admission commits, and one in flight when the case was reviewed whose stamp is
        // older than a newer write already shown (the maximum does not move). Both are accepted because the deletion
        // can be restored during its grace period. See CaseLastChanged.
        LocalDateTime changed = lastChanged.of(id).orElseThrow(() -> new NotFoundError("DeedPoll not found"));
        if (!DeletionFingerprint.truncate(changed)
            .equals(DeletionFingerprint.truncate(body.expectedLastChangedOn()))) {
            throw new DeletionConflictError(DeletionRefusal.STALE_CONFIRMATION);
        }
        if (target.retainPermanently()) {
            throw new DeletionConflictError(DeletionRefusal.RETAINED_PERMANENTLY);
        }
        if (deedPolls.hasGazetteMembership(id) || deedPolls.hasGazetteRequestEvidence(id)) {
            throw new DeletionConflictError(DeletionRefusal.GAZETTE_EVIDENCE);
        }
        if (deedPolls.hasRetainedDeedPayload(id)) {
            throw new DeletionConflictError(DeletionRefusal.RETAINED_DEED_PAYLOAD);
        }
        if (deedPolls.hasRetainedStagePayload(id)) {
            throw new DeletionConflictError(DeletionRefusal.RETAINED_STAGED_UPLOADS);
        }
        if (admission.hasUnresolvedDocumentStorage(id)) {
            throw new DeletionConflictError(DeletionRefusal.DOCUMENT_STORAGE_UNRESOLVED);
        }
        if (admission.hasUnresolvedCcdCreation(id)) {
            throw new DeletionConflictError(DeletionRefusal.CCD_CREATION_UNRESOLVED);
        }
        if (admission.hasUnsettledSend(id, AuditClock.now())) {
            throw new DeletionConflictError(DeletionRefusal.EMAIL_SEND_UNRESOLVED);
        }
    }

    /**
     * The identifiers cleanup must remove and verify, fixed now so nothing written later can widen the deletion.
     *
     * <p>A filesystem document is the worker's to remove; an attached CDAM document is listed so the tombstone can say
     * it was covered, and the disposer removes it by case reference. A {@code Failed} or settled uncertain upload has
     * no copy against the case. Retained payloads were refused above, so the payload rows have nothing to list.
     *
     * <p>A document a caseworker already removed is listed from its V50 tombstone: a filesystem one as a
     * {@link DeletionStepKind#LOCAL_DOCUMENT} (its unlink was best effort, so the worker removes and verifies it
     * again), a CDAM one as a {@link DeletionStepKind#CDAM_REMOVED_DOCUMENT}, which keeps the request from completing
     * until the dm-store team has purged it.
     */
    private void inventory(UUID requestId, LockedCase target) {
        for (DocumentLocation document : admission.documents(target.id())) {
            if ("Local".equals(document.storageState()) && document.storageKey() != null) {
                requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.LOCAL_DOCUMENT, document.id(),
                                    document.storageKey());
            } else if ("Attached".equals(document.storageState())) {
                requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.CDAM_DOCUMENT, document.id(),
                                    document.cdamDocumentId());
            }
        }
        for (RemovedDocument removed : removedDocuments.ofCase(target.id())) {
            if ("Local".equals(removed.storageState())) {
                requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.LOCAL_DOCUMENT, removed.id(),
                                    removed.locator());
            } else {
                requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.CDAM_REMOVED_DOCUMENT,
                                    removed.id(), removed.locator());
            }
        }
        for (String stage : admission.ownedStages(target.id())) {
            requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.OWNED_STAGE, stage, stage);
        }
        if (target.ccdCaseReference() != null) {
            requests.insertStep(UUID.randomUUID(), requestId, DeletionStepKind.CCD_DISPOSAL, null,
                                target.ccdCaseReference());
        }
    }
}
