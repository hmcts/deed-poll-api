package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The four conversions a bound field makes from a column to printed text.
 *
 * <p>The date pattern is pinned rather than described, because it is the one thing here that is also stated in
 * {@code ResolvedValues} for the emails: the deed and the email that carries it must not render the same date
 * two ways.
 */
class DeedValuesTest {

    @Test
    @DisplayName("a date is the same d MMMM yyyy an applicant reads everywhere else in this service")
    void theDateIsTheFormatAnApplicantReadsElsewhere() {
        assertThat(DeedValues.date(LocalDateTime.of(2026, 3, 9, 11, 30))).isEqualTo("9 March 2026");
        assertThat(DeedValues.date(LocalDateTime.of(2026, 12, 31, 23, 59))).isEqualTo("31 December 2026");
    }

    @Test
    @DisplayName("an unset date prints nothing, so the applicant can complete the line in wet ink")
    void anUnsetDatePrintsNothing() {
        // Deliberate: no fallback to createdOn and no clock. A blank is what the paper form always offered;
        // a plausible wrong date on a legal instrument is not recoverable by the person holding it.
        assertThat(DeedValues.date(null)).isEmpty();
    }

    @Test
    @DisplayName("an unset text or number column prints nothing rather than the word null")
    void anUnsetColumnPrintsNothing() {
        assertThat(DeedValues.text(null)).isEmpty();
        assertThat(DeedValues.text("Robin")).isEqualTo("Robin");
        assertThat(DeedValues.number(null)).isEmpty();
        assertThat(DeedValues.number(9)).isEqualTo("9");
    }

    @Test
    @DisplayName("an option set prints its label, never its member name")
    void anOptionSetPrintsItsLabel() {
        assertThat(DeedValues.label(Nationality.BritishCitizen, DynamicsValues.NATIONALITY_LABEL))
            .isEqualTo("British Citizen");
        assertThat(DeedValues.label(NationalitySection.NoneOfTheAbove, DynamicsValues.NATIONALITY_SECTION_LABEL))
            .isEqualTo("None of the Above");
        assertThat(DeedValues.label(MaritalStatus.MarriedCivilPartnership, DynamicsValues.MARITAL_STATUS_LABEL))
            .isEqualTo("Married / Civil Partnership");
        assertThat(DeedValues.label((Nationality) null, DynamicsValues.NATIONALITY_LABEL)).isEmpty();
    }

    @Test
    @DisplayName("a member with no label refuses rather than falling back to its member name")
    void memberWithNoLabelRefuses() {
        // A deed that printed DivorcedDissolved would be wrong in a way nobody would notice until it was
        // sealed, so this is a failure rather than a degradation.
        assertThatThrownBy(() -> DeedValues.label(MaritalStatus.Widowed, Map.of()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MaritalStatus.Widowed");
    }
}
