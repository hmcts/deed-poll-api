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
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * The case as it was stored, answered by a create and by a patch.
 *
 * <p><strong>Every field {@link CaseDetailResponse} publishes except the three child collections</strong>, and
 * the absence is the point. A write returns what the write did, and it did not touch the notes, witnesses or
 * documents: the reference's create and update issue a plain Prisma {@code create} / {@code update} with no
 * {@code include}, where only {@code findDeedPoll} asks for the children. Publishing them as empty lists would
 * be a wrong answer presented as a right one -- a patch to a case with three notes claiming it has none -- and
 * publishing them truthfully would mean three more queries on the write path plus a fetch the domain
 * deliberately does not do. So they are not published at all, and a caller that needs them re-reads the case.
 *
 * <p><strong>One response type for the two writes, not one each.</strong> The plan asks for separate request
 * and response types per operation, and the reason is that a shared type drifts towards being whatever every
 * caller needed; that reason does not reach here, because a create and a patch return the same thing -- the
 * stored case -- and the reference returns byte-for-byte the same shape from both. Two identical
 * seventy-seven-component records would be a duplication with no reader and two places to update a column in.
 * The request types are separate, because those really do differ in what they mean by an absent field.
 *
 * <p>Enum names, offset-free date-times and the derived day counts all follow
 * {@link CaseDetailResponse}, which records the reasoning for each; a write and a read must not describe the
 * same case in two different wire formats.
 *
 * <p>Written out field by field for the reason {@link DeedPollFields} is: a field is published by being listed.
 * {@code SavedCaseResponseFieldsTest} covers the hazard that buys -- a canonical constructor of seventy-seven
 * positional arguments, thirty-five of them {@code String} -- and also pins this field set against
 * {@code CaseDetailResponse}'s, so the two cannot silently diverge by more than the children.
 */
public record SavedCaseResponse(
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

    // --- Current referral state, denormalised ---
    // Published on the write response as well as the read one, exactly as state is, and for a reason
    // beyond symmetry: deed-poll-admin's in-memory mock derives its queryable field set from THIS
    // schema's keys, so a `where=currentReferralState:is:Decided` call -- the dashboard's own count --
    // 400s there unless the property is here. On the real api queryability comes from DeedPollFields
    // and is independent of any DTO; the mock is what admin develops against.
    ReferralState currentReferralState,

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
    Integer daysReceivedToReturned) {

    /**
     * The stored case and its derived counts.
     *
     * <p>Safe to call on a just-written entity outside the transaction, which is the other half of why the
     * children are absent: they are a lazy collection on a managed entity that no write initialises, so
     * reading one here would fail with the session closed rather than answer.
     */
    public static SavedCaseResponse from(DeedPollWithComputed saved) {
        DeedPoll record = saved.deedPoll();
        ComputedFields computed = saved.computed();

        return new SavedCaseResponse(
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

            record.getCurrentReferralState(),

            record.getReturnReason(),
            record.getRefundAmount(),
            record.getDateOfRefund(),
            record.getRefundReference(),

            record.getCreatedOn(),
            record.getModifiedOn(),
            record.getCreatedBy(),
            record.getModifiedBy(),

            computed.daysReceivedToEnroled(),
            computed.daysReceivedToReturned());
    }

    /**
     * A multiselect column as a JSON array.
     *
     * <p>Empty rather than a JSON null for the reason {@code CaseDetailResponse.integers} gives: the column is
     * nullable only because a required Prisma list produces a nullable column, and "nothing selected" is the
     * same answer either way.
     */
    private static List<Integer> integers(Integer[] values) {
        return values == null ? List.of() : Arrays.asList(values);
    }
}
