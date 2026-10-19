package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The application behind a referral, as a judge sees it: its details, its witnesses and its readable supporting
 * documents, and deliberately still not a case.
 *
 * <p><strong>Wide by decision, where it used to be narrow by decision.</strong> Until 2026-10-19 this was the
 * eight-field {@code JudicialCaseView} -- the reference, the type and the six name parts -- under decision 8
 * (Steve, 2026-09-17), on the reasoning that a judge directing a referral needs only to know which application
 * it is and which name is changing to which. The business answered otherwise on 2026-10-19: a judge working a
 * referral wants to read the whole application as a caseworker does, so contact details, the representative,
 * nationality, the minor and parent details, the witnesses and the documents are now in (business decisions 1
 * and 3). The fields listed here are design decision 1 of
 * {@code docs/plans/20261019-judicial-application-view-and-revised-directions.md}, and
 * {@code JudicialApplicationViewTest} pins them in declaration order.
 *
 * <p><strong>What stays out, and why it still matters:</strong>
 *
 * <ul>
 *   <li><strong>the case id and reference number</strong> ({@code id}, {@code referencenumber}) -- decision 2:
 *       the judicial surface is keyed on the referral so that a judge cannot name an application, and publishing
 *       the id would hand back the key {@code /judicial/**} is shaped to withhold. {@code displayReference} is
 *       what a judge quotes, and no route accepts it;</li>
 *   <li><strong>payment and refund data</strong> -- {@code feeReference}, {@code refundAmount},
 *       {@code dateOfRefund}, {@code refundReference}. Business decision 1 kept these hidden: whether the fee was
 *       paid or refunded cannot bear on whether a name change may lawfully be made;</li>
 *   <li><strong>the office's processing record</strong> -- the audit columns, {@code postCounter},
 *       {@code onlineApplicationReference}, the derived day counts, the two referral read-models,
 *       {@code returnReason} and the office workflow dates. A judge directing a referral is not reviewing how the
 *       office processed it, and the business did not ask for them.</li>
 * </ul>
 *
 * <p>{@code JudicialApplicationViewTest} asserts that none of those names appears on any judicial DTO.
 *
 * <p><strong>Privacy is shown, read-only</strong> (business decision 2). It restricts no read in this service --
 * it governs only who may <em>edit</em> it -- so a private case is the same whole view, and the value tells the
 * judge the record is held privately. No judicial route can change it.
 *
 * <p><strong>Not every column the caseworker sees is in either list.</strong> The address lines
 * ({@code applicantAddress1}/{@code 2}, {@code representativeAddress1}/{@code 2}), the declaration multiselects,
 * {@code publishToLondonGazette}, {@code migratedRecord} and {@code retainPermanently} are named by neither the
 * admitted list nor the exclusions, and the plan says to ask rather than choose. They are left out until the
 * business answers, which costs a judge two address lines rather than publishing something nobody decided.
 *
 * <p><strong>A separate type from {@code CaseDetailResponse} and never a subset of it.</strong>
 * {@code NamespaceSeparationSecurityTest} asserts that in bytecode, because the failure mode is not a judge
 * reading a field on purpose -- it is somebody adding a field to the caseworker response for a caseworker
 * reason and it silently appearing here. A separate record means an addition to the judicial view is a change to
 * this file, and to the test that pins its list.
 *
 * @param displayReference the rendered case reference, or null for a seeded or imported case that has none
 * @param type the kind of application, which decides what a lawful direction can be
 * @param state the application's derived state
 * @param privacy whether the record is held privately, shown and never editable here
 * @param urgency the application's urgency
 * @param urgencyreason why the application is urgent, or null
 * @param inPerson whether the application was made in person, or null
 * @param maritalStatus the applicant's marital status, or null
 * @param oldForename the forename being changed from, or null
 * @param oldMiddleName the middle name being changed from, or null
 * @param oldSurname the surname being changed from, or null
 * @param newForename the forename being changed to, or null
 * @param newMiddleName the middle name being changed to, or null
 * @param newSurname the surname being changed to, or null
 * @param applicantBuildingNoName the applicant's building number or name, or null
 * @param applicantEmail the applicant's email address, or null
 * @param applicantTelephone the applicant's telephone number, or null
 * @param applicantTownCity the applicant's town or city, or null
 * @param applicantPostcode the applicant's postcode, or null
 * @param representativeName the representative's name, or null
 * @param representativeBuildingNoName the representative's building number or name, or null
 * @param representativeCompany the representative's firm, or null
 * @param representativeEmail the representative's email address, or null
 * @param representativeTelephone the representative's telephone number, or null
 * @param representativeTownCity the representative's town or city, or null
 * @param representativePostcode the representative's postcode, or null
 * @param ageOfMinor the minor's age in years, or null
 * @param ageOfMinorMonths the months beyond those years, or null
 * @param minorDateOfBirth the minor's date of birth, or null
 * @param parents the parents, as one free-text field, or null
 * @param nationality the applicant's nationality, or null
 * @param nationalitySection the section of the nationality legislation relied on, or null
 * @param nationalitySectionOther the section, where it is none of those listed, or null
 * @param dateOfDeclaration when the declaration was made, or null
 * @param dateOfApplication when the application was made, or null
 * @param dateReceived when the office received it, or null
 * @param awaitingFurtherDocsInfo when further documents or information were asked for, or null
 * @param referToMasterChoice the previous system's referral choice, or null
 * @param masterName the previous system's referring Master, or null
 * @param masterDirections the previous system's directions, or null
 * @param masterDecision the previous system's decision, or null
 * @param masterDateOfDecision when the previous system recorded the decision, or null
 * @param dateDecisionDirectionsReceived when the previous system's directions were received, or null
 * @param referralNotes the previous system's referral notes, or null
 * @param witnesses the application's witnesses, with no ids
 * @param documents the application's documents with a file this service can serve, oldest first
 */
public record JudicialApplicationView(
    String displayReference,
    DeedPollType type,
    DeedPollState state,
    Privacy privacy,
    Urgency urgency,
    String urgencyreason,
    Boolean inPerson,
    MaritalStatus maritalStatus,

    String oldForename,
    String oldMiddleName,
    String oldSurname,
    String newForename,
    String newMiddleName,
    String newSurname,

    String applicantBuildingNoName,
    String applicantEmail,
    String applicantTelephone,
    String applicantTownCity,
    String applicantPostcode,

    String representativeName,
    String representativeBuildingNoName,
    String representativeCompany,
    String representativeEmail,
    String representativeTelephone,
    String representativeTownCity,
    String representativePostcode,

    Integer ageOfMinor,
    Integer ageOfMinorMonths,
    LocalDateTime minorDateOfBirth,
    String parents,

    Nationality nationality,
    NationalitySection nationalitySection,
    String nationalitySectionOther,

    LocalDateTime dateOfDeclaration,
    LocalDateTime dateOfApplication,
    LocalDateTime dateReceived,
    LocalDateTime awaitingFurtherDocsInfo,

    ReferToMaster referToMasterChoice,
    String masterName,
    String masterDirections,
    MasterDecision masterDecision,
    LocalDateTime masterDateOfDecision,
    LocalDateTime dateDecisionDirectionsReceived,
    String referralNotes,

    List<JudicialWitness> witnesses,
    List<JudicialDocument> documents) {

    /**
     * The application as a judge sees it.
     *
     * @param application the case, resolved from the referral
     * @param witnesses its witnesses
     * @param documents its readable documents only, with their types initialised
     */
    public static JudicialApplicationView from(DeedPoll application,
                                               List<DeedPollWitness> witnesses,
                                               List<DeedPollSupportingDocument> documents) {
        return new JudicialApplicationView(
            application.getDisplayReference(),
            application.getType(),
            application.getState(),
            application.getPrivacy(),
            application.getUrgency(),
            application.getUrgencyreason(),
            application.getInPerson(),
            application.getMaritalStatus(),

            application.getOldForename(),
            application.getOldMiddleName(),
            application.getOldSurname(),
            application.getNewForename(),
            application.getNewMiddleName(),
            application.getNewSurname(),

            application.getApplicantBuildingNoName(),
            application.getApplicantEmail(),
            application.getApplicantTelephone(),
            application.getApplicantTownCity(),
            application.getApplicantPostcode(),

            application.getRepresentativeName(),
            application.getRepresentativeBuildingNoName(),
            application.getRepresentativeCompany(),
            application.getRepresentativeEmail(),
            application.getRepresentativeTelephone(),
            application.getRepresentativeTownCity(),
            application.getRepresentativePostcode(),

            application.getAgeOfMinor(),
            application.getAgeOfMinorMonths(),
            application.getMinorDateOfBirth(),
            application.getParents(),

            application.getNationality(),
            application.getNationalitySection(),
            application.getNationalitySectionOther(),

            application.getDateOfDeclaration(),
            application.getDateOfApplication(),
            application.getDateReceived(),
            application.getAwaitingFurtherDocsInfo(),

            application.getReferToMasterChoice(),
            application.getMasterName(),
            application.getMasterDirections(),
            application.getMasterDecision(),
            application.getMasterDateOfDecision(),
            application.getDateDecisionDirectionsReceived(),
            application.getReferralNotes(),

            JudicialWitness.from(witnesses),
            JudicialDocument.from(documents));
    }
}
