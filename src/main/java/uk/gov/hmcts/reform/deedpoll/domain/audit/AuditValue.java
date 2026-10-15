package uk.gov.hmcts.reform.deedpoll.domain.audit;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * One field's before and after, as {@code deedpoll_audit_event_value} stores it. This is applicant data, and is
 * disposed with the case.
 *
 * <p>Both sides are text, encoded once here: a date or timestamp as ISO-8601, an enum by its name, a boolean as
 * {@code true}/{@code false}, a decimal in plain notation, anything else as its own text. A null stays a SQL NULL,
 * so a cleared field reads as a null {@link #newValue} and a created one as a null {@link #oldValue}. Whether the
 * field {@link #changed} is decided on the encoded forms, which are what a reader of the trail compares.
 */
public record AuditValue(String field, String oldValue, String newValue) {

    public AuditValue {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("An audit value needs its field");
        }
    }

    /**
     * A field that went from {@code before} to {@code after}.
     */
    public static AuditValue of(String field, Object before, Object after) {
        return new AuditValue(field, encode(before), encode(after));
    }

    /** A field a create set, which had no value before. */
    public static AuditValue created(String field, Object value) {
        return of(field, null, value);
    }

    /**
     * Whether the value differs; {@link AuditTrail} writes no row for one that does not.
     */
    public boolean changed() {
        return !Objects.equals(oldValue, newValue);
    }

    static String encode(Object value) {
        return switch (value) {
            case null -> null;
            case Enum<?> constant -> constant.name();
            case LocalDate date -> DateTimeFormatter.ISO_LOCAL_DATE.format(date);
            case LocalDateTime timestamp -> DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(timestamp);
            case OffsetDateTime timestamp -> DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(timestamp);
            case Instant instant -> DateTimeFormatter.ISO_INSTANT.format(instant);
            case BigDecimal decimal -> decimal.toPlainString();
            default -> value.toString();
        };
    }
}
