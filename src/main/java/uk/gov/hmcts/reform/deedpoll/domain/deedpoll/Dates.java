package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

/**
 * Reading a date out of a loosely-typed value, in UTC.
 *
 * <p>The reference does this inline, as {@code raw instanceof Date ? raw : new Date(String(raw))},
 * in {@code computed.ts}, {@code date-parts.ts}, {@code detail-view.ts} and {@code case-header.ts}.
 * Java needs a few more lines per attempt, so those four copies are collected here rather than
 * repeated: the alternative is four opinions about whether {@code "2026-03-01"} is a date.
 *
 * <p>Everything resolves to UTC wall time. Every ported column is a calendar date or a UTC instant
 * (see {@code deed-poll.prisma}), and the mapped {@link LocalDateTime} is UTC by construction -- so
 * an offset in the text is applied and discarded rather than kept, and a value with no offset is
 * taken as already-UTC.
 *
 * <p>An unreadable value gives {@code null}, which is what {@code new Date("nonsense")} amounts to
 * once the caller has checked {@code isNaN}. Every caller here does check, and the placeholder each
 * one renders is the reference's.
 */
final class Dates {

    private Dates() {
    }

    /**
     * Reads {@code raw} as a UTC date-time, or {@code null} if it is absent, blank or unparseable.
     *
     * <p>Accepts what the reference's {@code new Date(...)} accepts of the values this codebase
     * actually holds: a mapped {@link LocalDateTime}, an {@link Instant} or offset value from a
     * caller, an ISO date-time with or without an offset, and a bare ISO date.
     */
    static LocalDateTime toDateTime(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (raw instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (raw instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (raw instanceof OffsetDateTime offset) {
            return offset.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        if (raw instanceof ZonedDateTime zoned) {
            return zoned.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        }
        return parse(String.valueOf(raw));
    }

    /** Parses ISO text: an instant, an offset date-time, a local date-time, or a bare date. */
    private static LocalDateTime parse(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(trimmed).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException withoutOffset) {
            // Fall through: the value may carry no zone, or be a bare calendar date.
        }
        try {
            return LocalDateTime.parse(trimmed);
        } catch (DateTimeParseException withoutTime) {
            // Fall through: the value may be a bare calendar date.
        }
        try {
            return LocalDate.parse(trimmed).atStartOfDay();
        } catch (DateTimeParseException notADate) {
            return null;
        }
    }
}
