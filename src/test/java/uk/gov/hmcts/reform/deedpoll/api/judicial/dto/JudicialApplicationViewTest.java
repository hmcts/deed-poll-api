package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
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
 * The judicial application view's field set, pinned in both directions: exactly the fields the 2026-10-19
 * business decisions admit, and none of the ones they exclude on any judicial DTO.
 *
 * <p>Two lists written out rather than derived, because the lists are the decision: a field added to the view
 * without being added here fails, and so does a caseworker field that finds its way onto any judicial type.
 */
class JudicialApplicationViewTest {

    /** Design decision 1's list, in the record's declaration order. */
    private static final List<String> ADMITTED = List.of(
        "displayReference", "type", "state", "privacy", "urgency", "urgencyreason", "inPerson", "maritalStatus",
        "oldForename", "oldMiddleName", "oldSurname", "newForename", "newMiddleName", "newSurname",
        "applicantBuildingNoName", "applicantEmail", "applicantTelephone", "applicantTownCity",
        "applicantPostcode",
        "representativeName", "representativeBuildingNoName", "representativeCompany", "representativeEmail",
        "representativeTelephone", "representativeTownCity", "representativePostcode",
        "ageOfMinor", "ageOfMinorMonths", "minorDateOfBirth", "parents",
        "nationality", "nationalitySection", "nationalitySectionOther",
        "dateOfDeclaration", "dateOfApplication", "dateReceived", "awaitingFurtherDocsInfo",
        "referToMasterChoice", "masterName", "masterDirections", "masterDecision", "masterDateOfDecision",
        "dateDecisionDirectionsReceived", "referralNotes",
        "witnesses", "documents");

    /** Design decision 1's exclusions: the case's identity, its payment data, and the office's processing record. */
    private static final Set<String> EXCLUDED = Set.of(
        "id", "referencenumber",
        "feeReference", "refundAmount", "dateOfRefund", "refundReference",
        "createdOn", "modifiedOn", "createdBy", "modifiedBy", "postCounter", "onlineApplicationReference",
        "daysReceivedToEnroled", "daysReceivedToReturned", "currentReferralState", "awaitingConfirmationSince",
        "returnReason",
        "signatureRequestedDate", "signatureReceivedDate", "dateReadyForMaster", "dateReferred", "dateEnroled",
        "dateReturned", "dateNoticeSentToLondonGazette");

    /**
     * The three excluded names a judicial DTO may still carry, and only as a fact about something other than the
     * case: the referral's own id and the instant it was raised, a note's author and instant on the timeline, and
     * a document's id, which is only accepted under a referral the caller can see (design decision 2).
     */
    private static final Map<String, Set<String>> NOT_THE_CASES = Map.of(
        "id", Set.of("JudicialReferralDetail", "JudicialReferralSummary", "JudicialDocument"),
        "createdOn", Set.of("JudicialReferralDetail", "JudicialReferralSummary", "JudicialCaseNote"),
        "createdBy", Set.of("JudicialCaseNote"));

    @Test
    @DisplayName("the view's components are exactly design decision 1's list")
    void theComponentsAreExactlyTheAdmittedList() {
        assertThat(components(JudicialApplicationView.class)).containsExactlyElementsOf(ADMITTED);
    }

    @Test
    @DisplayName("a witness carries no id, and a document carries only what decision 2 lists")
    void theChildRecordsCarryOnlyWhatTheyList() {
        assertThat(components(JudicialWitness.class))
            .containsExactly("witnessName", "witnessAddress", "witnessOccupation", "declarant");
        assertThat(components(JudicialDocument.class))
            .containsExactly("id", "name", "documentType", "direction", "mimeType", "sizeBytes", "uploadedOn");
    }

    @Test
    @DisplayName("no judicial DTO carries an excluded name, except as the referral's, a note's or a document's own")
    void noJudicialDtoCarriesAnExcludedName() {
        List<Class<?>> dtos = judicialDtos();
        // Vacuity guard: a scan that found nothing would pass everything below.
        assertThat(dtos).extracting(Class::getSimpleName)
            .contains("JudicialApplicationView", "JudicialReferralDetail", "JudicialWitness", "JudicialDocument");

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
    @DisplayName("maps every admitted field from the case, private or not, and none of the excluded ones")
    void mapsEveryAdmittedFieldFromThePrivateCase() {
        DeedPoll application = new DeedPoll();
        application.setId("case-1");
        application.setDisplayReference("DP/2026/0001");
        application.setType(DeedPollType.values()[0]);
        application.setPrivacy(Privacy.values()[Privacy.values().length - 1]);
        application.setApplicantEmail("applicant@example.invalid");
        application.setApplicantPostcode("SW1A 1AA");
        application.setRepresentativeCompany("Solicitors LLP");
        application.setNationalitySectionOther("Other section");
        application.setMasterDirections("Earlier directions");
        application.setNewMiddleName("Peter");
        application.setFeeReference("FEE-1");
        application.setRefundAmount(BigDecimal.TEN);

        DeedPollWitness witness = new DeedPollWitness();
        witness.setId("witness-1");
        witness.setWitnessName("A Witness");
        witness.setWitnessAddress("1 Street");
        witness.setWitnessOccupation("Teacher");
        witness.setDeclarant(true);

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

        JudicialApplicationView view = JudicialApplicationView.from(application, List.of(witness), List.of(document));

        assertThat(view.displayReference()).isEqualTo("DP/2026/0001");
        assertThat(view.privacy()).isEqualTo(application.getPrivacy());
        assertThat(view.applicantEmail()).isEqualTo("applicant@example.invalid");
        assertThat(view.applicantPostcode()).isEqualTo("SW1A 1AA");
        assertThat(view.representativeCompany()).isEqualTo("Solicitors LLP");
        assertThat(view.nationalitySectionOther()).isEqualTo("Other section");
        assertThat(view.masterDirections()).isEqualTo("Earlier directions");
        assertThat(view.newMiddleName()).isEqualTo("Peter");
        assertThat(view.witnesses()).containsExactly(new JudicialWitness("A Witness", "1 Street", "Teacher", true));
        assertThat(view.documents()).containsExactly(new JudicialDocument("document-1",
            "Birth certificate, scanned", "Birth certificate", DocumentDirection.Incoming, "application/pdf", 1234,
            LocalDateTime.parse("2026-10-01T09:00:00")));
        assertThat(view.toString()).doesNotContain("case-1", "FEE-1", "witness-1");
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
