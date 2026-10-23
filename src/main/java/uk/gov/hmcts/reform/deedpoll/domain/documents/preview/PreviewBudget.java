package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One preview request's time, from the moment it was admitted, and whether its caller is still there.
 *
 * <p>The budget includes conversion (decision 2): a renderer calls {@link #checkpoint} between frames and pages,
 * and a client asks {@link #deadlineFor} how long its connect or read may take, which is never longer than what is
 * left. Both Application Gateways cut a request off at 30 seconds, so a preview that ran on would end on the
 * gateway's error page rather than this service's answer.
 *
 * <p>Thread-safe for the one case it needs: the request thread may {@link #cancel} while a worker checks, and a worker
 * blocked in a remote call rather than at a checkpoint is woken by what it registered with {@link #onCancel}.
 */
public final class PreviewBudget {

    private final Clock clock;
    private final Instant deadline;
    private final Set<Runnable> listeners = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

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
     * The caller has gone; every later {@link #checkpoint} stops the work.
     */
    public void cancel() {
        cancelled = true;
        listeners.forEach(Runnable::run);
    }

    /**
     * Runs {@code action} once if this budget is cancelled while the returned registration is open -- at once, if it
     * already has been. For work a checkpoint cannot reach, such as a converter call blocked on its socket. Closing
     * the registration forgets the action, so a finished call is never cancelled late.
     */
    public Registration onCancel(Runnable action) {
        AtomicBoolean ran = new AtomicBoolean();
        Runnable once = () -> {
            if (ran.compareAndSet(false, true)) {
                action.run();
            }
        };
        listeners.add(once);
        if (cancelled) {
            once.run();
        }
        return () -> listeners.remove(once);
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Stop here if the caller has gone or the time is up.
     *
     * @throws PreviewCancelledException if {@link #cancel} was called
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} if the budget is spent
     */
    public void checkpoint() {
        if (cancelled) {
            throw new PreviewCancelledException();
        }
        if (isExpired()) {
            throw new PreviewError(PreviewFailure.LIMIT_EXCEEDED);
        }
    }

    /**
     * A remote deadline: the configured one, or what is left of the budget if that is shorter.
     *
     * @throws PreviewCancelledException if {@link #cancel} was called
     * @throws PreviewError {@link PreviewFailure#LIMIT_EXCEEDED} if nothing is left to spend
     */
    public Duration deadlineFor(Duration configured) {
        checkpoint();
        Duration left = remaining();
        return configured.compareTo(left) < 0 ? configured : left;
    }

    /**
     * An {@link #onCancel} action, forgotten when closed.
     */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {

        @Override
        void close();
    }
}
