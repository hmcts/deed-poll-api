package uk.gov.hmcts.reform.deedpoll.reference;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conformance-harness fixture loader.
 *
 * <p>Ported from {@code reference/ts/conformance/artifacts.ts}. Reads the source-of-truth
 * Dataverse artifacts vendored at {@code reference/dataverse_tables/} and exposes them as typed
 * data the conformance tests assert against. This is the ONLY class that reads those files, so
 * re-pointing at them (or swapping their format) stays a one-file change.
 *
 * <p>No HTML-parsing dependency is on the test classpath, so the {@code columns.html} /
 * {@code relationships.html} extracts are parsed with a small regex over the flat, regular
 * {@code <td>} grid the export produces. If those files ever gain nested markup this parser must
 * be revisited -- {@link #parseGridRows} throws when a row does not have the expected cell count,
 * so drift fails loudly rather than silently dropping rows.
 *
 * <p><strong>KNOWN LIMITATION -- the {@code deedpoll/columns.html} extract is alphabetically
 * TRUNCATED:</strong> it declares "Total columns: 70" but lists only A--R (ends at "Refund
 * Reference"), so Type / State / Urgency / Section / Retain Permanently and other post-R columns
 * are absent. The conformance tests therefore assert the FORWARD direction only -- every column
 * present in the artifact must map to a ported field -- and never treat the artifact as a complete
 * column set. See the truncation guard in
 * {@code uk.gov.hmcts.reform.deedpoll.conformance.DataverseSchemaConformanceTest}.
 *
 * <p>Two deviations from the reference, both forced by the move into this repo:
 *
 * <ul>
 *   <li>The reference walks up the filesystem looking for {@code .business_context/dataverse_tables}
 *       because it runs from several working directories. Here the artifacts are vendored onto the
 *       test classpath, so they are read from there -- the same "location-independent" property, by
 *       the mechanism this build already has.</li>
 *   <li>{@link #businessRules} is an addition. The reference only ever asks whether a table's rules
 *       file is empty; the state-cascade test here asserts an error message verbatim against the
 *       rules file rather than against a transcription of it, which needs the text.</li>
 * </ul>
 */
public final class DataverseArtifacts {

    private static final String ROOT = "/reference/dataverse_tables/";

    private static final Pattern TABLE_BODY =
        Pattern.compile("<tbody>(.*?)</tbody>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ROW =
        Pattern.compile("<tr>(.*?)</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CELL =
        Pattern.compile("<td>(.*?)</td>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern COLUMN_TOTAL =
        Pattern.compile("Total columns:</strong>\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern NO_BUSINESS_RULES =
        Pattern.compile("no business rules for this table", Pattern.CASE_INSENSITIVE);
    private static final Pattern RULES_TABLE_HEADER =
        Pattern.compile("(?im)\\|\\s*business rule name\\s*\\|.*$");
    private static final Pattern REAL_RULE_ROW =
        Pattern.compile("(?m)^\\|\\s*[A-Za-z][^|]*\\|");

    /** Columns grid: display, name, type, managed, customized, customizable, required, searchable. */
    private static final int COLUMN_CELLS = 8;

    /** Relationships grid: display, name, relatedTable, type, managed, customized, customizable. */
    private static final int RELATIONSHIP_CELLS = 7;

    private DataverseArtifacts() {
    }

    /**
     * The five exported tables, by their artifact directory name.
     *
     * <p>Four of the five directories misspell "deedpoll" as "deeppoll". That is the export's own
     * spelling and it is what the vendored directories are called, so it is reproduced rather than
     * corrected. {@link #toString()} yields the directory name so a failing dynamic test names the
     * table the way the reference's {@code describe(table)} does.
     */
    public enum TableDir {
        DEEDPOLL("deedpoll"),
        NOTE("deeppoll_note"),
        WITNESS("deeppoll_witness"),
        SUPPORTING_DOCUMENT("deeppoll_supporting_document"),
        SUPPORTING_DOCUMENT_TYPE("deeppoll_supporting_document_type");

        private final String directory;

        TableDir(String directory) {
            this.directory = directory;
        }

        /** The vendored directory this table's artifacts live in. */
        public String directory() {
            return directory;
        }

        @Override
        public String toString() {
            return directory;
        }
    }

    /**
     * A parsed row of a Dataverse {@code columns.html} grid.
     */
    public record ColumnRow(String displayName, String logicalName, String dataType, String required) {
    }

    /**
     * A parsed row of a {@code relationships.html} grid.
     */
    public record RelationshipRow(String displayName, String relationshipName,
                                  String relatedTable, String relationshipType) {
    }

    /** Every column the table's extract lists, in the order it lists them. */
    public static List<ColumnRow> loadColumns(TableDir table) {
        return parseGridRows(read(table, "columns.html"), COLUMN_CELLS).stream()
            .map(cells -> new ColumnRow(cells.get(0), cells.get(1), cells.get(2), cells.get(6)))
            .toList();
    }

    /** Every relationship the table's extract lists, in the order it lists them. */
    public static List<RelationshipRow> loadRelationships(TableDir table) {
        return parseGridRows(read(table, "relationships.html"), RELATIONSHIP_CELLS).stream()
            .map(cells -> new RelationshipRow(cells.get(0), cells.get(1), cells.get(2), cells.get(3)))
            .toList();
    }

    /** The declared "Total columns: N" figure, for the truncation sanity-check; null when absent. */
    public static Integer declaredColumnTotal(TableDir table) {
        Matcher matcher = COLUMN_TOTAL.matcher(read(table, "columns.html"));
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    /**
     * The raw {@code business_rules.md} of a table.
     */
    public static String businessRules(TableDir table) {
        return read(table, "business_rules.md");
    }

    /**
     * True when a table's {@code business_rules.md} records no rules.
     *
     * <p>The four child tables all carry the sentinel "There [are] no business rules for this table"
     * plus an empty rules table (a single {@code | - | - |} row). Asserting this pins the fact that
     * nothing was to be ported for them -- if a future artifact adds a rule, this flips false and
     * forces someone to port it.
     */
    public static boolean hasNoBusinessRules(TableDir table) {
        String markdown = businessRules(table);
        boolean sentinel = NO_BUSINESS_RULES.matcher(markdown).find();
        // The rules table body is only the placeholder row (dashes), i.e. no real rule. The header
        // row is removed first, since it is itself a row starting with a letter.
        String withoutHeader = RULES_TABLE_HEADER.matcher(markdown).replaceFirst("");
        boolean onlyPlaceholderRows = !REAL_RULE_ROW.matcher(withoutHeader).find();
        return sentinel && onlyPlaceholderRows;
    }

    /**
     * Extracts the {@code <td>} cells of every {@code <tr>} in the first {@code <tbody>}, grouped
     * per row. Throws if any row does not have exactly {@code expectedCells} cells, so a format
     * change cannot silently drop or misalign columns.
     */
    private static List<List<String>> parseGridRows(String html, int expectedCells) {
        Matcher body = TABLE_BODY.matcher(html);
        String contents = body.find() ? body.group(1) : "";

        List<List<String>> rows = new ArrayList<>();
        Matcher row = ROW.matcher(contents);
        while (row.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cell = CELL.matcher(row.group(1));
            while (cell.find()) {
                cells.add(cell.group(1).trim());
            }
            if (cells.size() != expectedCells) {
                throw new IllegalStateException(
                    "Expected %d cells per row but found %d: %s".formatted(
                        expectedCells, cells.size(), row.group(1)));
            }
            rows.add(List.copyOf(cells));
        }
        return rows;
    }

    private static String read(TableDir table, String file) {
        String resource = ROOT + table.directory() + "/" + file;
        try (InputStream in = DataverseArtifacts.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException(
                    "The vendored Dataverse artifact is missing from the test classpath: " + resource
                        + ". These are the source-of-truth exports the conformance harness asserts "
                        + "against; without them the coverage assertions cannot run.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }
}
