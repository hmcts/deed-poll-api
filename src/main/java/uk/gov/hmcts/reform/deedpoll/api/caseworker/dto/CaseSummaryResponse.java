package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ConfirmationWait;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One row of the case list.
 *
 * <p><strong>Seven columns and an id, not a whole case.</strong> The list table renders the columns
 * {@code ListView.CASE_LIST_COLUMNS} names -- Reference, Privacy, Received, Type, State, New forename -- so
 * this carries those and the id the row links with. Returning the full case for every row instead would put
 * every applicant's address, telephone number and referral notes into a response that displays none of them,
 * once per case in the service; a narrow row is the honest contract and the data-minimising one, and it is not
 * a performance micro-optimisation to say so.
 *
 * <p><strong>{@code newSurname} is a column beyond that list, and it is a correctness requirement
 * rather than a nicety.</strong> The search term matches on surname among other names, so without it a
 * caseworker searching "Smith" gets a table of forenames that cannot show them why any row is in it.
 * {@code CASE_LIST_COLUMNS} is deliberately left alone: it is the ported Dynamics view and the conformance
 * tests read it as the specification, so the divergence belongs here, where the reason for it is.
 *
 * <p><strong>{@code currentReferralState} is the other, and it is here for the same kind of reason.</strong>
 * The "Returned -- action needed" queue is a filtered list -- {@code ?where=currentReferralState:is:Decided},
 * whose {@code total} is also the dashboard's count -- so a row in that list has to be able to show the value
 * that put it there. It is null for the great majority of cases, which have no live referral.
 *
 * <p>The consequence is that a new column on the list page needs a field here. That is the intended direction:
 * a field reaches a caller by being asked for.
 *
 * <p>Values are published raw rather than formatted. {@code ListView} formats them -- "Deed of
 * Relinquishment" for the enum member, GOV.UK dates -- but formatting is the rendering service's job and it
 * has that same view model; a formatted date over the wire cannot be sorted, compared or re-rendered in Welsh.
 *
 * <p><strong>{@code daysAwaitingConfirmation} is the third, and it is a number rather than a date on purpose.
 * </strong> The waiting list exists so that a case asked to confirm and never answered is impossible to forget,
 * which means the row has to say "43 days" without a caseworker doing arithmetic against today's date. It is
 * null for every case that is waiting for nothing, which is nearly all of them. The date it is computed from is
 * published on the case read rather than here -- the row answers "how long", the case answers "since when".
 *
 * <p>{@link DeedPollWithComputed#computed()} is deliberately dropped: the ported day counts are a detail-page
 * and reporting concern, and the list shows neither. {@code daysAwaitingConfirmation} is not one of them and is
 * not in {@code ComputedFields} either -- that record is a port of the reference's own derived pair, and this
 * count is this service's, derived from a column the reference schema does not have.
 *
 * @param id the case id, which is what a row links to
 * @param displayReference the rendered reference, or null for a seeded or imported case that has none
 * @param privacy whether the case is held privately, which the list tags
 * @param dateReceived when the application was received
 * @param type the case type
 * @param state the derived state
 * @param newForename the applicant's new forename
 * @param newSurname the applicant's new surname, which the search matches on and the list therefore shows
 * @param currentReferralState the state of the case's one live referral, or null when it has none -- the column
 *     the "Returned -- action needed" queue filters on, so a row in that queue can say why it is there
 * @param daysAwaitingConfirmation how many whole days the case has been waiting for the applicant's
 *     confirmation, or null when it is waiting for nothing -- 0 on the day the email was sent, never 1
 */
public record CaseSummaryResponse(
    String id,
    String displayReference,
    Privacy privacy,
    LocalDateTime dateReceived,
    DeedPollType type,
    DeedPollState state,
    String newForename,
    String newSurname,
    ReferralState currentReferralState,
    Integer daysAwaitingConfirmation) {

    /** One case as a list row, counting any wait as at this moment. */
    public static CaseSummaryResponse from(DeedPoll record) {
        return from(record, AuditClock.now());
    }

    /**
     * One case as a list row, as at a given instant.
     *
     * <p>The instant is passed in rather than read here so that a page of rows is counted from one reading of
     * the clock: two rows counted a tick apart either side of midnight would report waits a day different for
     * cases asked at the same moment.
     */
    private static CaseSummaryResponse from(DeedPoll record, LocalDateTime now) {
        return new CaseSummaryResponse(
            record.getId(),
            record.getDisplayReference(),
            record.getPrivacy(),
            record.getDateReceived(),
            record.getType(),
            record.getState(),
            record.getNewForename(),
            record.getNewSurname(),
            record.getCurrentReferralState(),
            ConfirmationWait.daysWaiting(record.getAwaitingConfirmationSince(), now));
    }

    /** The list, preserving the order the domain returned it in. */
    public static List<CaseSummaryResponse> from(List<DeedPollWithComputed> cases) {
        LocalDateTime now = AuditClock.now();
        return cases.stream().map(found -> from(found.deedPoll(), now)).toList();
    }
}
