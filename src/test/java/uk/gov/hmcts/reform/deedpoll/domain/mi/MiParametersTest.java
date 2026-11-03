package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiParametersTest {

    private static final LocalDate FROM = LocalDate.of(2190, 1, 1);

    @Test
    void includesMigratedCasesUnlessToldNotTo() {
        assertThat(MiParameters.of(FROM, FROM, MiGranularity.MONTH, null).includeMigrated()).isTrue();
        assertThat(MiParameters.of(FROM, FROM, MiGranularity.MONTH, false).includeMigrated()).isFalse();
    }

    @Test
    void namesEveryMissingField() {
        assertThatThrownBy(() -> MiParameters.of(null, null, null, null))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::field).containsExactly("from", "to", "granularity"));
    }

    @Test
    void refusesAnEndBeforeTheStart() {
        assertThatThrownBy(() -> MiParameters.of(FROM, FROM.minusDays(1), MiGranularity.WEEK, true))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::field).containsExactly("to"));
    }

    @Test
    void allowsExactlyTwoYearsAndNotADayMore() {
        assertThat(MiParameters.of(FROM, FROM.plusYears(2), MiGranularity.WEEK, true).to())
            .isEqualTo(LocalDate.of(2192, 1, 1));
        assertThatThrownBy(() -> MiParameters.of(FROM, FROM.plusYears(2).plusDays(1), MiGranularity.WEEK, true))
            .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                .extracting(FieldError::message).containsExactly("The range cannot be longer than 2 years."));
    }

    @Test
    void weeksStartOnMondayAndAreClippedToTheRange() {
        // 1 January 2190 is a Friday, in the ISO week of Monday 28 December 2189.
        assertThat(MiParameters.of(FROM, LocalDate.of(2190, 1, 12), MiGranularity.WEEK, true).periods())
            .containsExactly(
                new MiPeriod(FROM, LocalDate.of(2190, 1, 3)),
                new MiPeriod(LocalDate.of(2190, 1, 4), LocalDate.of(2190, 1, 10)),
                new MiPeriod(LocalDate.of(2190, 1, 11), LocalDate.of(2190, 1, 12)));
    }

    @Test
    void monthsAndQuartersAreCalendarPeriods() {
        assertThat(MiParameters.of(LocalDate.of(2189, 12, 15), LocalDate.of(2190, 2, 28), MiGranularity.MONTH, true)
            .periods()).containsExactly(
                new MiPeriod(LocalDate.of(2189, 12, 15), LocalDate.of(2189, 12, 31)),
                new MiPeriod(FROM, LocalDate.of(2190, 1, 31)),
                new MiPeriod(LocalDate.of(2190, 2, 1), LocalDate.of(2190, 2, 28)));
        assertThat(MiParameters.of(LocalDate.of(2190, 3, 31), LocalDate.of(2190, 10, 1), MiGranularity.QUARTER, true)
            .periods()).containsExactly(
                new MiPeriod(LocalDate.of(2190, 3, 31), LocalDate.of(2190, 3, 31)),
                new MiPeriod(LocalDate.of(2190, 4, 1), LocalDate.of(2190, 6, 30)),
                new MiPeriod(LocalDate.of(2190, 7, 1), LocalDate.of(2190, 9, 30)),
                new MiPeriod(LocalDate.of(2190, 10, 1), LocalDate.of(2190, 10, 1)));
    }

    @Test
    void javaAndPostgresTruncateAlike() {
        assertThat(MiGranularity.WEEK.field()).isEqualTo("week");
        assertThat(MiGranularity.QUARTER.start(LocalDate.of(2190, 12, 31))).isEqualTo(LocalDate.of(2190, 10, 1));
    }
}
