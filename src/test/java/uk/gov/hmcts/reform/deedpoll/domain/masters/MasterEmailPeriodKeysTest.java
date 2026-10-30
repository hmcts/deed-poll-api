package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code SENT} rows' period keys (plan F1, step 8), which V44 holds to 64 characters.
 */
class MasterEmailPeriodKeysTest {

    private static final UUID ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    @DisplayName("a send's key is its own id")
    void send() {
        assertThat(MasterEmailTransactions.sendPeriod(ID)).isEqualTo("send:" + ID).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("a reassignment's key is the referral and the moment, to the microsecond, whatever the moment")
    void reassignment() {
        assertThat(MasterEmailTransactions.reassignmentPeriod(ID, LocalDateTime.of(2026, 10, 31, 16, 0)))
            .isEqualTo(ID + "@2026-10-31T16:00:00.000000").hasSize(63);
        LocalDateTime withNanos = LocalDateTime.of(2026, 10, 31, 16, 0, 1, 123_456_789);
        assertThat(MasterEmailTransactions.reassignmentPeriod(ID, withNanos))
            .isEqualTo(ID + "@2026-10-31T16:00:01.123456").hasSize(63);
    }
}
