package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turning a flat form POST body into an update patch.
 *
 * <p>Ported from {@code reference/ts/deed-poll/form-payload.ts}. Driven by the field metadata so the
 * accepted payload cannot drift from the rendered form. Read-only fields (the computed counts, state,
 * the reference number) are never in {@link FormFields#EDITABLE_FIELDS}, so they are ignored. Date
 * fields arrive as GOV.UK date-input parts and are composed into ISO strings that
 * {@code DeedPollService} then coerces to date-times.
 *
 * <p>Each field passed in is written (an empty input becomes null), matching the Dynamics form where
 * saving persists the whole form and clears blanked fields -- so state derivation stays correct
 * (clearing {@code dateEnroled} un-enrols). Pass ONLY the fields on the submitted tab so a per-tab save
 * never nulls fields on other tabs.
 *
 * <p>The one exception is a {@code notNullable} field: its column is NOT NULL, so "absent" must mean
 * "leave alone" rather than "clear". Nulling one is a 500, which is exactly what a non-admin saving the
 * Application tab used to get -- {@code privacy} renders disabled for them, and a disabled control is
 * not submitted.
 */
public final class FormPayload {

    private FormPayload() {
    }

    /** The patch for a body covering every editable field. */
    public static Map<String, Object> buildUpdatePatch(Map<String, Object> body) {
        return buildUpdatePatch(body, FormFields.EDITABLE_FIELDS);
    }

    /**
     * The patch for a body covering exactly {@code fields} -- normally one tab's worth.
     */
    public static Map<String, Object> buildUpdatePatch(Map<String, Object> body, List<FieldDef> fields) {
        Map<String, Object> patch = new LinkedHashMap<>();
        for (FieldDef field : fields) {
            if (field.readOnly()) {
                continue;
            }
            Object value = readField(field, body);
            if (value == null && field.notNullable()) {
                continue;
            }
            patch.put(field.name(), value);
        }
        return Collections.unmodifiableMap(patch);
    }

    private static Object readField(FieldDef field, Map<String, Object> body) {
        return switch (field.kind()) {
            case DATE -> DateParts.composeDateParts(
                FormValues.datePart(body, field.name(), "day"),
                FormValues.datePart(body, field.name(), "month"),
                FormValues.datePart(body, field.name(), "year")).orElse(null);
            // Unchecked checkboxes are absent from the body; a present truthy value wins.
            case BOOLEAN -> "true".equals(body.get(field.name())) || "on".equals(body.get(field.name()));
            case NUMBER -> readNumber(field, body);
            case MULTISELECT -> FormValues.toIntList(body.get(field.name()));
            // Everything else is read as a plain string: `enum`, `text` and `textarea`, plus any kind
            // added later. The prototype listed those three explicitly ahead of its default branch; they
            // are covered here instead, because the branch behaves identically for all of them and an
            // empty value always becomes null rather than "".
            default -> {
                String value = FormValues.str(body.get(field.name()));
                yield value.isEmpty() ? null : value;
            }
        };
    }

    /**
     * A numeric field, as an {@link Integer} where it is whole and a {@link BigDecimal} otherwise.
     *
     * <p>The reference has one JavaScript {@code number} for both. Java does not, and the two columns
     * behind these fields genuinely differ: the ages are {@code integer} and {@code refundAmount} is
     * {@code numeric(12,2)}. Reading the whole ones as {@code Integer} keeps a patch's value assignable
     * to the column without a narrowing step that could round.
     */
    private static Object readNumber(FieldDef field, Map<String, Object> body) {
        String value = FormValues.str(body.get(field.name()));
        if (value.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException notAnInteger) {
            // Fall through: a decimal amount is legitimate for refundAmount.
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
