package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ComputedFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * One case in full, with its notes, witnesses and documents.
 *
 * <p>This is the response for {@code GET /caseworker/cases/&#123;id&#125;}, and it is the whole case because
 * that is what the operation is for: the detail page derives its tabs, sections and rows from the same field
 * metadata the edit forms use, reading the record by field name, so a page rendering every tab needs every
 * field. Splitting it into four calls would also break the one query the reference deliberately does -- see
 * {@code DeedPollService.findDeedPoll}.
 *
 * <p><strong>The field set is {@link DeedPollFields}' readable names, exactly.</strong> Not the entity's
 * columns: {@code deedpoll_deedpoll} also carries {@code placeOfBirth} and the parent block, which belong to
 * the citizen application journey and are not on any caseworker form. The reference's own case schema does not
 * accept them either. A DTO that published them because they happened to be in the migration is precisely
 * what {@code package-info} means by never returning an entity, so
 * {@code CaseDetailResponseFieldsTest} asserts the two sets agree rather than leaving it to review.
 *
 * <p><strong>Enums are published as their member names</strong> ({@code "DeedOfRelinquishment"}), not as the
 * Dynamics option-set integers, because the member name is what the Postgres enum column stores and what
 * every ported rule matches on. The integers remain available through the enum's declaration order for a
 * consumer that needs to talk to Dynamics; nothing in this estate does.
 *
 * <p><strong>Dates are ISO-8601 local date-times with no offset</strong>, because the columns are
 * {@code timestamp(3)} without a time zone and inventing one on the way out would be a claim the data does not
 * make.
 *
 * <p>The mapping is written out one field at a time rather than derived by reflection from
 * {@link DeedPollFields}, for the reason that registry itself is written out: a field is published by being
 * listed. The hazard that buys -- a canonical constructor with seventy-nine positional arguments, where two
 * transposed {@code String}s compile silently -- is covered by a test that populates every field with a
 * distinct value and compares the response against the record field by field.
 */
public record CaseDetailResponse(
    // --- Identity, allocated rather than submitted ---
    String id,
    Integer referencenumber,
    String displayReference,

    // --- General / classification ---
    DeedPollType type,
    DeedPollState state,
    Privacy privacy,
    Urgency urgency,
    String urgencyreason,
    PostCounter postCounter,
    Boolean inPerson,
    boolean migratedRecord,
    boolean retainPermanently,
    MaritalStatus maritalStatus,
    String feeReference,
    String onlineApplicationReference,

    // --- Applicant name ---
    String newForename,
    String newMiddleName,
    String newSurname,
    String oldForename,
    String oldMiddleName,
    String oldSurname,

    // --- Applicant address and contact ---
    String applicantBuildingNoName,
    String applicantEmail,
    String applicantTelephone,
    String applicantAddress1,
    String applicantAddress2,
    String applicantTownCity,
    String applicantPostcode,

    // --- Representative address and contact ---
    String representativeName,
    String representativeBuildingNoName,
    String representativeCompany,
    String representativeAddress1,
    String representativeEmail,
    String representativeAddress2,
    String representativeTelephone,
    String representativeTownCity,
    String representativePostcode,

    // --- Minor ---
    Integer ageOfMinor,
    Integer ageOfMinorMonths,
    LocalDateTime minorDateOfBirth,
    String parents,

    // --- Nationality ---
    Nationality nationality,
    NationalitySection nationalitySection,
    String nationalitySectionOther,

    // --- Declarations ---
    List<Integer> iShall,
    List<Integer> iHaveSubscribed,
    List<Integer> andAlsoMyOld,
    LocalDateTime dateOfDeclaration,

    // --- Dates driving state ---
    LocalDateTime dateOfApplication,
    LocalDateTime dateReceived,
    LocalDateTime awaitingFurtherDocsInfo,
    LocalDateTime signatureRequestedDate,
    LocalDateTime signatureReceivedDate,
    LocalDateTime dateReadyForMaster,
    LocalDateTime dateReferred,
    LocalDateTime dateEnroled,
    LocalDateTime dateReturned,
    LocalDateTime dateNoticeSentToLondonGazette,

    // --- Master / referral ---
    ReferToMaster referToMasterChoice,
    String masterName,
    String masterDirections,
    MasterDecision masterDecision,
    LocalDateTime masterDateOfDecision,
    LocalDateTime dateDecisionDirectionsReceived,
    String referralNotes,
    boolean publishToLondonGazette,

    // --- Return / refund ---
    String returnReason,
    BigDecimal refundAmount,
    LocalDateTime dateOfRefund,
    String refundReference,

    // --- Audit ---
    LocalDateTime createdOn,
    LocalDateTime modifiedOn,
    String createdBy,
    String modifiedBy,

    // --- Derived, not stored ---
    Integer daysReceivedToEnroled,
    Integer daysReceivedToReturned,

    // --- Children, from the same read ---
    List<NoteResponse> notes,
    List<WitnessResponse> witnesses,
    List<DocumentResponse> documents) {

    /**
     * One case, its derived counts and its three child collections.
     *
     * <p>The collections must already be initialised: the domain fetches them inside its own transaction,
     * because {@code open-in-view} is off and there is no session left by the time a response is being built.
     */
    public static CaseDetailResponse from(DeedPollWithComputed found) {
        DeedPoll record = found.deedPoll();
        ComputedFields computed = found.computed();

        return new CaseDetailResponse(
            record.getId(),
            record.getReferencenumber(),
            record.getDisplayReference(),

            record.getType(),
            record.getState(),
            record.getPrivacy(),
            record.getUrgency(),
            record.getUrgencyreason(),
            record.getPostCounter(),
            record.getInPerson(),
            record.isMigratedRecord(),
            record.isRetainPermanently(),
            record.getMaritalStatus(),
            record.getFeeReference(),
            record.getOnlineApplicationReference(),

            record.getNewForename(),
            record.getNewMiddleName(),
            record.getNewSurname(),
            record.getOldForename(),
            record.getOldMiddleName(),
            record.getOldSurname(),

            record.getApplicantBuildingNoName(),
            record.getApplicantEmail(),
            record.getApplicantTelephone(),
            record.getApplicantAddress1(),
            record.getApplicantAddress2(),
            record.getApplicantTownCity(),
            record.getApplicantPostcode(),

            record.getRepresentativeName(),
            record.getRepresentativeBuildingNoName(),
            record.getRepresentativeCompany(),
            record.getRepresentativeAddress1(),
            record.getRepresentativeEmail(),
            record.getRepresentativeAddress2(),
            record.getRepresentativeTelephone(),
            record.getRepresentativeTownCity(),
            record.getRepresentativePostcode(),

            record.getAgeOfMinor(),
            record.getAgeOfMinorMonths(),
            record.getMinorDateOfBirth(),
            record.getParents(),

            record.getNationality(),
            record.getNationalitySection(),
            record.getNationalitySectionOther(),

            integers(record.getIShall()),
            integers(record.getIHaveSubscribed()),
            integers(record.getAndAlsoMyOld()),
            record.getDateOfDeclaration(),

            record.getDateOfApplication(),
            record.getDateReceived(),
            record.getAwaitingFurtherDocsInfo(),
            record.getSignatureRequestedDate(),
            record.getSignatureReceivedDate(),
            record.getDateReadyForMaster(),
            record.getDateReferred(),
            record.getDateEnroled(),
            record.getDateReturned(),
            record.getDateNoticeSentToLondonGazette(),

            record.getReferToMasterChoice(),
            record.getMasterName(),
            record.getMasterDirections(),
            record.getMasterDecision(),
            record.getMasterDateOfDecision(),
            record.getDateDecisionDirectionsReceived(),
            record.getReferralNotes(),
            record.isPublishToLondonGazette(),

            record.getReturnReason(),
            record.getRefundAmount(),
            record.getDateOfRefund(),
            record.getRefundReference(),

            record.getCreatedOn(),
            record.getModifiedOn(),
            record.getCreatedBy(),
            record.getModifiedBy(),

            computed.daysReceivedToEnroled(),
            computed.daysReceivedToReturned(),

            NoteResponse.from(record.getNotes()),
            WitnessResponse.from(record.getWitnesses()),
            DocumentResponse.from(record.getDocuments()));
    }

    /**
     * A multiselect column as a JSON array.
     *
     * <p>Null becomes an empty list rather than a JSON null, for the reason
     * {@code DocumentTypeResponse.integers} gives: the column is nullable only because a required Prisma list
     * produces a nullable column, and "nothing selected" is the same answer either way.
     */
    private static List<Integer> integers(Integer[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }
}
