package uk.gov.hmcts.reform.deedpoll.ccd;

import feign.FeignException;
import feign.RetryableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.client.CoreCaseDataApi;
import uk.gov.hmcts.reform.ccd.client.model.CaseDataContent;
import uk.gov.hmcts.reform.ccd.client.model.CaseDetails;
import uk.gov.hmcts.reform.ccd.client.model.Event;
import uk.gov.hmcts.reform.ccd.client.model.StartEventResponse;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.CaseSeen;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.Confirmation;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdCaseProjection;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;

/**
 * The one API-owned linking operation: creates the CCD case for an existing deed-poll record and confirms the link
 * (plan 02, decision 11). {@link CcdLinkingSweep} calls it from the case list request (plan 02b), which links
 * caseworker-created, existing and imported records alike; online submission will call it too.
 *
 * <p><strong>Deliberately not {@code @Transactional}, and no caller may wrap it in a transaction</strong> (decision
 * 9). The claim is committed by {@link CcdCreationAttempts} before CCD is called, because data-store calls back into
 * {@code /ccd-persistence/cases} on a request of its own, and {@link LinkDeedPollHandler} there must read the claim
 * and lock the record. A transaction held across the call would hide the one and deadlock on the other.
 *
 * <p>CCD is reached through its public case API ({@link CoreCaseDataApi}) as the system user, with this service's
 * S2S token from {@code ServiceAuthConfiguration} -- never by calling our own persistence controller.
 *
 * <p><strong>Recovery is by reading, not retrying.</strong> {@link CcdLinkingRule} carries the argument; in short, the
 * local binding commits with the case, so it answers whether a case exists and CCD is read only to confirm it. A
 * fresh start is made only once the binding has been read and a refusal has shown nothing was created. After an
 * unknown outcome it never is, because a fresh start-event token is a fresh {@code Idempotency-Key}: an attempt then
 * waits {@code AwaitingConfirmation} and ends {@code Linked} or, visibly, {@code NeedsRepair}.
 *
 * <p><strong>The start-event token is not persisted</strong> (Task 4's deferral, settled in Task 7). Data-store's
 * {@code EventTokenService.generateToken} sets no expiry claim, so a token does not go stale on a clock, and nothing
 * here resubmits one: it is used once, inside one claim, and what settles an unknown outcome is the binding.
 *
 * <p>Failure detail written onto an attempt is fixed prose plus an HTTP status or an exception's class name --
 * never a message, which for Feign quotes the response body, and never a token.
 */
@Slf4j
@Service
public class CcdLinkingService {

    /** What one call did, or found. The attempt row holds the detail. */
    public enum Result {
        /** This call confirmed the link. */
        LINKED,
        /** The record was already linked; nothing was sent. */
        ALREADY_LINKED,
        /** Another worker holds the record's attempt. */
        IN_PROGRESS,
        /**
         * Nothing was created and the attempt is {@code Pending} again, for a later call -- or it already was, and
         * is not due until its {@code notBefore}, and this call sent nothing.
         */
        RETRY_LATER,
        /** The outcome is not yet known; a later reconciliation reads it again. */
        AWAITING_CONFIRMATION,
        /**
         * As {@code AWAITING_CONFIRMATION}, because a call to CCD failed: a submission whose outcome is unknown, or a
         * read that could not confirm the binding. Apart from it because it says CCD is unwell, which ends a sweep.
         */
        CCD_UNANSWERED,
        /** A person has to settle it. */
        NEEDS_REPAIR,
        /** No record has this id. */
        NO_SUCH_RECORD
    }

    static final String EVENT_SUMMARY = "Link deed poll";

    private final CcdCreationAttempts attempts;
    private final DeedPollRepository deedPolls;
    private final CoreCaseDataApi ccd;
    private final SystemUserTokens systemUser;
    private final ObjectProvider<AuthTokenGenerator> serviceTokens;
    private final CcdProperties identifiers;
    private final CcdLinkingProperties linking;

    public CcdLinkingService(CcdCreationAttempts attempts,
                             DeedPollRepository deedPolls,
                             CoreCaseDataApi ccd,
                             SystemUserTokens systemUser,
                             ObjectProvider<AuthTokenGenerator> serviceTokens,
                             CcdProperties identifiers,
                             CcdLinkingProperties linking) {
        this.attempts = attempts;
        this.deedPolls = deedPolls;
        this.ccd = ccd;
        this.systemUser = systemUser;
        this.serviceTokens = serviceTokens;
        this.identifiers = identifiers;
        this.linking = linking;
    }

    /**
     * Links one record to one CCD case, or carries its attempt on from wherever it had got to.
     *
     * <p>Safe to call any number of times, from any number of workers: one live attempt per record, one claim per
     * attempt, one binding per record.
     */
    public Result link(String deedPollId) {
        Optional<CcdCaseProjection> record = deedPolls.findCcdCaseProjectionById(deedPollId);
        if (record.isEmpty()) {
            return Result.NO_SUCH_RECORD;
        }
        Optional<CcdCreationAttempt> live = attempts.live(deedPollId);
        if (live.isEmpty()) {
            if (deedPolls.findCcdCaseReference(deedPollId).isPresent()) {
                return Result.ALREADY_LINKED;
            }
            live = attempts.open(deedPollId).or(() -> attempts.live(deedPollId));
            if (live.isEmpty()) {
                return current(deedPollId);
            }
        }
        CcdCreationAttempt attempt = live.get();
        return switch (attempt.getStatus()) {
            // Not due: released after a refusal, and waiting out retry-after. The claim would refuse it too.
            case Pending -> CcdLinkingRule.isDue(attempt.getNotBefore(), AuditClock.now())
                ? claimAndCreate(attempt, record.get())
                : Result.RETRY_LATER;
            case Claimed -> leaseExpired(attempt) ? takeFromExpiredClaim(attempt) : Result.IN_PROGRESS;
            case AwaitingConfirmation -> reconcile(attempt);
            case NeedsRepair -> Result.NEEDS_REPAIR;
            case Linked -> Result.ALREADY_LINKED;
        };
    }

    /**
     * Reconciles every attempt nobody is attending to: each expired claim, then each attempt awaiting confirmation.
     *
     * <p>One attempt that cannot be read -- a database fault, say -- is logged and passed over, so that it does not
     * keep every attempt behind it unread on every pass.
     *
     * @return how many attempts were read
     */
    public int reconcileOutstanding() {
        // Both lists first: an expired claim is reconciled as it is taken, and must not be read twice.
        List<CcdCreationAttempt> expiredClaims = attempts.expiredClaims();
        List<CcdCreationAttempt> awaiting = attempts.awaitingConfirmation();
        int read = 0;
        for (CcdCreationAttempt expired : expiredClaims) {
            read += readOne(expired, this::takeFromExpiredClaim);
        }
        for (CcdCreationAttempt waiting : awaiting) {
            read += readOne(waiting, this::reconcile);
        }
        return read;
    }

    /**
     * Reconciles one attempt from {@link CcdCreationAttempts#outstanding}: takes an expired claim off its worker, or
     * reads an attempt awaiting confirmation. The linking sweep's bounded counterpart of
     * {@link #reconcileOutstanding}, and unlike it this lets a failure through, because the sweep stops on one.
     *
     * <p>A claim is taken only while it is still expired, by {@code takeExpiredClaim}'s own condition, so an attempt
     * read before another worker claimed it afresh is left to that worker. Any other status reports what the
     * attempt says now and sends nothing.
     */
    public Result reconcileOne(CcdCreationAttempt outstanding) {
        return switch (outstanding.getStatus()) {
            case Claimed -> takeFromExpiredClaim(outstanding);
            case AwaitingConfirmation -> reconcile(outstanding);
            case Pending, NeedsRepair, Linked -> current(outstanding.getDeedPollId());
        };
    }

    private static int readOne(CcdCreationAttempt attempt, Function<CcdCreationAttempt, Result> reading) {
        try {
            reading.apply(attempt);
            return 1;
        } catch (RuntimeException failure) {
            // Fixed prose and the attempt's own id: the failure's message may quote a response body.
            log.warn("Could not reconcile CCD creation attempt {}: {}", attempt.getId(),
                failure.getClass().getSimpleName());
            return 0;
        }
    }

    private Result claimAndCreate(CcdCreationAttempt pending, CcdCaseProjection record) {
        if (!attempts.claim(pending.getId(), linking.worker(), linking.lease())) {
            // Another worker took it, or it was released again and is not yet due: say which.
            return current(pending.getDeedPollId());
        }
        // The claim matched only a Pending row and added exactly one.
        int tries = pending.getAttempts() + 1;

        String user;
        String service;
        try {
            user = systemUser.accessToken();
            service = serviceTokens.getObject().generate();
        } catch (RuntimeException failure) {
            return nothingCreated(pending, tries,
                "Failed before submission: " + describe("IDAM or S2S", failure));
        }
        StartEventResponse start;
        try {
            start = ccd.startCase(user, service, identifiers.caseTypeId(), DeedPollCaseType.LINK_DEED_POLL);
        } catch (RuntimeException failure) {
            return nothingCreated(pending, tries, "Failed before submission: " + describe(failure));
        }

        // Committed before the call, and only if the claim is still ours (plan 02b, decision 9): from here on the
        // attempt may have reached CCD, so it may never be retried from scratch. A lost claim submits nothing.
        if (!attempts.recordSubmission(pending.getId(), linking.worker(), tries)) {
            return current(pending.getDeedPollId());
        }
        CaseDetails created;
        try {
            created = ccd.submitCaseCreation(user, service, identifiers.caseTypeId(), content(record, start));
        } catch (RuntimeException failure) {
            return switch (CcdLinkingRule.classify(failure)) {
                case REFUSED -> afterRefusal(pending, tries, failure);
                case UNKNOWN -> unanswered(
                    awaitConfirmation(pending, "Submission outcome unknown: " + describe(failure)));
            };
        }

        Optional<String> binding = deedPolls.findCcdCaseReference(record.deedPollId());
        return switch (CcdLinkingRule.confirm(record.deedPollId(), identifiers.caseTypeId(), binding, seen(created))) {
            case CONFIRMED -> moved(pending, Claimed, Linked, null, Result.LINKED);
            case NOT_BOUND -> moved(pending, Claimed, NeedsRepair,
                "CCD reported a case created, but no reference is bound to the record", Result.NEEDS_REPAIR);
            case CONTRADICTED -> moved(pending, Claimed, NeedsRepair,
                "CCD reported a case that does not match the record's binding", Result.NEEDS_REPAIR);
        };
    }

    /**
     * CCD refused the submission. The binding is read before anything else: a record bound by an earlier
     * submission is confirmed rather than sent again, and only a record with nothing bound goes round again.
     */
    private Result afterRefusal(CcdCreationAttempt claimed, int tries, RuntimeException failure) {
        String detail = "Submission refused: " + describe(failure);
        if (deedPolls.findCcdCaseReference(claimed.getDeedPollId()).isPresent()) {
            return awaitConfirmation(claimed, detail);
        }
        return nothingCreated(claimed, tries, detail);
    }

    private Result nothingCreated(CcdCreationAttempt claimed, int tries, String detail) {
        if (!CcdLinkingRule.mayTryAgain(tries, linking.maxAttempts())) {
            return moved(claimed, Claimed, NeedsRepair, detail + "; no attempts left", Result.NEEDS_REPAIR);
        }
        return attempts.release(claimed.getId(), detail,
                CcdLinkingRule.nextDue(AuditClock.now(), linking.retryAfter(), tries))
            ? Result.RETRY_LATER
            : current(claimed.getDeedPollId());
    }

    private Result awaitConfirmation(CcdCreationAttempt claimed, String detail) {
        if (!attempts.move(claimed.getId(), Claimed, AwaitingConfirmation, detail)) {
            return current(claimed.getDeedPollId());
        }
        return reconcileNow(claimed.getDeedPollId());
    }

    private Result takeFromExpiredClaim(CcdCreationAttempt claimed) {
        if (!attempts.takeExpiredClaim(claimed.getId(),
                "The claim's lease expired before its outcome was recorded")) {
            return current(claimed.getDeedPollId());
        }
        return reconcileNow(claimed.getDeedPollId());
    }

    /**
     * Reconciles the attempt this caller has just moved to {@code AwaitingConfirmation}, read afresh.
     */
    private Result reconcileNow(String deedPollId) {
        return attempts.live(deedPollId)
            .filter(attempt -> attempt.getStatus() == AwaitingConfirmation)
            .map(this::reconcile)
            .orElseGet(() -> current(deedPollId));
    }

    /**
     * Reads the binding, and CCD if there is one, and settles an attempt {@code AwaitingConfirmation} -- or leaves
     * it, until the settle window has passed.
     */
    private Result reconcile(CcdCreationAttempt waiting) {
        String deedPollId = waiting.getDeedPollId();
        Optional<String> binding = deedPolls.findCcdCaseReference(deedPollId);
        Optional<Confirmation> confirmation = binding.isEmpty()
            ? Optional.of(Confirmation.NOT_BOUND)
            : read(binding.get()).map(seen ->
                CcdLinkingRule.confirm(deedPollId, identifiers.caseTypeId(), binding, seen));
        boolean settled = waiting.getModifiedOn().plus(linking.settleAfter()).isBefore(AuditClock.now());

        return switch (CcdLinkingRule.reconcile(confirmation, settled)) {
            case LINK -> moved(waiting, AwaitingConfirmation, Linked, null, Result.LINKED);
            // A bound record whose case CCD would not show: the read failed, not the record.
            case WAIT -> binding.isPresent() && confirmation.isEmpty()
                ? Result.CCD_UNANSWERED
                : Result.AWAITING_CONFIRMATION;
            case REPAIR -> repair(waiting, binding.isPresent(), confirmation.isPresent());
        };
    }

    private Result repair(CcdCreationAttempt waiting, boolean bound, boolean ccdRead) {
        if (!bound) {
            // Nothing bound when read -- but a late submission may be binding it now, so it is re-read under the lock.
            return attempts.repairIfUnbound(waiting,
                "No CCD case reference was bound to the record within the settle window")
                ? Result.NEEDS_REPAIR
                : current(waiting.getDeedPollId());
        }
        String detail = ccdRead
            ? "CCD's case does not match the record's binding"
            : "CCD could not be read to confirm the record's binding within the settle window";
        return moved(waiting, AwaitingConfirmation, NeedsRepair, detail, Result.NEEDS_REPAIR);
    }

    /**
     * The case CCD holds under {@code reference}, or empty if CCD could not be read.
     */
    private Optional<CaseSeen> read(String reference) {
        try {
            String user = systemUser.accessToken();
            String service = serviceTokens.getObject().generate();
            return Optional.of(seen(ccd.getCase(user, service, reference)));
        } catch (RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * A result reached after a CCD call failed: still awaiting confirmation is {@code CCD_UNANSWERED}.
     */
    private static Result unanswered(Result result) {
        return result == Result.AWAITING_CONFIRMATION ? Result.CCD_UNANSWERED : result;
    }

    private Result moved(CcdCreationAttempt attempt,
                         CcdCreationAttemptStatus from,
                         CcdCreationAttemptStatus to,
                         String detail,
                         Result result) {
        return attempts.move(attempt.getId(), from, to, detail) ? result : current(attempt.getDeedPollId());
    }

    /** What the record's attempt says now, for a caller whose own move lost a race. */
    private Result current(String deedPollId) {
        return attempts.live(deedPollId)
            .map(attempt -> resultOf(attempt.getStatus()))
            .orElseGet(() -> deedPolls.findCcdCaseReference(deedPollId).isPresent()
                ? Result.ALREADY_LINKED
                : Result.RETRY_LATER);
    }

    private static Result resultOf(CcdCreationAttemptStatus status) {
        return switch (status) {
            case Pending -> Result.RETRY_LATER;
            case Claimed -> Result.IN_PROGRESS;
            case AwaitingConfirmation -> Result.AWAITING_CONFIRMATION;
            case NeedsRepair -> Result.NEEDS_REPAIR;
            case Linked -> Result.ALREADY_LINKED;
        };
    }

    private static boolean leaseExpired(CcdCreationAttempt claimed) {
        return claimed.getLeaseExpiresAt() == null || claimed.getLeaseExpiresAt().isBefore(AuditClock.now());
    }

    private static CaseDataContent content(CcdCaseProjection record, StartEventResponse start) {
        // A LinkedHashMap, not Map.of: a record without a display reference is CCD's to refuse as a mandatory field.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deedPollId", record.deedPollId());
        data.put("deedPollReference", record.displayReference());
        return CaseDataContent.builder()
            .event(Event.builder().id(DeedPollCaseType.LINK_DEED_POLL).summary(EVENT_SUMMARY).build())
            .eventToken(start.getToken())
            .data(data)
            .build();
    }

    private static CaseSeen seen(CaseDetails details) {
        Map<String, Object> data = details.getData() == null ? Map.of() : details.getData();
        return new CaseSeen(CcdCaseReference.of(details.getId()), details.getCaseTypeId(),
            Objects.toString(data.get("deedPollId"), null));
    }

    /** A failure CCD's API threw, as fixed prose. */
    static String describe(RuntimeException failure) {
        return describe("CCD", failure);
    }

    /**
     * A failure as fixed prose: the status {@code called} sent, or the kind of failure. Never its message.
     *
     * @param called what was being called, so that an IDAM or S2S fault is not reported as CCD's
     */
    static String describe(String called, RuntimeException failure) {
        if (failure instanceof RetryableException) {
            return "no response from " + called;
        }
        if (failure instanceof FeignException feign && feign.status() > 0) {
            return called + " answered HTTP " + feign.status();
        }
        return failure.getClass().getSimpleName();
    }
}
