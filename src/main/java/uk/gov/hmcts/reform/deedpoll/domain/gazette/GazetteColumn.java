package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/** The supplied visible columns, in their original order. No hidden source fields are mapped. */
enum GazetteColumn {
    DISPLAY_REFERENCE("Reference Number", "displayReference", DeedPoll::getDisplayReference, true),
    DATE_OF_APPLICATION("Date of Application", "dateOfApplication", DeedPoll::getDateOfApplication, true),
    DATE_ENROLED("Date Enroled", "dateEnroled", DeedPoll::getDateEnroled, true),
    TYPE("Type", "type", DeedPoll::getType, true),
    NEW_FORENAME("New Forename", "newForename", DeedPoll::getNewForename, true),
    NEW_MIDDLE_NAME("New Middle Name", "newMiddleName", DeedPoll::getNewMiddleName, true),
    NEW_SURNAME("New Surname", "newSurname", DeedPoll::getNewSurname, true),
    OLD_FORENAME("Old Forename", "oldForename", DeedPoll::getOldForename, true),
    OLD_MIDDLE_NAME("Old Middle Name", "oldMiddleName", DeedPoll::getOldMiddleName, true),
    OLD_SURNAME("Old Surname", "oldSurname", DeedPoll::getOldSurname, true),
    MARITAL_STATUS("Marital Status", "maritalStatus", DeedPoll::getMaritalStatus, false),
    NATIONALITY("Nationality", "nationality", DeedPoll::getNationality, true),
    NATIONALITY_SECTION("Section", "nationalitySection", DeedPoll::getNationalitySection, true),
    NATIONALITY_SECTION_OTHER("Section Other", "nationalitySectionOther", DeedPoll::getNationalitySectionOther, true),
    APPLICANT_BUILDING_NO_NAME("Applicant Building No/Name", "applicantBuildingNoName",
        DeedPoll::getApplicantBuildingNoName, false),
    APPLICANT_ADDRESS1("Applicant Address 1", "applicantAddress1", DeedPoll::getApplicantAddress1, false),
    APPLICANT_ADDRESS2("Applicant Address 2", "applicantAddress2", DeedPoll::getApplicantAddress2, false),
    APPLICANT_TOWN_CITY("Applicant Town/City", "applicantTownCity", DeedPoll::getApplicantTownCity, false),
    APPLICANT_POSTCODE("Applicant Postcode", "applicantPostcode", DeedPoll::getApplicantPostcode, false),
    REPRESENTATIVE_NAME("Representative Name", "representativeName", DeedPoll::getRepresentativeName, true),
    REPRESENTATIVE_COMPANY("Representative Company", "representativeCompany", DeedPoll::getRepresentativeCompany, true),
    REPRESENTATIVE_BUILDING_NO_NAME("Representative Building No/Name", "representativeBuildingNoName",
        DeedPoll::getRepresentativeBuildingNoName, true),
    REPRESENTATIVE_ADDRESS1("Representative Address 1", "representativeAddress1",
        DeedPoll::getRepresentativeAddress1, true),
    REPRESENTATIVE_ADDRESS2("Representative Address 2", "representativeAddress2",
        DeedPoll::getRepresentativeAddress2, true),
    REPRESENTATIVE_TOWN_CITY("Representative Town/City", "representativeTownCity",
        DeedPoll::getRepresentativeTownCity, true),
    REPRESENTATIVE_POSTCODE("Representative Postcode", "representativePostcode",
        DeedPoll::getRepresentativePostcode, true),
    APPLICANT_EMAIL("Applicant Email", "applicantEmail", DeedPoll::getApplicantEmail, true);

    private final String header;
    private final String field;
    private final Function<DeedPoll, Object> reader;
    private final boolean minor;

    GazetteColumn(String header, String field, Function<DeedPoll, Object> reader, boolean minor) {
        this.header = header;
        this.field = field;
        this.reader = reader;
        this.minor = minor;
    }

    String header() {
        return header;
    }

    Object value(DeedPoll record) {
        Object value = reader.apply(record);
        if (value instanceof Enum<?> member) {
            String label = DynamicsValues.LABELS.get(field).get(member.name());
            if (label == null) {
                throw new IllegalStateException("Missing Gazette display label for " + field);
            }
            return label;
        }
        return value;
    }

    static List<GazetteColumn> columns(boolean adult) {
        return Arrays.stream(values()).filter(column -> adult || column.minor).toList();
    }
}
