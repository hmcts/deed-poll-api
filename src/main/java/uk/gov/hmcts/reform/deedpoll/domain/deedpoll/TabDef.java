package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.List;

/**
 * One tab of the case form.
 *
 * <p>Ported from the {@code TabDef} interface in {@code reference/ts/deed-poll/form-fields.ts}.
 * {@code id} is both the URL slug and the form section id. {@code sections} is empty when the tab is a
 * child-entity subgrid rather than fields.
 */
public record TabDef(String id, String label, List<SectionDef> sections, Subgrid subgrid) {

    public TabDef(String id, String label, List<SectionDef> sections, Subgrid subgrid) {
        this.id = id;
        this.label = label;
        this.sections = List.copyOf(sections);
        this.subgrid = subgrid;
    }

    /** A tab of data-entry sections. */
    public static TabDef of(String id, String label, List<SectionDef> sections) {
        return new TabDef(id, label, sections, null);
    }

    /** A tab that renders a child collection rather than fields. */
    public static TabDef subgridTab(String id, String label, Subgrid subgrid) {
        return new TabDef(id, label, List.of(), subgrid);
    }

    /**
     * Which child collection a subgrid tab renders.
     *
     * <p>The reference types this as {@code "notes" | "witnesses" | "documents"} and uses the string
     * to index the record ({@code record[subgrid]}), so {@link #fieldName()} keeps that spelling
     * rather than leaving callers to lower-case an enum name.
     */
    public enum Subgrid {
        NOTES("notes"),
        WITNESSES("witnesses"),
        DOCUMENTS("documents");

        private final String fieldName;

        Subgrid(String fieldName) {
            this.fieldName = fieldName;
        }

        /** The field name the collection is held under on a case. */
        public String fieldName() {
            return fieldName;
        }
    }
}
