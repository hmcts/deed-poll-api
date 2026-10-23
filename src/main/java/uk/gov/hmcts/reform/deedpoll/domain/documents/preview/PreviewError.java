package uk.gov.hmcts.reform.deedpoll.domain.documents.preview;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * A preview that was not produced, for one of {@link PreviewFailure}'s reasons.
 *
 * <p>It carries no cause, deliberately. A parser's or a client's exception message can quote the file, the
 * converter's body or its URL, and a cause is what a handler logs without thinking. Whoever throws this logs the
 * underlying exception's class name if anything, and only the failure's fixed sentence travels further.
 */
public class PreviewError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final PreviewFailure failure;
    private final transient Duration retryAfter;

    public PreviewError(PreviewFailure failure) {
        this(failure, null);
    }

    private PreviewError(PreviewFailure failure, Duration retryAfter) {
        super(Objects.requireNonNull(failure, "failure").message());
        this.failure = failure;
        this.retryAfter = retryAfter;
    }

    /**
     * {@link PreviewFailure#BUSY}, with the bounded wait the response's {@code Retry-After} carries.
     */
    public static PreviewError busy(Duration retryAfter) {
        return new PreviewError(PreviewFailure.BUSY, Objects.requireNonNull(retryAfter, "retryAfter"));
    }

    public PreviewFailure failure() {
        return failure;
    }

    /**
     * Present only for {@link PreviewFailure#BUSY}.
     */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
