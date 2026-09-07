package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseHeader;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.domain.notes.NotesView.buildNotesView;
import static uk.gov.hmcts.reform.deedpoll.domain.notes.NotesView.shortCode;

/**
 * Ports every test block in {@code reference/ts/notes/notes-view.test.ts}.
 *
 * <p>The notes are built in memory rather than stored: the view model reads a note's own fields and
 * nothing else, so a database would only make the same assertions slower. Timestamps are set explicitly
 * as UTC wall time, which is what the mapped column holds.
 */
class NotesViewTest {

    private static final String ID = "67ec4d74-2aa4-4c64-8793-1defd5773a87";

    /** 09:05 UTC in August, which is 10:05 British Summer Time. */
    private static final LocalDateTime AT = LocalDateTime.parse("2026-08-10T09:05:00");

    /**
     * A stored note, with the fields under test set by the caller. The id is a real-shaped uuid because
     * the view model derives the displayed code from it.
     */
    private static DeedPollNote note() {
        DeedPollNote note = new DeedPollNote();
        note.setId(ID);
        note.setNote("This is a note");
        note.setCreatedBy("Steve Newman");
        note.setModifiedBy("Steve Newman");
        note.setCreatedOn(AT);
        note.setModifiedOn(AT);
        return note;
    }

    @Nested
    @DisplayName("shortCode")
    class ShortCode {

        @Test
        @DisplayName("renders a uuid as two uppercase groups of four")
        void rendersAUuidAsTwoUppercaseGroupsOfFour() {
            assertThat(shortCode(ID)).isEqualTo("67EC-4D74");
        }

        @Test
        @DisplayName("takes the leading digits of the id, so the code is recognisable in the URL")
        void takesTheLeadingDigitsOfTheId() {
            // The code must be a visible prefix of the same uuid the note's URL carries -- that is the
            // whole reason for using hex rather than a denser encoding.
            String id = "40a61c90-b332-40b6-bbdf-4fd08a851f5d";
            String code = shortCode(id);

            assertThat(id.replace("-", "").toUpperCase(Locale.UK))
                .startsWith(code.replace("-", ""));
        }

        @Test
        @DisplayName("is exactly 8 significant characters plus one separator")
        void isExactlyEightSignificantCharactersPlusOneSeparator() {
            String code = shortCode("7c78958e-6921-42e3-9988-e297ebda6b5b");

            assertThat(code).hasSize(9);
            assertThat(code.replace("-", "")).hasSize(8);
        }

        @Test
        @DisplayName("is stable for the same id and differs for different ids")
        void isStableForTheSameIdAndDiffersForDifferentIds() {
            String a = ID;
            String b = "40a61c90-b332-40b6-bbdf-4fd08a851f5d";

            assertThat(shortCode(a)).isEqualTo(shortCode(a));
            assertThat(shortCode(a)).isNotEqualTo(shortCode(b));
        }

        @Test
        @DisplayName("does not pad a short id into a fake code")
        void doesNotPadAShortIdIntoAFakeCode() {
            // Nothing generates a non-uuid id, but the type permits one; it must not be dressed up as a
            // well-formed code.
            assertThat(shortCode("abc")).isEqualTo("ABC");
        }

        @Test
        @DisplayName("collides only when the leading 8 digits match -- the documented trade")
        void collidesOnlyWhenTheLeadingEightDigitsMatch() {
            // Two different notes CAN share a code. This is safe only because notes are listed per-case
            // and the code never resolves a note. If either changes, use the full id.
            assertThat(shortCode("67ec4d74-0000-0000-0000-000000000001"))
                .isEqualTo(shortCode("67ec4d74-ffff-ffff-ffff-ffffffffffff"));
            // Differing inside the first 8 digits is enough to distinguish them.
            assertThat(shortCode("67ec4d75-0000-0000-0000-000000000001"))
                .isNotEqualTo(shortCode("67ec4d74-0000-0000-0000-000000000001"));
        }
    }

    @Nested
    @DisplayName("buildNotesView")
    class BuildNotesView {

        @Test
        @DisplayName("formats one note into a display row")
        void formatsOneNoteIntoADisplayRow() {
            NotesView view = buildNotesView(List.of(note()));

            assertThat(view.count()).isEqualTo(1);
            assertThat(view.rows().get(0)).isEqualTo(new NotesView.NoteRow(
                ID,
                "67EC-4D74",
                "This is a note",
                "Steve Newman",
                "10 August 2026 at 10:05am",
                "Steve Newman",
                "10 August 2026 at 10:05am",
                true));
        }

        @Test
        @DisplayName("renders timestamps in Europe/London, not UTC")
        void rendersTimestampsInEuropeLondon() {
            // 09:05 UTC in August is 10:05 BST. A note saved at 10:05 must read back as 10:05am -- the
            // same rule the case header follows.
            NotesView view = buildNotesView(List.of(note()));

            assertThat(view.rows().get(0).createdOn()).isEqualTo("10 August 2026 at 10:05am");
        }

        @Test
        @DisplayName("renders Not recorded for a note predating the audit columns")
        void rendersNotRecordedForANotePredatingTheAuditColumns() {
            // The columns are nullable, and every note written before the migration has null in both. The
            // grid must not show "null".
            DeedPollNote note = note();
            note.setCreatedBy(null);
            note.setModifiedBy(null);

            NotesView.NoteRow row = buildNotesView(List.of(note)).rows().get(0);

            assertThat(row.createdBy()).isEqualTo(CaseHeader.NOT_RECORDED);
            assertThat(row.modifiedBy()).isEqualTo(CaseHeader.NOT_RECORDED);
        }

        @Test
        @DisplayName("treats a blank author name as not recorded")
        void treatsABlankAuthorNameAsNotRecorded() {
            DeedPollNote note = note();
            note.setCreatedBy("   ");

            assertThat(buildNotesView(List.of(note)).rows().get(0).createdBy())
                .isEqualTo(CaseHeader.NOT_RECORDED);
        }

        @Test
        @DisplayName("orders newest-modified first, whatever order it is given")
        void ordersNewestModifiedFirst() {
            DeedPollNote older = note();
            older.setId("older");
            older.setModifiedOn(LocalDateTime.parse("2026-08-01T09:00:00"));
            DeedPollNote newer = note();
            newer.setId("newer");
            newer.setModifiedOn(LocalDateTime.parse("2026-08-09T09:00:00"));

            // Ascending in, descending out -- the detail page's child collections are ordered ascending
            // for the other tabs, so this model cannot rely on its input.
            assertThat(buildNotesView(List.of(older, newer)).rows())
                .extracting(NotesView.NoteRow::id)
                .containsExactly("newer", "older");
        }

        @Test
        @DisplayName("sorts a note with no usable modifiedOn last, and never drops it")
        void sortsANoteWithNoUsableModifiedOnLast() {
            DeedPollNote dated = note();
            dated.setId("dated");
            DeedPollNote undated = note();
            undated.setId("undated");
            undated.setModifiedOn(null);

            List<NotesView.NoteRow> rows = buildNotesView(List.of(undated, dated)).rows();

            assertThat(rows).extracting(NotesView.NoteRow::id).containsExactly("dated", "undated");
            assertThat(rows.get(1).modifiedOn()).isEqualTo(CaseHeader.NOT_RECORDED);
        }

        @Test
        @DisplayName("flags a note edited since creation as modified")
        void flagsANoteEditedSinceCreationAsModified() {
            DeedPollNote note = note();
            note.setModifiedOn(LocalDateTime.parse("2026-08-10T11:30:00"));
            note.setModifiedBy("Jordan Blake");

            NotesView.NoteRow row = buildNotesView(List.of(note)).rows().get(0);

            assertThat(row.unmodified()).isFalse();
            assertThat(row.modifiedBy()).isEqualTo("Jordan Blake");
            assertThat(row.modifiedOn()).isEqualTo("10 August 2026 at 12:30pm");
        }

        @Test
        @DisplayName("compares instants, not formatted strings, when flagging modification")
        void comparesInstantsNotFormattedStrings() {
            // Two writes 20 seconds apart format to the same minute. Comparing the rendered strings would
            // call this unmodified; comparing instants does not.
            DeedPollNote note = note();
            note.setModifiedOn(LocalDateTime.parse("2026-08-10T09:05:20"));

            NotesView.NoteRow row = buildNotesView(List.of(note)).rows().get(0);

            assertThat(row.createdOn()).isEqualTo(row.modifiedOn());
            assertThat(row.unmodified()).isFalse();
        }

        @Test
        @DisplayName("returns an empty view for a case with no notes")
        void returnsAnEmptyViewForACaseWithNoNotes() {
            assertThat(buildNotesView(List.of())).isEqualTo(new NotesView(List.of(), 0));
        }

        @Test
        @DisplayName("does not mutate the list it is given")
        void doesNotMutateTheListItIsGiven() {
            DeedPollNote a = note();
            a.setId("a");
            a.setModifiedOn(LocalDateTime.parse("2026-08-01T09:00:00"));
            DeedPollNote b = note();
            b.setId("b");
            b.setModifiedOn(LocalDateTime.parse("2026-08-09T09:00:00"));
            List<DeedPollNote> input = new ArrayList<>(List.of(a, b));

            buildNotesView(input);

            assertThat(input).extracting(DeedPollNote::getId).containsExactly("a", "b");
        }

        @Test
        @DisplayName("buildNoteDetail formats a note exactly as the grid does")
        void buildNoteDetailFormatsANoteExactlyAsTheGridDoes() {
            // Not a reference block: the reference builds the amend page's model by calling
            // buildNotesView, and this pins that the two pages cannot format one note differently.
            DeedPollNote note = note();

            assertThat(NotesView.buildNoteDetail(note)).isEqualTo(buildNotesView(List.of(note)).rows().get(0));
        }
    }
}
