package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The write-side coercions, where a value stops being whatever a producer sent and becomes what the column holds.
 *
 * <p>This is the last thing before persistence, so a coercion that quietly changes a value here produces a wrong
 * answer nothing downstream can detect. {@code DeedPollSchemas} argues the case for refusing rather than
 * truncating and enforces it on the JSON path; these assert the writers agree, because the schema is not the only
 * producer of a patch -- {@code FormPayload.readNumber} falls back to a {@code BigDecimal} for a numeric field, so
 * a form can reach a writer with a fractional value and nothing in between to reject it.
 */
class DeedPollFieldsWriteTest {

    @Test
    @DisplayName("stores a whole number, whichever numeric type it arrived as")
    void storesAWholeNumber() {
        DeedPoll record = new DeedPoll();

        DeedPollFields.write(record, "ageOfMinor", 7);
        assertThat(record.getAgeOfMinor()).isEqualTo(7);

        // A form yields text, and the JSON schema yields an Integer; both have to land as the same value.
        DeedPollFields.write(record, "ageOfMinor", " 8 ");
        assertThat(record.getAgeOfMinor()).isEqualTo(8);

        DeedPollFields.write(record, "ageOfMinor", new BigDecimal("9"));
        assertThat(record.getAgeOfMinor()).isEqualTo(9);
    }

    @Test
    @DisplayName("refuses a fractional age rather than truncating it")
    void refusesAFractionalAge() {
        DeedPoll record = new DeedPoll();

        // The failure this exists to prevent: intValue() would have stored 7, and an age silently becoming 7 on a
        // permanent case record is a worse answer than an error, which is the rule DeedPollSchemas states.
        assertThatThrownBy(() -> DeedPollFields.write(record, "ageOfMinor", new BigDecimal("7.5")))
            .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> DeedPollFields.write(record, "ageOfMinor", 7.5d))
            .isInstanceOf(ArithmeticException.class);

        assertThat(record.getAgeOfMinor()).as("nothing is written when the value is refused").isNull();
    }

    @Test
    @DisplayName("refuses a whole number too large for the column rather than wrapping it")
    void refusesAnOutOfRangeAge() {
        DeedPoll record = new DeedPoll();

        // intValue() narrows by wrapping, so this would have stored 0 -- the same silent wrong answer as 7.5
        // becoming 7, reached by a different route.
        assertThatThrownBy(() -> DeedPollFields.write(record, "ageOfMinor", 4294967296L))
            .isInstanceOf(ArithmeticException.class);

        assertThat(record.getAgeOfMinor()).isNull();
    }

    @Test
    @DisplayName("clears an integer column on an explicit null, which is what a patch clearing it means")
    void clearsAnIntegerOnNull() {
        DeedPoll record = new DeedPoll();
        DeedPollFields.write(record, "ageOfMinor", 7);

        DeedPollFields.write(record, "ageOfMinor", null);

        assertThat(record.getAgeOfMinor()).isNull();
    }
}
