package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;

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
 * <p><strong>{@code newSurname} is the one column beyond that list, and it is a correctness requirement
 * rather than a nicety.</strong> The search term matches on surname among other names, so without it a
 * caseworker searching "Smith" gets a table of forenames that cannot show them why any row is in it.
 * {@code CASE_LIST_COLUMNS} is deliberately left alone: it is the ported Dynamics view and the conformance
 * tests read it as the specification, so the divergence belongs here, where the reason for it is.
 *
 * <p>The consequence is that a new column on the list page needs a field here. That is the intended direction:
 * a field reaches a caller by being asked for.
 *
 * <p>Values are published raw rather than formatted. {@code ListView} formats them -- "Deed of
 * Relinquishment" for the enum member, GOV.UK dates -- but formatting is the rendering service's job and it
 * has that same view model; a formatted date over the wire cannot be sorted, compared or re-rendered in Welsh.
 *
 * <p>{@link DeedPollWithComputed#computed()} is deliberately dropped: the derived day counts are a detail-page
 * and reporting concern, and the list shows neither.
 *
 * @param id the case id, which is what a row links to
 * @param displayReference the rendered reference, or null for a seeded or imported case that has none
 * @param privacy whether the case is held privately, which the list tags
 * @param dateReceived when the application was received
 * @param type the case type
 * @param state the derived state
 * @param newForename the applicant's new forename
 * @param newSurname the applicant's new surname, which the search matches on and the list therefore shows
 */
public record CaseSummaryResponse(
    String id,
    String displayReference,
    Privacy privacy,
    LocalDateTime dateReceived,
    DeedPollType type,
    DeedPollState state,
    String newForename,
    String newSurname) {

    /** One case as a list row. */
    public static CaseSummaryResponse from(DeedPoll record) {
        return new CaseSummaryResponse(
            record.getId(),
            record.getDisplayReference(),
            record.getPrivacy(),
            record.getDateReceived(),
            record.getType(),
            record.getState(),
            record.getNewForename(),
            record.getNewSurname());
    }

    /** The list, preserving the order the domain returned it in -- which is newest first. */
    public static List<CaseSummaryResponse> from(List<DeedPollWithComputed> cases) {
        return cases.stream().map(found -> from(found.deedPoll())).toList();
    }
}
