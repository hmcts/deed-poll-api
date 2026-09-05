package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The case header shown above the tabs on the detail page.
 *
 * <p>Ported from {@code reference/ts/deed-poll/case-header.ts}, which ports the Dynamics form header
 * ({@code power_app/screenshots/aplication-header.png}). The source shows five things next to the
 * record title: State, Modified By, Modified On and Created By, with "Deed Poll" as the entity
 * subtitle.
 *
 * <p>Two deliberate departures from the screenshot:
 *
 * <ul>
 *   <li>Type is added as a row. The Dynamics header omits it, but it is the field that drives every
 *       per-type rule in the form, so a caseworker should not have to open a tab to see it.</li>
 *   <li>The "- Saved" suffix and the header-collapse chevron are not ported. Both are Power Apps
 *       platform furniture: a server-rendered page has no dirty-form state and no collapsible header,
 *       so neither has anything to convey here.</li>
 * </ul>
 *
 * <p>The reference takes a {@code Partial<DeedPoll>}; this takes the entity, because the five fields it
 * reads are all stored ones -- unlike {@code DetailView}, which also needs derived columns and child
 * collections and therefore has to read a field map.
 */
public record CaseHeader(String entityLabel, List<CaseHeaderRow> rows) {

    /** The entity name, shown as the caption above the record title. */
    public static final String ENTITY_LABEL = "Deed Poll";

    /**
     * Placeholder for an absent header value.
     *
     * <p>Distinct from {@code DetailView.NOT_PROVIDED}: these fields are never asked of anybody, so an
     * empty one means the system did not record it (a row predating the audit columns), not that
     * someone left it blank.
     */
    public static final String NOT_RECORDED = "Not recorded";

    /** How the header timestamp reads: "13 May 2026". */
    private static final DateTimeFormatter HEADER_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    /**
     * The zone the header renders in.
     *
     * <p>Not UTC: {@code modifiedOn} is a UTC instant, and a save made at 09:44 British Summer Time must
     * read back as 9:44am rather than 8:44am. Naming the zone explicitly also keeps the output
     * independent of the server's local zone.
     */
    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    /**
     * One row of the header summary list.
     *
     * <p>{@code tag} renders the value as a govukTag rather than plain text, and is set for State only.
     */
    public record CaseHeaderRow(String key, String value, boolean tag) {
    }

    /**
     * A timestamp in GOV.UK style: "13 May 2026 at 9:44am".
     *
     * <p>The source renders {@code 13/05/2026 09:44}, but every other date in this app is
     * GOV.UK-formatted and a single numeric outlier in the header would be the odd one out.
     *
     * <p>The hour and meridiem are assembled rather than formatted from a pattern: a locale's day-period
     * text is CLDR data and has changed case and spacing between JDK releases, which would silently
     * move this away from GOV.UK style.
     */
    public static String formatTimestamp(Object raw) {
        LocalDateTime utc = Dates.toDateTime(raw);
        if (utc == null) {
            return NOT_RECORDED;
        }
        ZonedDateTime local = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(LONDON);
        int hour = local.getHour() % 12 == 0 ? 12 : local.getHour() % 12;
        String meridiem = local.getHour() < 12 ? "am" : "pm";
        return "%s at %d:%02d%s".formatted(HEADER_DATE.format(local), hour, local.getMinute(), meridiem);
    }

    /**
     * Builds the header view model for one case.
     *
     * <p>Every row is always present -- unlike the tab sections, which drop hidden fields. The header is
     * a fixed set of five facts about the record, and a header whose rows came and went by case would be
     * harder to read, not cleaner.
     */
    public static CaseHeader buildCaseHeader(DeedPoll record) {
        return new CaseHeader(ENTITY_LABEL, List.of(
            new CaseHeaderRow("Type", label("type", record.getType()), false),
            new CaseHeaderRow("State", label("state", record.getState()), true),
            new CaseHeaderRow("Modified By", author(record.getModifiedBy()), false),
            new CaseHeaderRow("Modified On", formatTimestamp(record.getModifiedOn()), false),
            new CaseHeaderRow("Created By", author(record.getCreatedBy()), false)));
    }

    /** Looks a stored enum member up in the shared label registry. */
    private static String label(String set, Object value) {
        if (value == null || "".equals(value)) {
            return NOT_RECORDED;
        }
        Map<String, String> labels = DynamicsValues.LABELS.get(set);
        String key = String.valueOf(value);
        return labels == null ? key : labels.getOrDefault(key, key);
    }

    /** A stored author name, or the placeholder for a record predating the columns. */
    private static String author(String raw) {
        return raw == null || raw.isBlank() ? NOT_RECORDED : raw.trim();
    }
}
