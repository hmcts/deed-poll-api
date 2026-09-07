package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.List;

/**
 * One card of a form tab: a heading and the fields under it, in source order.
 *
 * <p>Ported from the {@code SectionDef} interface in {@code reference/ts/deed-poll/form-fields.ts}.
 */
public record SectionDef(String label, List<FieldDef> fields) {

    public SectionDef(String label, List<FieldDef> fields) {
        this.label = label;
        this.fields = List.copyOf(fields);
    }
}
