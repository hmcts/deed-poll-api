package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Master's application view, pinned in both directions: exactly the fields Master Sullivan's feedback admits
 * (Steve, 2026-10-27), in the order the Masters asked for them, and none of the excluded ones on any judicial DTO.
 *
 * <p>Two lists written out rather than derived, because the lists are the decision: a field added to the view
 * without being added here fails, and so does any excluded field that finds its way onto a judicial type.
 */
class JudicialApplicationViewTest {

    /** What a Master sees, in the record's declaration order. */
    private static final List<String> ADMITTED = List.of(
        "displayReference",
        "newForename", "newMiddleName", "newSurname", "oldForename", "oldMiddleName", "oldSurname",
        "type",
        "nationality", "nationalitySection", "nationalitySectionOther",
        "parents",
        "documents");

    /**
     * Never on a judicial DTO: the case's identity, its payment data and the office's processing record (since
     * 2026-09-17), and everything the 2026-10-27 narrowing took back out (the Masters asked not to see it).
     */
    private static final Set<String> EXCLUDED = Set.of(
        // The case's identity, payment data and processing record.
        "id", "referencenumber",
        "feeReference", "refundAmount", "dateOfRefund", "refundReference",
        "createdOn", "modifiedOn", "createdBy", "modifiedBy", "postCounter", "onlineApplicationReference",
        "daysReceivedToEnroled", "daysReceivedToReturned", "currentReferralState", "awaitingConfirmationSince",
        "returnReason",
        "signatureRequestedDate", "signatureReceivedDate", "dateReadyForMaster", "dateReferred", "dateEnroled",
        "dateReturned", "dateNoticeSentToLondonGazette",
        // Taken back out on 2026-10-27.
        "state", "privacy", "urgency", "urgencyreason", "inPerson", "maritalStatus",
        "applicantBuildingNoName", "applicantEmail", "applicantTelephone", "applicantTownCity", "applicantPostcode",
        "representativeName", "representativeBuildingNoName", "representativeCompany", "representativeEmail",
        "representativeTelephone", "representativeTownCity", "representativePostcode",
        "ageOfMinor", "ageOfMinorMonths", "minorDateOfBirth",
        "dateOfDeclaration", "dateOfApplication", "dateReceived", "awaitingFurtherDocsInfo",
        "referToMasterChoice", "masterName", "masterDirections", "masterDecision", "masterDateOfDecision",
        "dateDecisionDirectionsReceived", "referralNotes",
        "witnesses", "caseNotes");

    /**
     * Excluded names a judicial DTO may still carry, and only as a fact about something other than the case: the
     * referral's own id, state, urgency and the instant it was raised, and a document's id, which is only accepted
     * under a referral the caller can see.
     */
    private static final Map<String, Set<String>> NOT_THE_CASES = Map.of(
        "id", Set.of("JudicialReferralDetail", "JudicialReferralSummary", "JudicialDocument"),
        "state", Set.of("JudicialReferralDetail", "JudicialReferralSummary"),
        "urgency", Set.of("JudicialReferralDetail", "JudicialReferralSummary"),
        "createdOn", Set.of("JudicialReferralDetail", "JudicialReferralSummary"));

    @Test
    @DisplayName("the view's components are exactly what the Masters asked for, in their order")
    void theComponentsAreExactlyTheAdmittedList() {
        assertThat(components(JudicialApplicationView.class)).containsExactlyElementsOf(ADMITTED);
    }

    @Test
    @DisplayName("a document carries only what the Master's list needs")
    void theDocumentCarriesOnlyWhatItLists() {
        assertThat(components(JudicialDocument.class))
            .containsExactly("id", "name", "documentType", "direction", "mimeType", "sizeBytes", "uploadedOn");
    }

    @Test
    @DisplayName("the referral detail carries no case notes")
    void theReferralDetailCarriesNoCaseNotes() {
        assertThat(components(JudicialReferralDetail.class)).doesNotContain("caseNotes");
    }

    @Test
    @DisplayName("no judicial DTO carries an excluded name, except as the referral's or a document's own")
    void noJudicialDtoCarriesAnExcludedName() {
        List<Class<?>> dtos = judicialDtos();
        // Vacuity guard: a scan that found nothing would pass everything below.
        assertThat(dtos).extracting(Class::getSimpleName)
            .contains("JudicialApplicationView", "JudicialReferralDetail", "JudicialReferralSummary",
                      "JudicialDocument");

        List<String> offending = new ArrayList<>();
        for (Class<?> dto : dtos) {
            for (String name : components(dto)) {
                boolean permitted = NOT_THE_CASES.getOrDefault(name, Set.of()).contains(dto.getSimpleName());
                if (EXCLUDED.contains(name) && !permitted) {
                    offending.add(dto.getSimpleName() + "." + name);
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    @DisplayName("maps every admitted field from a minor's private case, and none of the excluded ones")
    void mapsEveryAdmittedField() {
        DeedPollSupportingDocumentType type = new DeedPollSupportingDocumentType();
        type.setName("Birth certificate");
        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setId("document-1");
        document.setName("Birth certificate, scanned");
        document.setDocumentType(type);
        document.setDirection(DocumentDirection.Incoming);
        document.setMimeType("application/pdf");
        document.setSizeBytes(1234);
        document.setCreatedOn(LocalDateTime.parse("2026-10-01T09:00:00"));

        DeedPoll application = minorsCase();
        JudicialApplicationView view = JudicialApplicationView.from(application, List.of(document));

        assertThat(view).isEqualTo(new JudicialApplicationView(
            "DP/2026/0001", "Sam", "Peter", "Smith", "Samuel", "Paul", "Jones", DeedPollType.Minor,
            Nationality.values()[0], NationalitySection.values()[0], "Other section", "Ann Jones and Bob Jones",
            List.of(new JudicialDocument("document-1", "Birth certificate, scanned", "Birth certificate",
                DocumentDirection.Incoming, "application/pdf", 1234, LocalDateTime.parse("2026-10-01T09:00:00")))));
        assertThat(view.toString())
            .doesNotContain("case-1", "FEE-1", "applicant@example.invalid", "SW1A 1AA", "Solicitors LLP",
                            "Earlier directions");
    }

    @ParameterizedTest
    @EnumSource(value = DeedPollType.class, names = {"Minor", "MinorTransgender"}, mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("withholds the parents from every application that is not a minor's, even if a row carries them")
    void withholdsTheParentsUnlessMinor(DeedPollType type) {
        DeedPoll application = minorsCase();
        application.setType(type);

        assertThat(JudicialApplicationView.from(application, List.of()).parents()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = DeedPollType.class, names = {"Minor", "MinorTransgender"})
    @DisplayName("shows the parents on a minor's application")
    void showsTheParentsForAMinor(DeedPollType type) {
        DeedPoll application = minorsCase();
        application.setType(type);

        assertThat(JudicialApplicationView.from(application, List.of()).parents()).isEqualTo("Ann Jones and Bob Jones");
    }

    /** A minor's private case carrying every kind of field, admitted and excluded alike. */
    private static DeedPoll minorsCase() {
        DeedPoll application = new DeedPoll();
        application.setId("case-1");
        application.setDisplayReference("DP/2026/0001");
        application.setType(DeedPollType.Minor);
        application.setPrivacy(Privacy.values()[Privacy.values().length - 1]);
        application.setNewForename("Sam");
        application.setNewMiddleName("Peter");
        application.setNewSurname("Smith");
        application.setOldForename("Samuel");
        application.setOldMiddleName("Paul");
        application.setOldSurname("Jones");
        application.setNationality(Nationality.values()[0]);
        application.setNationalitySection(NationalitySection.values()[0]);
        application.setNationalitySectionOther("Other section");
        application.setParents("Ann Jones and Bob Jones");
        application.setApplicantEmail("applicant@example.invalid");
        application.setApplicantPostcode("SW1A 1AA");
        application.setRepresentativeCompany("Solicitors LLP");
        application.setMasterDirections("Earlier directions");
        application.setFeeReference("FEE-1");
        application.setRefundAmount(BigDecimal.TEN);
        return application;
    }

    private static List<String> components(Class<?> type) {
        assertThat(type.isRecord()).as("%s is a record", type.getSimpleName()).isTrue();
        return Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).toList();
    }

    private static List<Class<?>> judicialDtos() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Record.class));
        List<Class<?>> found = new ArrayList<>();
        String dtoPackage = JudicialApplicationView.class.getPackageName();
        for (BeanDefinition candidate : scanner.findCandidateComponents(dtoPackage)) {
            try {
                found.add(Class.forName(candidate.getBeanClassName()));
            } catch (ClassNotFoundException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        return found;
    }
}
