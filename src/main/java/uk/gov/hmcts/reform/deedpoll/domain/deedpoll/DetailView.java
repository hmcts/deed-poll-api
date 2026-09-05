package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardBlock;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.CardColumn;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CardLayout.Columnable;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Read-only detail view model, built from the SAME field metadata that drives the edit forms.
 *
 * <p>Ported from {@code reference/ts/deed-poll/detail-view.ts}. That sharing is the point of the
 * module: the detail page used to hand-list six tabs while the form had nine, so address, minor and
 * declaration fields were editable but never readable back. Deriving both from
 * {@link FormFields#FORM_TABS} makes that class of drift impossible -- a field added there appears on
 * both pages.
 *
 * <p>Two filters are applied, mirroring what the edit form does: per-type field rules
 * ({@link DeedPollValidation#getFieldRules}) hide fields the Type does not use, and
 * {@link FormPresentation.TabVisibility} hides the three conditional tabs. A section whose every field
 * is hidden is dropped rather than rendered as a block of "Not provided" rows.
 *
 * <p>The record is read as a field map rather than as an entity, exactly as the reference reads a
 * {@code Record<string, unknown>}: the page shows derived columns and child collections alongside
 * stored ones, and {@link DeedPollFields#readAll} is what assembles all three into one map.
 */
public final class DetailView {

    /** Placeholder for an absent value, matching the reference's house style. */
    public static final String NOT_PROVIDED = "Not provided";

    /** GOV.UK date style: "1 January 2026". */
    private static final DateTimeFormatter GOV_UK_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private DetailView() {
    }

    /** One key/value pair of a card, already formatted for display. */
    public record DetailRow(String key, String value, CardColumn column) implements Columnable {
    }

    /**
     * One card of a tab.
     *
     * <p>{@code blocks} is the section's rows as an ordered sequence of card bands (two-column, or full
     * width). A view OF {@code rows}, not a second source of truth -- every row must appear in exactly
     * one band or it would be invisible on the page.
     */
    public record DetailSection(String label, List<DetailRow> rows, List<CardBlock<DetailRow>> blocks) {
    }

    /**
     * One tab of the detail page.
     *
     * <p>{@code subgrid} is set for child-entity tabs, which render a list rather than rows.
     * {@code empty} is true when the tab has nothing to show, and drives the "None recorded" message.
     */
    public record DetailTab(
        String id,
        String label,
        TabDef.Subgrid subgrid,
        List<DetailSection> sections,
        boolean empty) {
    }

    /** Formats one stored value for display, by field kind. */
    public static String formatValue(FieldDef field, Object raw) {
        if (raw == null || "".equals(raw)) {
            return NOT_PROVIDED;
        }

        return switch (field.kind()) {
            case DATE -> formatDate(raw);
            case BOOLEAN -> Boolean.TRUE.equals(raw) ? "Yes" : "No";
            case ENUM -> label(field.enumSet(), raw);
            case MULTISELECT -> formatMultiselect(field, raw);
            // A BigDecimal (refundAmount) stringifies correctly; 0 must survive.
            default -> String.valueOf(raw);
        };
    }

    /**
     * Builds the full detail view model.
     *
     * <p>Every tab the record's type and state allow, each with its populated rows. Subgrid tabs carry
     * no sections -- the template renders their child records directly off the case.
     */
    public static List<DetailTab> buildDetailTabs(Map<String, Object> record,
                                                  Map<String, FieldRule> rules,
                                                  FormPresentation presentation) {
        List<DetailTab> tabs = new ArrayList<>();

        for (TabDef tab : FormFields.FORM_TABS) {
            if (!tabVisible(tab, presentation)) {
                continue;
            }

            if (tab.subgrid() != null) {
                tabs.add(new DetailTab(
                    tab.id(),
                    tab.label(),
                    tab.subgrid(),
                    List.of(),
                    subgridCount(tab.subgrid(), record) == 0));
                continue;
            }

            List<DetailSection> sections = tab.sections().stream()
                .map(section -> buildSection(section, record, rules))
                .filter(Objects::nonNull)
                .toList();

            tabs.add(new DetailTab(tab.id(), tab.label(), null, sections, sections.isEmpty()));
        }

        return List.copyOf(tabs);
    }

    /**
     * Builds the display rows for one section, dropping only fields the type HIDES.
     *
     * <p>Empty values are kept and render "Not provided". This replaced the original skip-if-empty rule
     * when the page moved to two-column cards: the cards exist to replicate the Dynamics form so staff
     * find a given field in the same place every time, and a row that disappears when blank defeats
     * that -- it shifts every field below it. Per-type hidden fields are still dropped, so an adult case
     * shows no minor fields; that is a statement about the case type, not about this record.
     *
     * <p>Null when every field is hidden, which is the one remaining reason to drop a section.
     */
    private static DetailSection buildSection(SectionDef section,
                                              Map<String, Object> record,
                                              Map<String, FieldRule> rules) {
        List<DetailRow> rows = section.fields().stream()
            .filter(field -> visible(field, rules))
            .map(field -> new DetailRow(field.label(), formatValue(field, record.get(field.name())), field.column()))
            .toList();
        if (rows.isEmpty()) {
            return null;
        }
        return new DetailSection(section.label(), rows, CardLayout.splitIntoBlocks(rows));
    }

    /** Whether this field appears at all, per the type's field rules. */
    private static boolean visible(FieldDef field, Map<String, FieldRule> rules) {
        FieldRule rule = rules.get(field.name());
        return rule == null || rule.visible();
    }

    /** Which conditional tabs are visible, keyed by tab id. */
    private static boolean tabVisible(TabDef tab, FormPresentation presentation) {
        return switch (tab.id()) {
            case "referral" -> presentation.tabs().referralToMaster();
            case "enrolment" -> presentation.tabs().enrolment();
            case "return" -> presentation.tabs().returnTab();
            default -> true;
        };
    }

    /** Count of child rows backing a subgrid tab, for its empty state. */
    private static int subgridCount(TabDef.Subgrid subgrid, Map<String, Object> record) {
        Object list = record.get(subgrid.fieldName());
        return list instanceof Collection<?> children ? children.size() : 0;
    }

    /** GOV.UK-style "1 January 2026", or the placeholder when unparseable. */
    private static String formatDate(Object raw) {
        LocalDateTime date = Dates.toDateTime(raw);
        return date == null ? NOT_PROVIDED : GOV_UK_DATE.format(date);
    }

    private static String formatMultiselect(FieldDef field, Object raw) {
        List<Object> values = toList(raw);
        if (values.isEmpty()) {
            return NOT_PROVIDED;
        }
        return values.stream()
            .map(value -> label(field.enumSet(), value))
            .collect(Collectors.joining(", "));
    }

    /** Looks a value up in the named label set, falling back to the value itself. */
    private static String label(String enumSet, Object raw) {
        Map<String, String> labels = enumSet == null ? null : DynamicsValues.LABELS.get(enumSet);
        String key = String.valueOf(raw);
        if (labels == null) {
            return key;
        }
        return labels.getOrDefault(key, key);
    }

    /** A multiselect value as a list, whether it arrives as an array, a collection or a lone value. */
    private static List<Object> toList(Object raw) {
        if (raw instanceof Collection<?> collection) {
            return collection.stream().map(Object.class::cast).toList();
        }
        if (raw instanceof Object[] array) {
            return Stream.of(array).toList();
        }
        return List.of(raw);
    }
}
