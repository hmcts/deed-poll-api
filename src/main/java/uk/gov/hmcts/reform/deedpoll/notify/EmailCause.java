package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.Referral;

import java.util.Optional;
import java.util.UUID;

/**
 * What entitles a case to one email: a referral somebody decided, or the case's own state.
 *
 * <p><strong>Why the referral is optional rather than always present.</strong> The two referral-outcome emails
 * are sent because a Master refused something, and the referral is the thing they speak for -- it names the
 * direction one of them quotes, the decision date the other prints, and the row the unique index keys mutual
 * exclusion on. The chase is sent because the case is waiting for documents, and there is no referral in that
 * sentence at all.
 *
 * <p><strong>The alternative was to manufacture one, and every spelling of that is worse.</strong> Selecting an
 * unrelated historical referral would key a chase's outbox row to a refusal it has nothing to do with, and would
 * make the two mutually exclusive through a group they do not share a meaning in; a zero UUID would violate the
 * foreign key V11 added; a nullable field on {@code Composed} would put the "is there one?" question at every
 * call site instead of at the one that answers it. So the cause says which kind it is, and
 * {@link #referralId()} answering null is the outbox column's own null rather than a stand-in for anything.
 *
 * <p>Package-private, and deliberately not part of any response: this is how the service reaches the values a
 * template resolves from, not something a caseworker is shown. {@code CaseEmailService} is the only caller.
 *
 * @param referral the refusal this email speaks for, or empty when the case itself is the cause
 */
record EmailCause(Optional<Referral> referral) {

    /** The referral a referral-driven template was found applicable by. */
    static EmailCause of(Referral referral) {
        return new EmailCause(Optional.of(referral));
    }

    /** The case's own state, for a template that is not about a referral at all. */
    static EmailCause ofCase() {
        return new EmailCause(Optional.empty());
    }

    /**
     * The referral id an outbox row is written with, or null for a case-driven email.
     *
     * <p>Null and not empty, because the column is nullable and this is the value bound to it. A case-driven
     * row is outside the partial unique index for exactly that reason -- see
     * {@link EmailExclusionGroup#DOCUMENT_CHASE}.
     */
    UUID referralId() {
        return referral.map(Referral::getId).orElse(null);
    }

    /**
     * The direction {@link EmailSlot#JUDGES_REASON} is pre-populated from, or null when there is no referral
     * and so nothing to pre-populate from.
     *
     * <p>Null rather than empty for the reason {@link EmailSlots#parse} takes it that way: a referral with no
     * direction recorded and no referral at all leave the slot in the same position, which is the caseworker's.
     */
    String directionsText() {
        return referral.map(Referral::getDirectionsText).orElse(null);
    }

    /**
     * The referral, for a template whose own rule is that it has one.
     *
     * <p>Unreachable for a case-driven template: the only caller is the arm of a switch over
     * {@code EmailTemplate} whose cause was built by {@link #of}, so a cause with no referral cannot reach it
     * unless somebody changes one of the two without the other. That is what this says rather than a
     * {@code NullPointerException} two frames further in.
     */
    Referral requireReferral() {
        return referral.orElseThrow(() -> new IllegalStateException(
            "This email is composed from a referral, but its cause is the case's own state"));
    }
}
