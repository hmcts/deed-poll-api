package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Decision 5's encoding: one text form per value, so a before and an after compare as stored. */
class AuditValueTest {

    @Test
    @DisplayName("writes a date as ISO-8601")
    void writesADateAsIso() {
        assertThat(AuditValue.of("dateReceived", null, LocalDate.of(2026, 10, 5)).newValue())
            .isEqualTo("2026-10-05");
    }

    @Test
    @DisplayName("writes a timestamp as ISO-8601, seconds included even when they are zero")
    void writesATimestampAsIso() {
        assertThat(AuditValue.of("closedAt", null, LocalDateTime.of(2026, 10, 5, 9, 0)).newValue())
            .isEqualTo("2026-10-05T09:00:00");
    }

    @Test
    @DisplayName("writes an enum by its name, never its ordinal")
    void writesAnEnumByName() {
        assertThat(AuditValue.of("type", null, DeedPollType.Adult).newValue()).isEqualTo(DeedPollType.Adult.name());
    }

    @Test
    @DisplayName("writes booleans as true and false")
    void writesBooleans() {
        AuditValue value = AuditValue.of("isWelsh", false, true);

        assertThat(value.oldValue()).isEqualTo("false");
        assertThat(value.newValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("writes a decimal in plain notation and anything else as its text")
    void writesOtherValuesAsText() {
        UUID id = UUID.randomUUID();

        assertThat(AuditValue.of("fee", null, new BigDecimal("1E+3")).newValue()).isEqualTo("1000");
        assertThat(AuditValue.of("id", null, id).newValue()).isEqualTo(id.toString());
        assertThat(AuditValue.of("newForename", null, "Ada").newValue()).isEqualTo("Ada");
    }

    @Test
    @DisplayName("keeps a null as a null, so a cleared field reads as a null new value")
    void keepsANull() {
        AuditValue cleared = AuditValue.of("newForename", "Ada", null);

        assertThat(cleared.oldValue()).isEqualTo("Ada");
        assertThat(cleared.newValue()).isNull();
        assertThat(cleared.changed()).isTrue();
    }

    @Test
    @DisplayName("records a created field with a null old value")
    void recordsACreatedField() {
        AuditValue created = AuditValue.created("newForename", "Ada");

        assertThat(created.oldValue()).isNull();
        assertThat(created.newValue()).isEqualTo("Ada");
    }

    @Test
    @DisplayName("compares the encoded forms, so equal values of different objects are unchanged")
    void comparesTheEncodedForms() {
        assertThat(AuditValue.of("dateReceived", LocalDate.of(2026, 10, 5), LocalDate.parse("2026-10-05")).changed())
            .isFalse();
        assertThat(AuditValue.of("newForename", null, null).changed()).isFalse();
        assertThat(AuditValue.of("newForename", "Ada", "Ada ").changed()).isTrue();
    }

    @Test
    @DisplayName("refuses a value with no field")
    void refusesAValueWithNoField() {
        assertThatThrownBy(() -> AuditValue.of(" ", null, "Ada")).isInstanceOf(IllegalArgumentException.class);
    }
}
