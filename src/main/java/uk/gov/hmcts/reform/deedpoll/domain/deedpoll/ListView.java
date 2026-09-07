package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;

import java.util.List;

/**
 * View model for the case-list table.
 *
 * <p>Ported from {@code reference/ts/deed-poll/list-view.ts}. Built here rather than formatted in the
 * template, which is how the prototype did it via two custom Nunjucks filters: formatting in the view
 * model needs no bespoke template environment and, unlike a filter, can be tested directly.
 *
 * <p>It reuses {@link DetailView#formatValue} and {@link FormFields#FIELD_BY_NAME}, so a column shows
 * exactly what the same field shows on the detail page. A second formatter here would be a second
 * opinion about what a Type is called.
 */
public final class ListView {

    /** The table's column headings, beside the row builder so the two cannot fall out of step. */
    public static final List<String> CASE_LIST_COLUMNS =
        List.of("Reference", "Privacy", "Received", "Type", "State", "New forename");

    private ListView() {
    }

    /**
     * A row of the list table, with every cell already a string the template prints as-is.
     *
     * <p>{@code isPrivate} is rendered as a red tag, so the state is not carried by colour alone.
     */
    public record CaseListRow(
        String id,
        String reference,
        String privacy,
        boolean isPrivate,
        String dateReceived,
        String type,
        String state,
        String newForename) {
    }

    /** Builds one row per case, preserving the order given -- the domain decides the sort. */
    public static List<CaseListRow> buildCaseList(List<DeedPoll> cases) {
        return cases.stream()
            .map(record -> new CaseListRow(
                record.getId(),
                // Not run through formatValue: the reference is already a display string, so formatting
                // it would only risk changing it. It is nullable in the schema even though createDeedPoll
                // always allocates one -- a seeded or imported row need not have -- so the placeholder is
                // possible here, and a reference-less case showing as such is the honest rendering. The
                // row still links.
                record.getDisplayReference() == null ? DetailView.NOT_PROVIDED : record.getDisplayReference(),
                format("privacy", record.getPrivacy()),
                record.getPrivacy() == Privacy.Private,
                format("dateReceived", record.getDateReceived()),
                format("type", record.getType()),
                format("state", record.getState()),
                format("newForename", record.getNewForename())))
            .toList();
    }

    /**
     * Formats one cell by looking its field up in the shared metadata.
     *
     * <p>A name absent from {@link FormFields#FIELD_BY_NAME} would silently stringify, so it falls back
     * to a plain string only for a genuinely unset value and otherwise trusts the metadata -- every name
     * used above is on a form tab, and a test pins that.
     */
    private static String format(String name, Object raw) {
        FieldDef field = FormFields.FIELD_BY_NAME.get(name);
        if (field == null) {
            return raw == null || "".equals(raw) ? DetailView.NOT_PROVIDED : String.valueOf(raw);
        }
        return DetailView.formatValue(field, raw);
    }
}
