package uk.gov.hmcts.reform.deedpoll.entity;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * The clock the audit columns are stamped from: UTC, always.
 *
 * <p>Every {@code createdOn} and {@code modifiedOn} in this schema is a {@code timestamp} with no zone,
 * and the whole codebase reads them as UTC wall time -- {@code Dates} says so, {@code Computed}'s day
 * arithmetic assumes it, and {@code CaseHeader.formatTimestamp} converts *out* of UTC into
 * Europe/London for display. A bare {@link LocalDateTime#now()} would stamp the JVM's default zone
 * instead, so a save made at 09:44 British Summer Time would be stored as 09:44 and then rendered as
 * 10:44am -- an hour of silent drift in audit data and in every {@code createdOn} ordering, appearing
 * only on a machine or image where {@code TZ} is set.
 *
 * <p>One method rather than eight copies of {@code LocalDateTime.now(ZoneOffset.UTC)}, because the
 * decision is one decision and a copy of it that drifts is exactly the bug above.
 */
public final class AuditClock {

    private AuditClock() {
    }

    /** The current instant as UTC wall time, which is what the audit columns hold. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
