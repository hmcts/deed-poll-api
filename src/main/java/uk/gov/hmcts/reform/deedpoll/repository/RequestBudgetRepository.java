package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * The fixed-window request counters behind the abuse limits (V37).
 *
 * <p>One statement does the work, for {@link ReferenceCounterRepository#allocateNextSerial}'s reason: the
 * {@code ON CONFLICT … DO UPDATE} takes the row lock, so a second transaction on the same (subject, bucket) waits
 * for the first and then increments the committed count. A read followed by a write would let two replicas both
 * read 19 and both answer 20, and the first request of a window would race to insert. The statement also resets a
 * row whose window has passed, so nothing ever deletes from the table.
 *
 * <p>Not {@code MANDATORY}, unlike the reference counter: a spend is meant to commit on its own, before the request
 * it pays for runs, so a request that then fails still counts. Called outside a transaction, it auto-commits.
 */
@Repository
public class RequestBudgetRepository {

    private static final String SPEND = """
        INSERT INTO "deedpoll_request_budget" ("subject", "bucket", "windowStart", "count")
        VALUES (?, ?, ?, 1)
        ON CONFLICT ("subject", "bucket") DO UPDATE SET
            "count" = CASE WHEN "deedpoll_request_budget"."windowStart" = EXCLUDED."windowStart"
                           THEN "deedpoll_request_budget"."count" + 1 ELSE 1 END,
            "windowStart" = EXCLUDED."windowStart"
        RETURNING "count", "windowStart"
        """;

    private final JdbcTemplate jdbc;

    public RequestBudgetRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * What one spend left: the count in the current window, this request included, and when that window ends.
     *
     * @param count the requests counted in this window, at least 1
     * @param windowEnd the instant the window closes, from which {@code Retry-After} is computed
     */
    public record Spend(int count, Instant windowEnd) {
    }

    /**
     * Counts one request against a subject's bucket.
     *
     * <p>The window is the request time truncated to a multiple of {@code window} since the epoch, so every
     * replica computes the same window for the same instant, and a window starts afresh rather than sliding.
     *
     * @param subject the IDAM subject, or the shared bucket name for a token with none
     * @param bucket the bucket's name
     * @param at the request time
     * @param window the bucket's window, positive and in whole milliseconds
     * @return the count and the window's end
     */
    public Spend spend(String subject, String bucket, Instant at, Duration window) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(bucket, "bucket");
        long windowMillis = window.toMillis();
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("a budget window must be at least one millisecond");
        }
        long atMillis = at.toEpochMilli();
        Instant windowStart = Instant.ofEpochMilli(atMillis - Math.floorMod(atMillis, windowMillis));

        // UTC on both sides: the column has no zone, and every replica must agree on what it holds.
        return jdbc.queryForObject(SPEND, (row, index) -> new Spend(
                row.getInt("count"),
                row.getObject("windowStart", LocalDateTime.class).toInstant(ZoneOffset.UTC).plusMillis(windowMillis)),
            subject, bucket, LocalDateTime.ofInstant(windowStart, ZoneOffset.UTC));
    }
}
