package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

/**
 * The control kind, deciding how a field renders and how its submitted value coerces.
 *
 * <p>Ported from the {@code FieldKind} union in {@code reference/ts/deed-poll/form-fields.ts}.
 */
public enum FieldKind {
    TEXT,
    TEXTAREA,
    DATE,
    NUMBER,
    BOOLEAN,
    ENUM,
    MULTISELECT
}
