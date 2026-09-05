package uk.gov.hmcts.reform.deedpoll.domain.notes;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseHeader;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * View model for the Notes subgrid on the case detail page.
 *
 * <p>Ported from {@code reference/ts/notes/notes-view.ts}, which ports the source's notes grid
 * ({@code power_app/screenshots/note_overview.png}). The source grid has seven columns: NoteID, Deed
 * Poll, Note, Modified By, Modified On, Created By, Created On. One is dropped, one is re-derived, and
 * the four audit columns are paired into two:
 *
 * <ul>
 *   <li><strong>NoteID</strong> keeps its label but shows a short code derived from the row's id, not the
 *       source's autonumber (which is not ported -- see {@code DeedPollNote} in
 *       {@code reference/deed-poll.prisma}). See {@link #shortCode} for why 8 characters is safe here.</li>
 *   <li><strong>Deed Poll</strong> repeats the {@code <h1>} of the page the grid is nested inside. The
 *       documents subgrid on the same page does not restate it either.</li>
 *   <li><strong>Created</strong> and <strong>Modified</strong> each render their timestamp with the author
 *       beneath it, rather than as four columns. Two columns instead of four keeps the Note itself readable
 *       width, and binding each time to its actor makes "who changed this, when" one glance. The timestamp
 *       leads because that is what a note history is scanned for; hence the model exposes all four values
 *       and lets the template pair them.</li>
 * </ul>
 *
 * <p>Also not ported, all Power Apps grid furniture with no server-rendered equivalent: the per-row
 * checkboxes (no bulk actions exist), Refresh (the page reload IS the refresh), Quick find (a filter box
 * for a list that is nearly always one screen), the column-sort chevrons and the horizontal scrollbar.
 * "Rows: 1" IS ported -- a count is genuinely useful -- but as a GOV.UK caption rather than a grid footer.
 *
 * @param rows the notes, formatted for display, newest-modified first
 * @param count the row count, shown as a caption above the table
 */
public record NotesView(List<NoteRow> rows, int count) {

    /**
     * One row of the notes grid, already formatted for display.
     *
     * @param id note id, for the amend link
     * @param code {@code id} as the short display code shown under the NoteID label
     * @param note the note text itself
     * @param unmodified true when this note has never been edited since creation, so the Modified column
     *     can say "Not edited" rather than repeating the creation pair. Compared on the stored instants,
     *     not the formatted strings, so two writes inside the same displayed minute still count as edited.
     */
    public record NoteRow(
        String id,
        String code,
        String note,
        String createdBy,
        String createdOn,
        String modifiedBy,
        String modifiedOn,
        boolean unmodified) {
    }

    /**
     * A note's id as a short human-readable code: the first 8 hex digits, uppercased and hyphenated into
     * two groups of four -- {@code 67EC-4D74}. It is the leading run of the same uuid that appears in the
     * note's URL, so the code and the id are visibly one thing.
     *
     * <p><strong>Display only. Never a lookup key.</strong> Eight hex digits is 32 bits, so codes are not
     * globally unique: across a whole notes table two rows will eventually share one (about a 25% chance
     * somewhere by 50,000 notes). That is harmless <em>here</em> because notes are only ever listed scoped
     * to a single case, and a clash is invisible unless both notes appear in the same list -- for a case
     * carrying 20 notes that is roughly 1 in 22 million, and 1 in 870,000 even at 100 notes. Decision
     * Steve's, 2026-08-10.
     *
     * <p>Two conditions this reasoning depends on. Break either and the short code stops being safe, so
     * change it back to the full id if you do:
     *
     * <ol>
     *   <li>Routing stays on the full uuid. If the code ever <em>resolved</em> a note, a table-wide
     *       collision would become a correctness bug rather than a cosmetic one.</li>
     *   <li>Notes are only ever displayed per-case. A cross-case notes list would put many codes in one
     *       view and the per-case arithmetic above would no longer apply.</li>
     * </ol>
     */
    public static String shortCode(String id) {
        String hex = id.replace("-", "").toUpperCase(Locale.UK);
        // A non-uuid id (nothing generates one, but the type does not forbid one) is returned uppercased
        // and ungrouped rather than padded into a fake code.
        if (hex.length() < 8) {
            return hex;
        }
        return hex.substring(0, 4) + "-" + hex.substring(4, 8);
    }

    /**
     * One note formatted for the amend page ({@code power_app/screenshots/note_amend_note.png}), which
     * shows the same four audit values the grid does -- the source puts them in its form header -- plus the
     * text to pre-fill and the code that titles the page.
     *
     * <p>Deliberately the same shape as a grid row, built by the same function, so the two pages cannot
     * format one note differently. {@code unmodified} lets the page make the same "Not edited" statement
     * the grid does rather than repeating the creation pair.
     */
    public static NoteRow buildNoteDetail(DeedPollNote note) {
        return buildNotesView(List.of(note)).rows().get(0);
    }

    /**
     * Builds the notes view model.
     *
     * <p>Ordered newest first: the source grid is sorted by Modified On descending (the arrow on that
     * column in the screenshot), and on a case that accumulates notes over months the latest is what a
     * caseworker wants first. {@code NoteService.findNotes} already applies an order in the query; sorting
     * here too keeps the model correct for any caller that does not -- including the detail page, whose
     * child collections are ordered ascending for the other tabs.
     *
     * <p>A note with no usable {@code modifiedOn} sorts last rather than being dropped or treated as the
     * epoch. The column is {@code NOT NULL}, so this is defence for a note built in memory or read through
     * a projection rather than for a stored row -- and the reference asserts it, because dropping a note
     * from a history is a worse failure than misplacing one.
     *
     * <p>The input list is not sorted in place: the caller's list, which on the detail page is a managed
     * Hibernate collection, is left as it was.
     */
    public static NotesView buildNotesView(List<DeedPollNote> notes) {
        List<NoteRow> rows = notes.stream()
            .sorted(Comparator.comparing(DeedPollNote::getModifiedOn,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(NotesView::row)
            .toList();
        return new NotesView(rows, rows.size());
    }

    private static NoteRow row(DeedPollNote note) {
        LocalDateTime created = note.getCreatedOn();
        LocalDateTime modified = note.getModifiedOn();
        return new NoteRow(
            note.getId(),
            shortCode(note.getId()),
            note.getNote(),
            author(note.getCreatedBy()),
            CaseHeader.formatTimestamp(created),
            author(note.getModifiedBy()),
            CaseHeader.formatTimestamp(modified),
            // The stored instants, not the rendered strings: two writes 20 seconds apart format to the
            // same minute, and comparing the strings would call that unmodified. The reference compares
            // milliseconds; these are the mapped timestamps themselves, which is the same claim at the
            // precision the column actually keeps.
            created != null && modified != null && created.equals(modified));
    }

    /**
     * A stored author name, or the placeholder for a note predating the audit columns.
     *
     * <p>The same rule {@code CaseHeader} applies to the header's authors, and the reference likewise
     * states it twice -- once per view model. Both read {@code NOT_RECORDED} from the header, so the
     * placeholder itself is shared even though the two-line helper is not.
     */
    private static String author(String raw) {
        return raw == null || raw.isBlank() ? CaseHeader.NOT_RECORDED : raw.trim();
    }
}
