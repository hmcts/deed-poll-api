package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.Computed;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * The wait for an applicant's confirmation, as the two questions asked about it: since when, and how long.
 *
 * <p>{@code REQUEST_CONFIRMATION_TO_PROCEED} asks an applicant whether they still want the deed enrolled now
 * that the judge has refused their non-publication request. The send stamps
 * {@code deedpoll_referral."confirmationRequestedAt"} and the recording writes
 * {@code "confirmationOutcome"}; between the two, the case is waiting, and nothing else about the case says so.
 *
 * <p><strong>This is the half of {@code deedpoll_deedpoll."awaitingConfirmationSince"} that has no database in
 * it</strong> -- the same split {@link CurrentReferralState} keeps, and for the same reason: container-backed
 * tests are excluded on the Jenkins agent ({@code INTEGRATION_TESTS_ENABLED=false}), so the rule that decides
 * whether a case is still in the waiting list has to be a function that runs in CI. The column is a
 * denormalised read-model, recomputed from the referral table at each of the two sites that can change the
 * answer rather than pushed from what either site thought it did; {@code V17__case_awaiting_confirmation.sql}
 * has the reasoning for the column existing at all.
 *
 * <p><strong>There is deliberately no ninth case state for "awaiting confirmation".</strong> A case's state is
 * derived from its date columns by a verbatim port whose member order is the Dynamics option-set integers, so
 * the wait is surfaced <em>alongside</em> the state and never inside it.
 */
public final class ConfirmationWait {

    private ConfirmationWait() {
    }

    /**
     * When the longest outstanding wait on a case began, given every outstanding request date on it.
     *
     * <p>The earliest rather than the latest: a caseworker looking at the list is being reminded of the case
     * that has waited longest, and the row's day count has to be that wait.
     *
     * <p><strong>A case really can hold more than one outstanding wait, so this is the rule and not a tie-break
     * for a shape the service cannot produce.</strong> Said plainly because an earlier version of this javadoc
     * claimed the opposite -- that {@code ReferralService.createReferral} refuses a second live referral, so a
     * case cannot hold two -- and that is the premise somebody would replace this {@code min} with an
     * {@code orElseThrow} on. It is false: {@code createReferral} refuses a second referral only while the first
     * is <em>non-terminal</em>, and {@code close} clears neither confirmation column. So a referral closed while
     * its {@code confirmationOutcome} is still null stays outstanding for ever, the case may then be referred and
     * refused again, and the second referral may be asked in its turn -- which is the route
     * {@code CaseEmailService.awaitingAnAnswer} points a caseworker at when they have to ask again.
     *
     * <p><strong>The consequence is that a superseded ask outlives the exchange that replaced it, and only the
     * office can retire it.</strong> Nothing here can tell an abandoned request from a live one -- V16 has two
     * outcome members and deliberately no {@code Superseded} -- so a case whose newest referral has been asked
     * and answered stays in the waiting list, on the older referral's date, until a caseworker records an answer
     * against that referral too. All four confirmation columns are published on {@code ReferralResponse}, so the
     * referrals tab is where they see which referral is holding the case there. Retiring one automatically would
     * be a decision about the process rather than a read-model repair, which is why this counts an older request
     * rather than quietly dropping it. {@code V17__case_awaiting_confirmation.sql}'s comment still carries the
     * old claim; it is a committed migration, so it is corrected here rather than edited there.
     *
     * <p>Nulls are dropped rather than trusted to sort: the caller's query excludes a referral that was never
     * asked, but a null reaching {@link Comparator#naturalOrder()} would throw at the one site whose job is to
     * keep a column honest.
     *
     * @param outstandingRequests when each still-unanswered confirmation was asked for, in any order
     * @return the earliest of them, or empty when the case is waiting for nothing
     */
    public static Optional<LocalDateTime> earliestOutstanding(Collection<LocalDateTime> outstandingRequests) {
        return outstandingRequests.stream()
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder());
    }

    /**
     * How many whole days a case has been waiting, or null when it is waiting for nothing.
     *
     * <p><strong>Calendar days, so the day the email was sent reads as 0 and not 1.</strong> That is
     * {@link Computed#daysBetween}'s arithmetic, reused rather than restated: it is the same "whole days
     * between two dates, compared in UTC" the ported day counts use, and a caseworker reading "43 days" beside
     * "0 days" needs both to mean the same kind of thing.
     *
     * <p>Not clamped at zero, faithfully to the arithmetic it borrows: a negative reading would mean a stamp in
     * the future, which is a clock or a repaired row rather than a wait, and hiding it behind a 0 would make it
     * unreportable.
     *
     * @param since when the wait began, or null for a case waiting for nothing
     * @param now the instant the count is taken at, which a caller reads once per page so that two rows cannot
     *     straddle a midnight
     */
    public static Integer daysWaiting(LocalDateTime since, LocalDateTime now) {
        return since == null ? null : Computed.daysBetween(since, now);
    }
}
