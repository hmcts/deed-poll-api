package uk.gov.hmcts.reform.deedpoll.ccd;

import feign.FeignException;
import feign.RetryableException;
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
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule.Next;
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

import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The one API-owned linking operation: creates the CCD case for an existing deed-poll record and confirms the link
 * (plan 02, decision 11). Online submission and the migration backfill both call it; nothing in this plan does.
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
         * Nothing was created and the attempt is {@code Pending} again, for a later call.
         */
        RETRY_LATER,
        /** The outcome is not yet known; a later reconciliation reads it again. */
        AWAITING_CONFIRMATION,
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
            case Pending -> claimAndCreate(attempt, record.get());
            case Claimed -> leaseExpired(attempt) ? takeFromExpiredClaim(attempt) : Result.IN_PROGRESS;
            case AwaitingConfirmation -> reconcile(attempt);
            case NeedsRepair -> Result.NEEDS_REPAIR;
            case Linked -> Result.ALREADY_LINKED;
        };
    }

    /**
     * Reconciles every attempt nobody is attending to: each expired claim, then each attempt awaiting confirmation.
     *
     * @return how many attempts were read
     */
    public int reconcileOutstanding() {
        // Both lists first: an expired claim is reconciled as it is taken, and must not be read twice.
        List<CcdCreationAttempt> expiredClaims = attempts.expiredClaims();
        List<CcdCreationAttempt> awaiting = attempts.awaitingConfirmation();
        int read = 0;
        for (CcdCreationAttempt expired : expiredClaims) {
            takeFromExpiredClaim(expired);
            read++;
        }
        for (CcdCreationAttempt waiting : awaiting) {
            reconcile(waiting);
            read++;
        }
        return read;
    }

    private Result claimAndCreate(CcdCreationAttempt pending, CcdCaseProjection record) {
        if (!attempts.claim(pending.getId(), linking.worker(), linking.lease())) {
            return Result.IN_PROGRESS;
        }
        int tries = attempts.live(record.deedPollId()).map(CcdCreationAttempt::getAttempts)
            .orElse(pending.getAttempts() + 1);

        String user;
        String service;
        StartEventResponse start;
        try {
            user = systemUser.accessToken();
            service = serviceTokens.getObject().generate();
            start = ccd.startCase(user, service, identifiers.caseTypeId(), DeedPollCaseType.LINK_DEED_POLL);
        } catch (RuntimeException failure) {
            return nothingCreated(pending, tries, "Failed before submission: " + describe(failure));
        }

        CaseDetails created;
        try {
            created = ccd.submitCaseCreation(user, service, identifiers.caseTypeId(), content(record, start));
        } catch (RuntimeException failure) {
            return switch (CcdLinkingRule.classify(failure)) {
                case REFUSED -> afterRefusal(pending, tries, failure);
                case UNKNOWN -> awaitConfirmation(pending, "Submission outcome unknown: " + describe(failure));
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
        return CcdLinkingRule.afterNothingCreated(tries, linking.maxAttempts()) == Next.RELEASE
            ? moved(claimed, Claimed, Pending, detail, Result.RETRY_LATER)
            : moved(claimed, Claimed, NeedsRepair, detail + "; no attempts left", Result.NEEDS_REPAIR);
    }

    private Result awaitConfirmation(CcdCreationAttempt claimed, String detail) {
        if (!attempts.move(claimed.getId(), Claimed, AwaitingConfirmation, detail)) {
            return current(claimed.getDeedPollId());
        }
        return attempts.live(claimed.getDeedPollId())
            .filter(attempt -> attempt.getStatus() == AwaitingConfirmation)
            .map(this::reconcile)
            .orElseGet(() -> current(claimed.getDeedPollId()));
    }

    private Result takeFromExpiredClaim(CcdCreationAttempt claimed) {
        return awaitConfirmation(claimed, "The claim's lease expired before its outcome was recorded");
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
            case WAIT -> Result.AWAITING_CONFIRMATION;
            case REPAIR -> moved(waiting, AwaitingConfirmation, NeedsRepair, repairDetail(confirmation),
                Result.NEEDS_REPAIR);
            case RELEASE -> throw new IllegalStateException("Reconciliation never releases an attempt");
        };
    }

    private static String repairDetail(Optional<Confirmation> confirmation) {
        if (confirmation.isEmpty()) {
            return "CCD could not be read to confirm the record's binding within the settle window";
        }
        return switch (confirmation.get()) {
            case NOT_BOUND -> "No CCD case reference was bound to the record within the settle window";
            case CONTRADICTED -> "CCD's case does not match the record's binding";
            case CONFIRMED -> throw new IllegalStateException("A confirmed link is not a repair");
        };
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

    /** A failure as fixed prose: the status CCD sent, or the kind of failure. Never its message. */
    static String describe(RuntimeException failure) {
        if (failure instanceof RetryableException) {
            return "no response from CCD";
        }
        if (failure instanceof FeignException feign && feign.status() > 0) {
            return "CCD answered HTTP " + feign.status();
        }
        return failure.getClass().getSimpleName();
    }
}
