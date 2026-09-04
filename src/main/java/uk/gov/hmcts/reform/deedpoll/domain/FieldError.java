package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * A failure addressed to a specific field, so a form can render it in place.
 *
 * <p>Ported from the {@code FieldError} interface in {@code reference/ts/errors.ts}. The field name
 * is the caller's name for the input -- the reference dots nested and indexed paths
 * ({@code witnesses.0.name}) -- and an empty field means the failure is addressed to the form as a
 * whole rather than to any one input.
 */
public record FieldError(String field, String message) {
}
