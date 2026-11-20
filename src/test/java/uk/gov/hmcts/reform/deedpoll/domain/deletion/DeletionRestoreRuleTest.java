package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionRestoreRuleTest {

    private static final LocalDateTime UNTIL = LocalDateTime.of(2026, 12, 3, 10, 0, 0, 123_000_000);
    private static final LocalDateTime INSIDE = UNTIL.minusDays(1);

    private static DeletionRequest request(DeletionStatus status, UUID owner, int attempts) {
        return new DeletionRequest(UUID.randomUUID(), "case-1", UUID.randomUUID(), DeletionReason.DUPLICATE,
                                   DeletionNote.parse("Logged twice from one application"), "0".repeat(64),
                                   UNTIL.minusDays(15), "dm", "Dee Manager", UNTIL.minusDays(14), null, UNTIL, status,
                                   null, attempts, owner, owner == null ? null : UNTIL.plusMinutes(5), UNTIL, null);
    }

    @Test
    @DisplayName("a Pending, unclaimed, unattempted request is restorable up to the millisecond before its end")
    void restorableInsideTheWindow() {
        DeletionRequest pending = request(DeletionStatus.Pending, null, 0);

        assertThat(DeletionRestoreRule.refusal(pending, UNTIL.minusDays(14))).isEmpty();
        assertThat(DeletionRestoreRule.refusal(pending, UNTIL.minusNanos(1_000_000))).isEmpty();
        assertThat(DeletionRestoreRule.refusal(pending, UNTIL)).contains(DeletionRefusal.RESTORE_WINDOW_CLOSED);
        assertThat(DeletionRestoreRule.refusal(pending, UNTIL.plusDays(1)))
            .contains(DeletionRefusal.RESTORE_WINDOW_CLOSED);
    }

    @ParameterizedTest
    @EnumSource(value = DeletionStatus.class, names = "Pending", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("any status but Pending is cleanup started, inside the window or after it")
    void otherStatusesHaveStarted(DeletionStatus status) {
        // Unclaimed and unattempted, so only the status can refuse it.
        DeletionRequest started = request(status, null, 0);

        assertThat(DeletionRestoreRule.refusal(started, INSIDE)).contains(DeletionRefusal.CLEANUP_STARTED);
        assertThat(DeletionRestoreRule.refusal(started, UNTIL.plusDays(1))).contains(DeletionRefusal.CLEANUP_STARTED);
    }

    @Test
    @DisplayName("a claim or a past attempt is cleanup started even while Pending, and is said before the window")
    void claimedOrAttemptedHasStarted() {
        assertThat(DeletionRestoreRule.refusal(request(DeletionStatus.Pending, UUID.randomUUID(), 0), INSIDE))
            .contains(DeletionRefusal.CLEANUP_STARTED);
        assertThat(DeletionRestoreRule.refusal(request(DeletionStatus.Pending, null, 1), INSIDE))
            .contains(DeletionRefusal.CLEANUP_STARTED);
        assertThat(DeletionRestoreRule.refusal(request(DeletionStatus.Pending, null, 1), UNTIL.plusDays(1)))
            .contains(DeletionRefusal.CLEANUP_STARTED);
    }
}
