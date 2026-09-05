package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.FormFields.SelectOption;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * View model for one editable tab of a case, and the error summary that goes with it.
 *
 * <p>Ported from {@code reference/ts/deed-poll/edit-view.ts}. The template gets fields already
 * decorated with their value, their rule, their enum options and their date parts, so it needs no
 * per-field logic -- which is the only way a 25-field form stays legible. It lives in the domain because
 * every decision it makes (is this field required, may this caller edit privacy, which options does this
 * enum have) is a business rule, and none of it is HTTP.
 *
 * <p>{@code caseRecord} is the reference's {@code case}, which Java reserves. It is the only renamed
 * member here.
 */
public record EditView(
    Map<String, Object> caseRecord,
    TabRef tab,
    List<EditSection> sections,
    List<TabRef> tabs) {

    /** Who is editing, as far as field-level editability is concerned. */
    public record EditContext(boolean isAdmin) {
    }

    /** A tab as the form's own navigation needs it. */
    public record TabRef(String id, String label, TabDef.Subgrid subgrid) {
    }

    /**
     * A field, plus everything the template needs in order to render it without deciding anything.
     *
     * <p>The reference's {@code EditField} extends {@code FieldDef}; a record cannot extend one, so the
     * definition is held and its four commonly-read members are delegated. {@code locked} means editable
     * in principle but not by this caller -- currently only {@code privacy}.
     */
    public record EditField(
        FieldDef field,
        Object value,
        DateParts dateParts,
        FieldRule rule,
        List<SelectOption> options,
        boolean locked) {

        /** The field's name, and the name of its form input. */
        public String name() {
            return field.name();
        }

        /** The field's label. */
        public String label() {
            return field.label();
        }

        /** The control kind. */
        public FieldKind kind() {
            return field.kind();
        }

        /** The label set this field's values come from, or null. */
        public String enumSet() {
            return field.enumSet();
        }

        /** Whether the field renders disabled and is never saved. */
        public boolean readOnly() {
            return field.readOnly();
        }
    }

    /** One card of the edit form. */
    public record EditSection(String label, List<EditField> fields) {
    }

    /** One entry of the error summary: its text, and the anchor it links to when it has one. */
    public record SummaryEntry(String text, String href) {
    }

    /** Resolves a tab id from a URL segment, falling back to the first tab. */
    public static TabDef tabFor(Object id) {
        return FormFields.FORM_TABS.stream()
            .filter(tab -> tab.id().equals(id))
            .findFirst()
            .orElse(FormFields.FORM_TABS.get(0));
    }

    /** The non-read-only field names on a tab -- what an error summary may legitimately link to. */
    public static Set<String> editableFieldNames(TabDef tab) {
        Set<String> names = new LinkedHashSet<>();
        tab.sections().stream()
            .flatMap(section -> section.fields().stream())
            .filter(field -> !field.readOnly())
            .forEach(field -> names.add(field.name()));
        return Set.copyOf(names);
    }

    /**
     * Builds the error summary for a rejected save.
     *
     * <p>An entry links only when its field is actually on the submitted tab. The cross-field date rules
     * can produce an error addressed to a field on a different tab -- the Enroled+Returned rule is
     * addressed to {@code state}, which lives on the Application tab, so saving the Return tab yields a
     * real error about a field that is not on screen. A {@code #state} link there would be a dead anchor,
     * which is worse than no link at all: the message reads perfectly well on its own.
     */
    public static List<SummaryEntry> buildErrorSummary(List<FieldError> errors, TabDef tab) {
        Set<String> onTab = editableFieldNames(tab);
        return errors.stream()
            .map(error -> new SummaryEntry(
                error.message(),
                onTab.contains(error.field()) ? "#" + error.field() : null))
            .toList();
    }

    /** The view for a tab that is being rendered fresh, from stored values only. */
    public static EditView buildEditView(Map<String, Object> record, TabDef tab, EditContext ctx) {
        return buildEditView(record, tab, ctx, null);
    }

    /**
     * Decorates a tab's fields with everything needed to render them.
     *
     * <p>{@code submitted}, when non-null, is a rejected POST body replayed over the stored record, so a
     * re-rendered form shows what the caseworker typed rather than what is saved. An EMPTY map is a
     * replay -- a real rejected POST with everything blanked -- and must come back blank rather than
     * silently repopulated from storage; null is what means "not a replay". Read-only fields are excluded
     * from the replay: they were never editable, so the body has nothing to say about them and the stored
     * value is the truth.
     */
    public static EditView buildEditView(Map<String, Object> record,
                                         TabDef tab,
                                         EditContext ctx,
                                         Map<String, Object> submitted) {
        DeedPollType type = record.get("type") instanceof DeedPollType stored ? stored : null;
        Map<String, FieldRule> rules = DeedPollValidation.getFieldRules(
            type,
            Boolean.TRUE.equals(record.get("migratedRecord")),
            !Boolean.FALSE.equals(record.get("publishToLondonGazette")));
        boolean canEditPrivacy = FormPresentation.privacyEditable(type, new PresentationContext(ctx.isAdmin(), false));

        List<EditSection> sections = tab.sections().stream()
            .map(section -> new EditSection(section.label(), section.fields().stream()
                .map(field -> decorate(field, record, rules, canEditPrivacy, submitted))
                .toList()))
            .toList();

        return new EditView(
            record,
            new TabRef(tab.id(), tab.label(), tab.subgrid()),
            sections,
            FormFields.FORM_TABS.stream().map(t -> new TabRef(t.id(), t.label(), t.subgrid())).toList());
    }

    private static EditField decorate(FieldDef field,
                                      Map<String, Object> record,
                                      Map<String, FieldRule> rules,
                                      boolean canEditPrivacy,
                                      Map<String, Object> submitted) {
        boolean replaying = submitted != null && !field.readOnly();
        Object value = replaying ? submitted.get(field.name()) : record.get(field.name());

        DateParts dateParts = null;
        if (field.kind() == FieldKind.DATE) {
            dateParts = replaying
                ? DateParts.submittedDateParts(field.name(), submitted)
                : DateParts.splitDateParts(record.get(field.name()));
        }

        FieldRule rule = rules.getOrDefault(field.name(), new FieldRule(true, false));
        return new EditField(
            field,
            value,
            dateParts,
            rule,
            field.enumSet() == null ? List.of() : FormFields.enumOptions(field.enumSet()),
            // Privacy editability is governed by the caller's role, separately from the per-type rules.
            "privacy".equals(field.name()) && !canEditPrivacy);
    }
}
