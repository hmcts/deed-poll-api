package uk.gov.hmcts.reform.deedpoll.ccd;

import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.util.Optional;
import java.util.Set;

/**
 * Whether the {@code linkDeedPoll} submission may bind a CCD case reference to a record: the whole of plan 02's
 * decision 10, with no database in it, so that Docker-free CI runs it.
 *
 * <p>{@link LinkDeedPollHandler} gathers the facts under the record's row lock and does what this answers; it
 * decides nothing itself. The checks run in a fixed order, and the order is part of the rule:
 * <ol>
 *   <li>the reference must be one this service can store;</li>
 *   <li>the record must exist;</li>
 *   <li>its live creation attempt must be {@code Claimed} or {@code AwaitingConfirmation} -- <strong>a UUID in a
 *       payload is not permission to bind a case</strong>, and only the linking operation opens and claims an
 *       attempt. It comes before the binding checks, so a record with no attempt in progress is refused the same
 *       way whether or not it is linked, and a replay against a {@code Linked} record binds nothing;</li>
 *   <li>a record already bound to this reference is a no-op, and to another is a conflict -- never an
 *       overwrite;</li>
 *   <li>a reference already bound to another record is a conflict.</li>
 * </ol>
 *
 * <p>The attempt is the record's one live attempt rather than one named in the payload. The payload carries only
 * the definition's two fields, and V22's partial index allows a record one live attempt, so "the attempt for this
 * operation" and "the record's live attempt" are the same row.
 */
public final class CcdLinkRule {

    /** The live-attempt states in which a submission for the record is expected. */
    static final Set<CcdCreationAttemptStatus> EXPECTING_A_SUBMISSION =
        Set.of(CcdCreationAttemptStatus.Claimed, CcdCreationAttemptStatus.AwaitingConfirmation);

    private CcdLinkRule() {
    }

    /**
     * What the handler knows once it holds the record's lock.
     *
     * @param reference         the reference CCD supplied, as stored, or empty if it is not sixteen digits
     * @param recordExists      whether a record has the payload's id
     * @param boundReference    the record's current reference, if it has one
     * @param liveAttempt       the status of the record's live creation attempt, if it has one
     * @param holderOfReference the id of whichever record already carries {@code reference}, if any
     */
    public record Facts(Optional<String> reference,
                        boolean recordExists,
                        Optional<String> boundReference,
                        Optional<CcdCreationAttemptStatus> liveAttempt,
                        Optional<String> holderOfReference) {
    }

    /**
     * The verdict. Every refusal's message is fixed prose: it goes back to data-store and on to whoever submitted
     * the event, so it names the rule and never a value that came from the payload or the database.
     */
    public enum Outcome {
        BIND(null),
        ALREADY_BOUND(null),
        MALFORMED_REFERENCE("The CCD case reference is not sixteen digits"),
        NO_SUCH_RECORD("No deed poll record has this id"),
        NO_ATTEMPT_IN_PROGRESS("This deed poll record has no CCD creation attempt in progress"),
        RECORD_LINKED_ELSEWHERE("This deed poll record is already linked to a different CCD case"),
        REFERENCE_TAKEN("This CCD case reference is already linked to a different deed poll record");

        private final String refusal;

        Outcome(String refusal) {
            this.refusal = refusal;
        }

        public boolean refused() {
            return refusal != null;
        }

        /** The sentence the submission is refused with, or null for an outcome that is not a refusal. */
        public String refusal() {
            return refusal;
        }
    }

    /**
     * Decides a submission for the record {@code deedPollId}.
     */
    public static Outcome decide(String deedPollId, Facts facts) {
        if (facts.reference().isEmpty()) {
            return Outcome.MALFORMED_REFERENCE;
        }
        if (!facts.recordExists()) {
            return Outcome.NO_SUCH_RECORD;
        }
        if (facts.liveAttempt().filter(EXPECTING_A_SUBMISSION::contains).isEmpty()) {
            return Outcome.NO_ATTEMPT_IN_PROGRESS;
        }
        String reference = facts.reference().get();
        if (facts.boundReference().isPresent()) {
            return facts.boundReference().get().equals(reference)
                ? Outcome.ALREADY_BOUND
                : Outcome.RECORD_LINKED_ELSEWHERE;
        }
        if (facts.holderOfReference().filter(holder -> !holder.equals(deedPollId)).isPresent()) {
            return Outcome.REFERENCE_TAKEN;
        }
        return Outcome.BIND;
    }
}
