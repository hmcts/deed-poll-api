package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;
import java.util.Objects;

/**
 * The Delivery Manager's own words for why an application is being deleted (Steve, 2026-11-18): required beside the
 * reason code, so the user confirms what they intend.
 *
 * <p>It outlives the case, in the request and in the audit event's {@code deletionNote}, for the audit's seven years,
 * so it must say why and must not carry the applicant's personal details. Nothing can check that, so the API and
 * Admin both say it. It is never logged, and {@link #toString()} does not print it.
 *
 * <p>The rule, which {@code V48}'s check constraint states again: ordinary spaces trimmed from both ends, then 10 to
 * 500 characters (code points, as Postgres counts them), with no control character anywhere -- tab, newline, DEL and
 * C1 included -- and no unpaired surrogate.
 */
public final class DeletionNote {

    public static final int MIN_LENGTH = 10;
    public static final int MAX_LENGTH = 500;
    public static final String FIELD = "note";

    private final String value;

    private DeletionNote(String value) {
        this.value = value;
    }

    /**
     * The note this text makes, or a refusal addressed to {@value #FIELD}.
     *
     * <p>The refusal's words are fixed, and never repeat the text.
     */
    public static DeletionNote parse(String raw) {
        if (raw == null) {
            throw refusal("Enter why this application is being deleted");
        }
        String trimmed = trimSpaces(raw);
        if (trimmed.isEmpty()) {
            throw refusal("Enter why this application is being deleted");
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char unit = trimmed.charAt(index);
            if (Character.isHighSurrogate(unit) && index + 1 < trimmed.length()
                && Character.isLowSurrogate(trimmed.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(unit) || Character.isISOControl(unit)) {
                throw refusal("The reason for deletion must not contain tabs, line breaks or control characters");
            }
        }
        int length = trimmed.codePointCount(0, trimmed.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw refusal("The reason for deletion must be between " + MIN_LENGTH + " and " + MAX_LENGTH
                + " characters");
        }
        return new DeletionNote(trimmed);
    }

    /** The note's text, for the request row and the audit detail only. */
    public String value() {
        return value;
    }

    private static String trimSpaces(String raw) {
        int start = 0;
        int end = raw.length();
        while (start < end && raw.charAt(start) == ' ') {
            start++;
        }
        while (end > start && raw.charAt(end - 1) == ' ') {
            end--;
        }
        return raw.substring(start, end);
    }

    private static InvalidInputError refusal(String message) {
        return new InvalidInputError(message, List.of(new FieldError(FIELD, message)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DeletionNote note && value.equals(note.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /** Never the text: a note logged by accident would be in the logs for as long as they are kept. */
    @Override
    public String toString() {
        return "DeletionNote[redacted]";
    }
}
