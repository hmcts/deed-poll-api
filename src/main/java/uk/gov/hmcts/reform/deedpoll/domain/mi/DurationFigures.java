package uk.gov.hmcts.reform.deedpoll.domain.mi;

/**
 * How long a bucket's intervals took, in whole calendar days (plan D4), and how many there were.
 *
 * <p>Always published with {@code count} beside it (plan D6), however small the bucket. A bucket with no intervals
 * has {@code count} 0 and null percentiles: there is nothing to compute, which is not the same as zero days.
 * The percentiles are Postgres's {@code percentile_cont}, interpolating between the two nearest values, so a
 * median of two intervals is their mean.
 */
public record DurationFigures(long count, Double medianDays, Double p95Days) {

    public static final DurationFigures NONE = new DurationFigures(0, null, null);

    public DurationFigures {
        if (count == 0 && (medianDays != null || p95Days != null)) {
            throw new IllegalArgumentException("An empty bucket has no percentiles");
        }
    }
}
