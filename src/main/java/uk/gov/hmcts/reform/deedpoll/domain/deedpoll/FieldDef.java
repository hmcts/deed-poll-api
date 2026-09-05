package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.Columnable;

/**
 * One field of the editable case form.
 *
 * <p>Ported from the {@code FieldDef} interface in {@code reference/ts/deed-poll/form-fields.ts}.
 *
 * <p>{@code enumSet} names the {@code DynamicsValues.LABELS} set a field's values are labelled from.
 * The reference types it as {@code keyof typeof LABELS}, which the compiler checks; Java cannot index
 * a map type, so it is a {@link String} here and {@code FormFieldsTest} checks every one used in
 * {@code FORM_TABS} resolves. That is the same guarantee, moved from the compiler to a test.
 */
public record FieldDef(
    String name,
    String label,
    FieldKind kind,
    String enumSet,
    boolean readOnly,
    boolean notNullable,
    CardColumn column) implements Columnable {

    /** A field of the given kind, with no enum set and no flags. */
    public static FieldDef of(String name, String label, FieldKind kind, CardColumn column) {
        return new FieldDef(name, label, kind, null, false, false, column);
    }

    /**
     * A single-select field labelled from the named {@code LABELS} set.
     */
    public static FieldDef enumOf(String name, String label, String enumSet, CardColumn column) {
        return new FieldDef(name, label, FieldKind.ENUM, enumSet, false, false, column);
    }

    /**
     * A multi-select field labelled from the named {@code LABELS} set.
     */
    public static FieldDef multiselectOf(String name, String label, String enumSet, CardColumn column) {
        return new FieldDef(name, label, FieldKind.MULTISELECT, enumSet, false, false, column);
    }

    /**
     * The same field, read-only.
     *
     * <p>Read-only fields render disabled and are never written on save, so they are absent from
     * {@code EDITABLE_FIELDS} and from anything an error summary may link to.
     */
    public FieldDef asReadOnly() {
        return new FieldDef(name, label, kind, enumSet, true, notNullable, column);
    }

    /**
     * The same field, marked as backing a NOT NULL column.
     *
     * <p>An absent input must then be omitted from a patch rather than nulled. Set this on any field
     * whose column has a database default -- {@code privacy} did not have it, and because privacy
     * renders disabled for a non-admin (and browsers do not submit disabled controls) a non-admin
     * saving the Application tab wrote null to a NOT NULL column and 500'd.
     */
    public FieldDef asNotNullable() {
        return new FieldDef(name, label, kind, enumSet, readOnly, true, column);
    }
}
