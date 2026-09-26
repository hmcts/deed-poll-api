package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The mapping from a bound Dataverse element to a case column.
 *
 * <p>Asserted here as counts and names, and in {@code DeedGeneratorTest} as values that actually reach the
 * document. Both matter: this one fails when somebody changes a mapping, that one when somebody changes the
 * template.
 */
class DeedFieldTest {

    @Test
    @DisplayName("the adult deed's 18 fields and the minor's 13 are the sets the templates bind")
    void theTwoFieldSetsAreTheSetsTheTemplatesBind() {
        assertThat(DeedField.fieldsFor(DeedTemplate.ADULT)).hasSize(18);
        assertThat(DeedField.fieldsFor(DeedTemplate.MINOR)).hasSize(13);

        // 11 shared, so every field is on one deed or the other and nothing is orphaned.
        List<DeedField> shared = DeedField.fieldsFor(DeedTemplate.ADULT).stream()
            .filter(field -> field.boundBy(DeedTemplate.MINOR)).toList();
        assertThat(shared).hasSize(11);
        assertThat(DeedField.values()).hasSize(18 + 13 - 11);
    }

    @Test
    @DisplayName("the adult-only fields are the applicant's address, their email and their marital status")
    void theAdultOnlyFieldsAreTheAddressTheEmailAndTheStatus() {
        assertThat(DeedField.fieldsFor(DeedTemplate.ADULT).stream()
            .filter(field -> !field.boundBy(DeedTemplate.MINOR))
            .map(DeedField::element))
            .containsExactlyInAnyOrder("deedpoll_applicantbuildingnoname", "deedpoll_applicantaddress1",
                "deedpoll_applicantaddress2", "deedpoll_applicanttowncity", "deedpoll_applicantpostcode",
                "deedpoll_applicantemail", "deedpoll_status");
    }

    @Test
    @DisplayName("the minor-only fields are the child's age and the parents")
    void theMinorOnlyFieldsAreTheAgeAndTheParents() {
        assertThat(DeedField.fieldsFor(DeedTemplate.MINOR).stream()
            .filter(field -> !field.boundBy(DeedTemplate.ADULT))
            .map(DeedField::element))
            .containsExactlyInAnyOrder("deedpoll_ageofminor", "deedpoll_parents");
    }

    @Test
    @DisplayName("the reference number is the rendered reference, never the serial integer")
    void theReferenceNumberIsTheRenderedReference() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setReferencenumber(1);
        deedPoll.setDisplayReference("KBDP2026-00001");

        // The serial is an internal component of the reference and is not what the applicant was shown.
        assertThat(DeedField.REFERENCE_NUMBER.valueFor(deedPoll)).isEqualTo("KBDP2026-00001");
    }

    @Test
    @DisplayName("deedpoll_status is the marital status, which is the mapping the element name hides")
    void statusIsTheMaritalStatus() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setMaritalStatus(MaritalStatus.DivorcedDissolved);

        assertThat(DeedField.STATUS.element()).isEqualTo("deedpoll_status");
        assertThat(DeedField.STATUS.valueFor(deedPoll))
            .isEqualTo("Divorced / Civil Partnership Dissolved");
    }

    @Test
    @DisplayName("deedpoll_date is dateReceived, not createdOn, so a migrated case prints its own date")
    void theDateIsDateReceivedRatherThanCreatedOn() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setMigratedRecord(true);
        deedPoll.setCreatedOn(LocalDateTime.of(2026, 9, 25, 9, 0));
        deedPoll.setDateReceived(LocalDateTime.of(2019, 6, 4, 0, 0));

        // createdOn is an audit column, and for a migrated case it is the migration date. Printing it would put
        // a 2026 date on a deed whose reference number reads KBDP2019-....
        assertThat(DeedField.DATE.valueFor(deedPoll)).isEqualTo("4 June 2019");
    }

    @Test
    @DisplayName("every field resolves to a value for a case with nothing set, and none of them to null")
    void everyFieldResolvesForAnEmptyCase() {
        DeedPoll empty = new DeedPoll();

        for (DeedField field : DeedField.values()) {
            assertThat(field.valueFor(empty)).as(field.element()).isEmpty();
        }
    }

    @Test
    @DisplayName("no two fields bind the same element")
    void noTwoFieldsBindTheSameElement() {
        assertThat(List.of(DeedField.values()).stream().map(DeedField::element).distinct().count())
            .isEqualTo(DeedField.values().length);
    }

    @Test
    @DisplayName("an element no field knows is not silently ignored")
    void anUnknownElementIsNotSilentlyIgnored() {
        assertThat(DeedField.forElement("deedpoll_somethingnew")).isEmpty();
        assertThat(DeedField.forElement("deedpoll_newforename")).contains(DeedField.NEW_FORENAME);
    }
}
