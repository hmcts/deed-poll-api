package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

/**
 * A single field's UI and validation rule for a given Type.
 *
 * <p>Ported from the {@code FieldRule} interface in
 * {@code reference/ts/deed-poll/deed-poll-validation.ts}. Consumed by both the form (show / hide /
 * mandate) and request-time validation, which is the point of it: one answer to "is this field
 * required for this case", not one per consumer.
 */
public record FieldRule(boolean visible, boolean required) {

    /**
     * The same rule with {@code required} replaced, since a record cannot be edited in place.
     */
    FieldRule withRequired(boolean newRequired) {
        return new FieldRule(visible, newRequired);
    }

    /**
     * The same rule with {@code visible} replaced.
     */
    FieldRule withVisible(boolean newVisible) {
        return new FieldRule(newVisible, required);
    }
}
