package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pod's few conversion permits (decision 5), refused rather than queued when they are all in use.
 *
 * <p>A conversion holds a decoded frame or a converter response in memory, so the number running at once is what
 * bounds this pod's preview heap. There is deliberately no queue: a waiting request would hold its connection and
 * its source stream, and would spend the budget it needs for the conversion itself. A caller over capacity is told
 * {@link PreviewFailure#BUSY} with a bounded {@code Retry-After} at once.
 *
 * <p>A PDF passes through and takes nothing. The permit is released by {@link Permit#close} only, exactly once,
 * and its holder closes it when the work has actually finished -- not when the caller disconnects, because a worker
 * still decoding after a disconnect is still using the memory the permit stands for.
 */
public final class ConversionCapacity {

    private static final Permit NONE = new Permit(null);

    private final Semaphore permits;
    private final Duration retryAfter;

    public ConversionCapacity(int permits, Duration retryAfter) {
        if (permits < 1) {
            throw new IllegalArgumentException("conversion capacity must be at least 1");
        }
        this.permits = new Semaphore(permits);
        this.retryAfter = retryAfter;
    }

    /**
     * A permit for this format's preview, at once or not at all.
     *
     * @throws PreviewError {@link PreviewFailure#BUSY} if the format needs a permit and none is free
     */
    public Permit acquire(PreviewFormat format) {
        if (!format.isConversion()) {
            return NONE;
        }
        if (!permits.tryAcquire()) {
            throw PreviewError.busy(retryAfter);
        }
        return new Permit(permits);
    }

    /** Permits free now; for tests and metrics. */
    public int available() {
        return permits.availablePermits();
    }

    /** One held permit; closing it more than once releases it once. */
    public static final class Permit implements AutoCloseable {

        private final Semaphore owner;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (owner != null && released.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
