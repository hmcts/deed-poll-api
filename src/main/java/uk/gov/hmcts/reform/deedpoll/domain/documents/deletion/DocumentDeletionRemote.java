package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The remote half of a permanent document deletion: one call per step, outside any transaction, each answering a
 * {@link StepOutcome} and never throwing for what the far side said (plan 20261125-permanent-document-deletion,
 * Task 4; receipts in docs/DOCUMENT-DELETION.md, "Remote steps and their receipts").
 *
 * <p>Implemented in {@code storage/PermanentDocumentRemoval}. A seam so that {@link DocumentDeletionWork}'s claims,
 * leases, budget and completion are tested against real Postgres with the remote answers scripted.
 */
public interface DocumentDeletionRemote {

    /**
     * Takes the document off the linked CCD case's current collection: the document event again, with the case's
     * other {@code Attached} documents read fresh just before it is sent, never a list read earlier.
     *
     * @param timeLeft how long is left before a call must have started: it bounds the wait for each token grant, and
     *                 is asked again before the event's start and between the start and its submission; negative
     *                 sends nothing more and answers {@link Verdict#NOT_SENT}
     */
    StepOutcome removeCcdReference(String caseId, String documentId, String caseReference,
                                   Supplier<Duration> timeLeft);

    /**
     * CDAM's {@code DELETE ?permanent=true}, never preceded by a soft delete. Only {@code 204} is a receipt.
     *
     * @param timeLeft how long is left before the delete must have been sent: it bounds the wait for each token grant,
     *                 and is asked again once both are in hand; negative sends nothing and answers
     *                 {@link Verdict#NOT_SENT}
     */
    StepOutcome deleteCdamPermanently(UUID cdamDocumentId, Supplier<Duration> timeLeft);

    /** The filesystem store's removal, verified: deleted, or the root present and the file not. */
    StepOutcome removeLocalFile(String storageKey);

    /** What one call established. */
    enum Verdict {
        /** The step's one receipt: it is confirmed. */
        DONE,
        /** Nothing was sent, by this service's own choice (the budget); not a try. */
        NOT_SENT,
        /** Unknown or transient: another try may answer differently, and re-sending is safe. */
        RETRY,
        /** Refused in a way another try will not fix; the failure says what a person must look at. */
        FAILED
    }

    /**
     * One step's answer.
     *
     * @param code    an allowlisted upper-case code for the step's {@code outcome} column; never a provider message
     * @param failure set only for {@link Verdict#FAILED}
     */
    record StepOutcome(Verdict verdict, String code, DocumentDeletionFailure failure) {

        public StepOutcome {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(code, "code");
            if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) {
                throw new IllegalArgumentException("A step outcome code is an allowlisted upper-case word");
            }
            if ((verdict == Verdict.FAILED) != (failure != null)) {
                throw new IllegalArgumentException("A failure is named exactly when the step failed");
            }
        }

        public static StepOutcome done(String code) {
            return new StepOutcome(Verdict.DONE, code, null);
        }

        public static StepOutcome notSent(String code) {
            return new StepOutcome(Verdict.NOT_SENT, code, null);
        }

        public static StepOutcome retry(String code) {
            return new StepOutcome(Verdict.RETRY, code, null);
        }

        public static StepOutcome failed(String code, DocumentDeletionFailure failure) {
            return new StepOutcome(Verdict.FAILED, code, failure);
        }
    }
}
