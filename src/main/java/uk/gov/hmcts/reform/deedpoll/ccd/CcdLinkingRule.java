package uk.gov.hmcts.reform.deedpoll.ccd;

import feign.FeignException;
import feign.RetryableException;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * What the linking operation does with each answer CCD gives it: the whole of plan 02's "recovery is by reading,
 * not retrying", with no database and no network in it, so that Docker-free CI runs it.
 *
 * <p><strong>The local binding is the truth about whether a case exists.</strong> The case is decentralised: the
 * {@code ccd.case_data} row data-store asks for is written by this service, inside the same transaction as
 * {@link LinkDeedPollHandler}'s bind. So a record with no binding has no case, whatever CCD said, and a record
 * with one has exactly that case. CCD is read only to confirm what the binding says, never to find a case.
 *
 * <p>{@link CcdLinkingService} gathers the facts and does what this answers.
 */
public final class CcdLinkingRule {

    private CcdLinkingRule() {
    }

    /**
     * What a failed {@code submitCaseCreation} says about whether anything was created.
     */
    public enum SubmitFailure {
        /**
         * CCD answered, and the answer was a refusal: data-store validated nothing it would create, or rolled back
         * what it began -- a stale event token is its {@code 403}, a refusal from the handler its {@code 422}.
         */
        REFUSED,
        /**
         * Nobody knows. A lost response, a timeout, a {@code 5xx} -- which data-store gives when this service's
         * persistence API failed, keeping its own pointer -- or a {@code 404} after submit.
         */
        UNKNOWN
    }

    /**
     * Classifies what {@code submitCaseCreation} threw.
     *
     * <p>Only a status CCD actually sent can be a refusal, and only a {@code 4xx} that is not {@code 404} (the case
     * type or event not found, which after a successful start is a routing fault, not an answer) or {@code 408}
     * (a timeout by another name). Anything else -- no status, a {@code 5xx}, a response that would not decode --
     * may have followed a commit.
     */
    public static SubmitFailure classify(RuntimeException failure) {
        if (failure instanceof RetryableException || !(failure instanceof FeignException feign)) {
            return SubmitFailure.UNKNOWN;
        }
        int status = feign.status();
        boolean refusal = status >= 400 && status < 500 && status != 404 && status != 408;
        return refusal ? SubmitFailure.REFUSED : SubmitFailure.UNKNOWN;
    }

    /**
     * A case as CCD showed it: in the answer to a submission, or read back by reference.
     *
     * @param reference  the case's reference, as sixteen digits, or empty if CCD gave none this service could store
     * @param caseTypeId the case type it says the case has
     * @param deedPollId the record id in its data
     */
    public record CaseSeen(Optional<String> reference, String caseTypeId, String deedPollId) {
    }

    /** Whether CCD's case, the configured case type and the record's local binding all agree. */
    public enum Confirmation {
        /** Reference, case type and record id agree on both sides. */
        CONFIRMED,
        /** The record holds no reference, so whatever CCD showed is not a case of ours. */
        NOT_BOUND,
        /** Both sides have something, and they disagree. */
        CONTRADICTED
    }

    /**
     * Confirms a case against the local binding (plan 02, Task 7: "the returned reference, case type and UUID
     * match the local binding").
     */
    public static Confirmation confirm(String deedPollId, String caseTypeId, Optional<String> binding, CaseSeen seen) {
        if (binding.isEmpty()) {
            return Confirmation.NOT_BOUND;
        }
        boolean agrees = seen.reference().equals(binding)
            && Objects.equals(seen.caseTypeId(), caseTypeId)
            && Objects.equals(seen.deedPollId(), deedPollId);
        return agrees ? Confirmation.CONFIRMED : Confirmation.CONTRADICTED;
    }

    /**
     * What reconciliation does with an attempt {@code AwaitingConfirmation}. Never a release to {@code Pending}:
     * after an unknown outcome a fresh start is how a record gets a second case.
     */
    public enum Next {
        /**
         * Mark it {@code Linked}.
         */
        LINK,
        /**
         * Leave it {@code AwaitingConfirmation} for a later reconciliation to read again.
         */
        WAIT,
        /**
         * Mark it {@code NeedsRepair}, for a person.
         */
        REPAIR
    }

    /**
     * After a refusal, or a failure before anything was submitted, with the binding read and absent: nothing was
     * created, so the claim may be given back as {@code Pending} and tried again from the start -- until it has
     * used its attempts, when it is a repair item.
     *
     * @param attempts how many claims the attempt has had, this one included
     */
    public static boolean mayTryAgain(int attempts, int maxAttempts) {
        return attempts < maxAttempts;
    }

    /**
     * When an attempt released after a refusal is next due (plan 02b, decision 6): {@code retryAfter} for each claim
     * it has had, so a CCD that keeps refusing is asked less and less often, and never twice in one sweep.
     *
     * @param attempts how many claims the attempt has had, this one included
     */
    public static LocalDateTime nextDue(LocalDateTime now, Duration retryAfter, int attempts) {
        return now.plus(retryAfter.multipliedBy(Math.max(1, attempts)));
    }

    /**
     * Whether a {@code Pending} attempt may be claimed now. {@code CcdCreationAttemptRepository.claim} repeats this
     * predicate in SQL, so that the service's answer and the statement's cannot disagree.
     *
     * @param notBefore the attempt's {@code notBefore}; null means due now
     */
    public static boolean isDue(LocalDateTime notBefore, LocalDateTime now) {
        return notBefore == null || !notBefore.isAfter(now);
    }

    /**
     * Whether an admin may send an attempt back to {@code Pending} to be tried again from scratch (plan 02b,
     * decision 8): only from {@code NeedsRepair}, and only while nothing may have been submitted.
     *
     * <p>The condition {@code CcdCreationAttemptStatus} cannot state, because it is a fact about the row rather than
     * the state. A retry from scratch starts a fresh event, whose fresh start-event token mints a fresh idempotency
     * key in data-store, so once {@code submitCaseCreation} may have been called it could create a second case for
     * one record. {@code submissionMayHaveReached} is committed before that call and never reset, and every row that
     * predates it was backfilled true, so a true here refuses whatever else is known.
     *
     * @param status                   the attempt's status as last read
     * @param submissionMayHaveReached whether {@code submitCaseCreation} may have been called for it
     */
    public static boolean mayRetryFromScratch(CcdCreationAttemptStatus status, boolean submissionMayHaveReached) {
        return status == CcdCreationAttemptStatus.NeedsRepair && !submissionMayHaveReached;
    }

    /**
     * Reconciling an attempt {@code AwaitingConfirmation}, once the binding has been read and, if there is one, CCD
     * asked about it.
     *
     * <p>A contradiction is repair at once, because waiting cannot change it. Anything else unconfirmed waits for
     * the settle window, then becomes repair: never a fresh start, which after an unknown outcome is exactly how a
     * record would get a second case.
     *
     * @param confirmation what the binding and CCD said, or empty if CCD could not be read
     * @param settled      whether the attempt has waited past the settle window
     */
    public static Next reconcile(Optional<Confirmation> confirmation, boolean settled) {
        if (confirmation.filter(Confirmation.CONFIRMED::equals).isPresent()) {
            return Next.LINK;
        }
        if (confirmation.filter(Confirmation.CONTRADICTED::equals).isPresent()) {
            return Next.REPAIR;
        }
        return settled ? Next.REPAIR : Next.WAIT;
    }
}
