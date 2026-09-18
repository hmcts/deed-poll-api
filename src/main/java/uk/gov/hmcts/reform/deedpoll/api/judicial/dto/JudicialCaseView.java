package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

/**
 * The application behind a referral, as a judge sees it: eight fields, and deliberately not a case.
 *
 * <p><strong>This is the narrowest type in the service, and the narrowness is the requirement.</strong> Decision
 * 8, confirmed rather than provisional (Steve, 2026-09-17): a judge directing a referral needs to know which
 * application it is, what kind of application it is, and which name is being changed to which. That is this
 * record. Everything else a case carries is <em>excluded</em>, and the exclusions are the point rather than an
 * oversight:
 *
 * <ul>
 *   <li><strong>no payment or fee references</strong> -- {@code feeReference}, {@code refundReference},
 *       {@code refundAmount}, {@code dateOfRefund}: whether the fee was paid or refunded is the office's
 *       business and cannot bear on whether a name change may lawfully be made;</li>
 *   <li><strong>no internal audit trail</strong> -- {@code createdBy}, {@code modifiedBy}, the twelve workflow
 *       dates, {@code postCounter}, {@code onlineApplicationReference}. A judge directing a referral is not
 *       reviewing how the office processed it;</li>
 *   <li><strong>no privacy administration</strong> -- the {@code privacy} field and the admin-only editability
 *       around it. Whether a case is held privately is a decision about the <em>record</em>, and publishing it
 *       to the judiciary would be the disclosure the whole namespace separation exists to prevent;</li>
 *   <li><strong>no applicant contact details, no representative, no witnesses, no documents, no
 *       nationality</strong>. Where the applicant lives and how to telephone them is not an input to a
 *       direction.</li>
 * </ul>
 *
 * <p><strong>A separate type from {@code CaseDetailResponse} and never a subset of it.</strong>
 * {@code NamespaceSeparationSecurityTest} asserts that in bytecode, because the failure mode is not a judge
 * reading a field on purpose -- it is somebody adding a field to the caseworker response for a caseworker
 * reason and it silently appearing here. A separate record means an addition to the judicial view is a change to
 * this file, which is where the note above says a fresh legal and policy decision is needed.
 *
 * <p>Middle names are carried alongside the forename and surname because a deed poll is <em>about</em> the whole
 * name: a direction on a change from "John Peter Smith" to "John Peter Smyth" that showed only the first and
 * last parts would hide the only part that moved, in the very case where a Master's attention is most likely to
 * be needed. All six name components are nullable, since an application may change only part of a name.
 *
 * <p>There is no case id here, and that is decision 2 rather than an omission: the judicial surface is keyed on
 * the referral so that a judge cannot name an application, and publishing the id would hand back exactly the
 * key that the {@code /judicial/**} paths are shaped to withhold. {@code displayReference} is what a judge
 * quotes in a direction and is not an id the API accepts anywhere.
 *
 * @param displayReference the rendered case reference, or null for a seeded or imported case that has none
 * @param type the kind of application -- adult, minor, relinquishment -- which decides what a lawful direction
 *     can be
 * @param oldForename the forename being changed from, or null
 * @param oldMiddleName the middle name being changed from, or null
 * @param oldSurname the surname being changed from, or null
 * @param newForename the forename being changed to, or null
 * @param newMiddleName the middle name being changed to, or null
 * @param newSurname the surname being changed to, or null
 */
public record JudicialCaseView(
    String displayReference,
    DeedPollType type,
    String oldForename,
    String oldMiddleName,
    String oldSurname,
    String newForename,
    String newMiddleName,
    String newSurname) {

    /** The application as a judge sees it, taking the eight fields and no others. */
    public static JudicialCaseView from(DeedPoll application) {
        return new JudicialCaseView(
            application.getDisplayReference(),
            application.getType(),
            application.getOldForename(),
            application.getOldMiddleName(),
            application.getOldSurname(),
            application.getNewForename(),
            application.getNewMiddleName(),
            application.getNewSurname());
    }
}
