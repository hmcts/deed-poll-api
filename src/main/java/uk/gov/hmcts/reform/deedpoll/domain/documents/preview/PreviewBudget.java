package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * One preview request's time, from the moment it was admitted.
 *
 * <p>The budget includes conversion (decision 2): a renderer calls {@link #checkpoint} between frames and pages,
 * and a client asks {@link #deadlineFor} how long its connect or read may take, which is never longer than what is
 * left. Both Application Gateways cut a request off at 30 seconds, so a preview that ran on would end on the
 * gateway's error page rather than this service's answer.
 *
 * <p>It is also the only bound on abandoned work. The request thread does the conversion itself, so a caller who
 * disconnects mid-conversion is noticed only when the finished PDF is written; until then the work runs on to its
 * end or to this budget, holding its permit throughout.
 */
public final class PreviewBudget {

    private final Clock clock;
    private final Instant deadline;

    private PreviewBudget(Clock clock, Instant deadline) {
        this.clock = clock;
        this.deadline = deadline;
    }

    /**
     * A budget of {@code total}, starting now on {@code clock}.
     */
    public static PreviewBudget start(Clock clock, Duration total) {
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(total, "total");
        return new PreviewBudget(clock, clock.instant().plus(total));
    }

    /** What is left, never negative. */
    public Duration remaining() {
        Duration left = Duration.between(clock.instant(), deadline);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** Whether the time is used up. */
    public boolean isExpired() {
        return !clock.instant().isBefore(deadline);
    }

    /**
     * Stop here if the time is up.
     *
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} if the budget is spent
     */
    public void checkpoint() {
        if (isExpired()) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    /**
     * A remote deadline: the configured one, or what is left of the budget if that is shorter.
     *
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} if nothing is left to spend
     */
    public Duration deadlineFor(Duration configured) {
        checkpoint();
        Duration left = remaining();
        return configured.compareTo(left) < 0 ? configured : left;
    }
}
