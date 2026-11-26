package uk.gov.hmcts.reform.deedpoll.ccd;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * The system user's and the service's tokens, each waited for no longer than a deadline allows (plan
 * 20261125-permanent-document-deletion; docs/DOCUMENT-DELETION.md, "Request budget and per-call allowances").
 *
 * <p>A cold IDAM password grant or S2S lease has its own client timeouts, and {@link SystemUserTokens} makes a
 * second caller wait for the first's grant, so neither is bounded by a staff request's budget. Here each grant runs
 * on a small pool of its own and the caller waits only for what {@code timeLeft} says is left, asked again before the
 * second grant. A grant that outlasts the wait is not cancelled: it finishes in the background and fills the token
 * caches for the next attempt. A full pool is the same as no time: nothing is sent, and the sweep tries again.
 */
public final class TokenGrants {

    /** The pool's ceiling: grants are cached, so more than a few at once means the token services are stuck. */
    private static final int MAX_GRANTS = 4;

    private static final ExecutorService GRANTS = new ThreadPoolExecutor(
        0, MAX_GRANTS, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), new Daemons(),
        new ThreadPoolExecutor.AbortPolicy());

    private TokenGrants() {
    }

    /** The two bearer tokens a CCD or CDAM call sends. */
    public record Tokens(String user, String service) {
    }

    /**
     * The user's token, then the service's, each waited for only while {@code timeLeft} is not negative, and
     * {@code timeLeft} asked once more when both are in hand.
     *
     * @return empty when time ran out (or the pool was full) before both were in hand, or by the time they were, so
     *         nothing may be sent
     * @throws RuntimeException whatever a grant threw, as it was, for the caller to classify
     */
    public static Optional<Tokens> within(Supplier<Duration> timeLeft, Supplier<String> user,
                                          Supplier<String> service) {
        Optional<String> userToken = grant(timeLeft, user);
        if (userToken.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> serviceToken = grant(timeLeft, service);
        if (serviceToken.isEmpty() || timeLeft.get().isNegative()) {
            return Optional.empty();
        }
        return Optional.of(new Tokens(userToken.get(), serviceToken.get()));
    }

    private static Optional<String> grant(Supplier<Duration> timeLeft, Supplier<String> grant) {
        Duration left = timeLeft.get();
        if (left.isNegative()) {
            return Optional.empty();
        }
        Future<String> pending;
        try {
            pending = GRANTS.submit(grant::get);
        } catch (RejectedExecutionException busy) {
            return Optional.empty();
        }
        try {
            return Optional.of(pending.get(left.toNanos(), TimeUnit.NANOSECONDS));
        } catch (TimeoutException late) {
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("A token grant failed: " + cause.getClass().getSimpleName());
        }
    }

    /** Named daemon threads, so an abandoned grant never holds the JVM open. */
    private static final class Daemons implements ThreadFactory {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable work) {
            Thread thread = new Thread(work, "token-grant-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
