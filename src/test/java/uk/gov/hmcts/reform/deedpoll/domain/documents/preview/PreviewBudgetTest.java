package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreviewBudgetTest {

    private final MovingClock clock = new MovingClock();

    @Test
    @DisplayName("the budget counts down from admission and never goes negative")
    void countsDown() {
        PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
        clock.advance(Duration.ofSeconds(10));
        assertThat(budget.remaining()).isEqualTo(Duration.ofSeconds(15));
        budget.checkpoint();
        clock.advance(Duration.ofSeconds(20));
        assertThat(budget.remaining()).isZero();
        assertThat(budget.isExpired()).isTrue();
    }

    @Test
    @DisplayName("a cancel runs each open registration once, at once if already cancelled, and never a closed one")
    void onCancel() {
        PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
        int[] open = new int[1];
        int[] closed = new int[1];
        budget.onCancel(() -> open[0]++);
        budget.onCancel(() -> closed[0]++).close();
        budget.cancel();
        budget.cancel();
        assertThat(open[0]).isEqualTo(1);
        assertThat(closed[0]).isZero();

        int[] late = new int[1];
        budget.onCancel(() -> late[0]++);
        assertThat(late[0]).isEqualTo(1);
    }

    @Test
    @DisplayName("a spent budget stops the work as a limit, never as a partial preview")
    void spentIsLimitExceeded() {
        PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
        clock.advance(Duration.ofSeconds(25));
        assertThatThrownBy(budget::checkpoint).isInstanceOfSatisfying(PreviewError.class,
            error -> assertThat(error.failure()).isEqualTo(PreviewFailure.LIMIT_EXCEEDED));
        assertThatThrownBy(() -> budget.deadlineFor(Duration.ofSeconds(3))).isInstanceOf(PreviewError.class);
    }

    @Test
    @DisplayName("a cancelled request stops at its next checkpoint, ahead of any expiry")
    void cancellationStops() {
        PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
        budget.cancel();
        clock.advance(Duration.ofSeconds(30));
        assertThat(budget.isCancelled()).isTrue();
        assertThatThrownBy(budget::checkpoint).isInstanceOf(PreviewCancelledException.class);
        assertThatThrownBy(() -> budget.deadlineFor(Duration.ofSeconds(3)))
            .isInstanceOf(PreviewCancelledException.class);
    }

    @Test
    @DisplayName("a remote deadline is the configured one, or what is left if that is shorter")
    void remoteDeadlinesFitTheBudget() {
        PreviewBudget budget = PreviewBudget.start(clock, Duration.ofSeconds(25));
        assertThat(budget.deadlineFor(Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(20));
        clock.advance(Duration.ofSeconds(22));
        assertThat(budget.deadlineFor(Duration.ofSeconds(20))).isEqualTo(Duration.ofSeconds(3));
    }

    private static final class MovingClock extends Clock {

        private Instant now = Instant.parse("2026-10-23T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
