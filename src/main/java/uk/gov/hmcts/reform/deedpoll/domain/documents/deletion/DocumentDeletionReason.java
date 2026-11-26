package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;
import java.util.Objects;

/**
 * The staff member's own words for why a document is being deleted permanently (Steve, 2026-11-25): free text, not
 * a fixed list, so "Duplicate" and "Uploaded in error" are both reasons.
 *
 * <p>It is copied into the {@code DOCUMENT_DELETED} audit event's detail and the case note, so it outlives the
 * document. Admin's hint asks for why and for no unnecessary applicant details; nothing here can check that. It is
 * never logged and never echoed in a refusal, and {@link #toString()} does not print it.
 *
 * <p>The rule, which {@code V51}'s check constraint states again for what Postgres can check: Unicode whitespace
 * trimmed from both ends ({@link String#strip()}), then 1 to {@value #MAX_LENGTH} code points, with none of: a control
 * character (tab, newline, DEL and C1 included), an unpaired surrogate, a line or paragraph separator, or a
 * bidirectional control (which would let text display other than it reads). Accepted text is kept exactly.
 * Application deletion's {@code DeletionNote} has its own, stricter rule, which this does not touch.
 */
public final class DocumentDeletionReason {

    public static final int MAX_LENGTH = 500;
    public static final String FIELD = "reason";

    private static final String ENTER = "Enter why this document is being deleted";
    private static final String FORBIDDEN =
        "The reason for deletion must not contain line breaks, tabs or control characters";
    private static final String TOO_LONG = "The reason for deletion must be " + MAX_LENGTH + " characters or fewer";

    private final String value;

    private DocumentDeletionReason(String value) {
        this.value = value;
    }

    /**
     * The reason this text makes, or a refusal addressed to {@value #FIELD} in fixed words that never repeat it.
     */
    public static DocumentDeletionReason parse(String raw) {
        if (raw == null) {
            throw refusal(ENTER);
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw refusal(ENTER);
        }
        for (int index = 0; index < trimmed.length(); index++) {
            char unit = trimmed.charAt(index);
            if (Character.isHighSurrogate(unit) && index + 1 < trimmed.length()
                && Character.isLowSurrogate(trimmed.charAt(index + 1))) {
                index++;
            } else if (Character.isSurrogate(unit) || forbidden(unit)) {
                throw refusal(FORBIDDEN);
            }
        }
        if (trimmed.codePointCount(0, trimmed.length()) > MAX_LENGTH) {
            throw refusal(TOO_LONG);
        }
        return new DocumentDeletionReason(trimmed);
    }

    /** Whether this text would parse to exactly itself: what a stored or audited reason must be. */
    public static boolean isParsed(String value) {
        try {
            return value != null && parse(value).value.equals(value);
        } catch (InvalidInputError refused) {
            return false;
        }
    }

    /** The reason's text, for the request row, the audit detail and the case note only. */
    public String value() {
        return value;
    }

    private static boolean forbidden(char unit) {
        int type = Character.getType(unit);
        return Character.isISOControl(unit)
            || type == Character.LINE_SEPARATOR
            || type == Character.PARAGRAPH_SEPARATOR
            || isBidiControl(unit);
    }

    private static boolean isBidiControl(char unit) {
        return unit == '\u061C' || unit == '\u200E' || unit == '\u200F'
            || unit >= '\u202A' && unit <= '\u202E'
            || unit >= '\u2066' && unit <= '\u2069';
    }

    private static InvalidInputError refusal(String message) {
        return new InvalidInputError(message, List.of(new FieldError(FIELD, message)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DocumentDeletionReason reason && value.equals(reason.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    /** Never the text: a reason logged by accident would be in the logs for as long as they are kept. */
    @Override
    public String toString() {
        return "DocumentDeletionReason[redacted]";
    }
}
