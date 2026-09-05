package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/notes/service.test.ts}.
 *
 * <p><strong>Against a real database, not a mocked Prisma client</strong>, for the reason
 * {@code DeedPollServiceTest} gives: the reference asserts the arguments its mock was called with, because
 * that is all a TypeScript unit test can do, and the stored row is the claim those assertions stand for.
 * So each of the reference's shapes translates:
 *
 * <ul>
 *   <li>{@code expect(create).toHaveBeenCalledWith({data: ...})} becomes "the stored note carries those
 *       values".</li>
 *   <li>{@code expect(data).not.toHaveProperty("createdBy")} becomes "the stored createdBy is unchanged" --
 *       and here it is structural as well, since {@link UpdateNoteInput} cannot carry it.</li>
 *   <li>{@code expect(update).not.toHaveBeenCalled()} becomes "the stored note is unchanged".</li>
 * </ul>
 *
 * <p>Not {@code @Transactional}: the writes must really commit so the assertions read committed rows. The
 * tables are cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NoteServiceTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    @Autowired
    private NoteService noteService;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    @AfterEach
    void clearTables() {
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    /** A stored case to hang notes off. */
    private String storedCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", RECEIVED)))
            .deedPoll()
            .getId();
    }

    private static CreateNoteInput create(String text) {
        return NoteSchemas.parseCreateNote(Map.of("note", text));
    }

    private static UpdateNoteInput update(String text) {
        return NoteSchemas.parseUpdateNote(Map.of("note", text));
    }

    /** The stored note, read back rather than taken from the returned instance. */
    private DeedPollNote stored(String id) {
        return notes.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("creates a note scoped to its parent")
    void createsANoteScopedToItsParent() {
        String caseId = storedCase();

        DeedPollNote created = noteService.createNote(caseId, create("hello"), new NoteAuthor("Steve Newman"));

        DeedPollNote saved = stored(created.getId());
        assertThat(saved.getNote()).isEqualTo("hello");
        assertThat(saved.getDeedPoll().getId()).isEqualTo(caseId);
        // Both audit columns are stamped on create: the source shows a Modified By on a note that has only
        // ever been created, so "modified" means "last written", not "edited since".
        assertThat(saved.getCreatedBy()).isEqualTo("Steve Newman");
        assertThat(saved.getModifiedBy()).isEqualTo("Steve Newman");
    }

    @Test
    @DisplayName("attributes a create with no caller name to the service account")
    void attributesACreateWithNoCallerNameToTheServiceAccount() {
        DeedPollNote created = noteService.createNote(storedCase(), create("hello"));

        DeedPollNote saved = stored(created.getId());
        assertThat(saved.getCreatedBy()).isEqualTo("# svc_HMCTS_LCPT_Dev");
        assertThat(saved.getModifiedBy()).isEqualTo("# svc_HMCTS_LCPT_Dev");
    }

    @Test
    @DisplayName("moves modifiedBy on update without touching createdBy")
    void movesModifiedByOnUpdateWithoutTouchingCreatedBy() {
        // An edit must never rewrite who created a note -- the same rule the case's own update enforces by
        // deleting createdBy from the patch. Here it is structural: UpdateNoteInput cannot carry createdBy,
        // so the patch has no way to express it.
        String caseId = storedCase();
        String noteId = noteService.createNote(caseId, create("original"), new NoteAuthor("Steve Newman")).getId();

        noteService.updateNote(caseId, noteId, update("amended"), new NoteAuthor("Jordan Blake"));

        DeedPollNote saved = stored(noteId);
        assertThat(saved.getNote()).isEqualTo("amended");
        assertThat(saved.getModifiedBy()).isEqualTo("Jordan Blake");
        assertThat(saved.getCreatedBy()).isEqualTo("Steve Newman");
    }

    @Test
    @DisplayName("404s updating a note that belongs to another case, before writing")
    void refusesToUpdateANoteThatBelongsToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String noteId = noteService.createNote(owner, create("original")).getId();

        assertThatThrownBy(() -> noteService.updateNote(other, noteId, update("hijacked")))
            .isInstanceOf(NotFoundError.class);

        assertThat(stored(noteId).getNote()).isEqualTo("original");
    }

    @Test
    @DisplayName("404s creating a note on a missing case")
    void refusesToCreateANoteOnAMissingCase() {
        assertThatThrownBy(() -> noteService.createNote("nope", create("x")))
            .isInstanceOf(NotFoundError.class);

        assertThat(notes.count()).isZero();
    }

    @Test
    @DisplayName("lists notes newest first for a case that exists")
    void listsNotesNewestFirst() {
        String caseId = storedCase();
        DeedPollNote first = noteService.createNote(caseId, create("first"));
        DeedPollNote second = noteService.createNote(caseId, create("second"));
        // Written explicitly rather than relying on two creates landing in different microseconds: the
        // claim under test is the order the query applies, not the clock's resolution.
        setCreatedOn(first.getId(), LocalDateTime.parse("2026-08-01T09:00:00"));
        setCreatedOn(second.getId(), LocalDateTime.parse("2026-08-09T09:00:00"));

        assertThat(noteService.findNotes(caseId))
            .extracting(DeedPollNote::getNote)
            .containsExactly("second", "first");
    }

    @Test
    @DisplayName("404s listing notes for a case that does not exist, rather than answering an empty list")
    void refusesToListNotesForACaseThatDoesNotExist() {
        // An empty list would say "this case has no notes", which is a different fact from "there is no
        // such case" -- and the wrong one for a mistyped id.
        assertThatThrownBy(() -> noteService.findNotes("nope")).isInstanceOf(NotFoundError.class);
    }

    @Test
    @DisplayName("returns an empty list for a real case with no notes")
    void returnsAnEmptyListForARealCaseWithNoNotes() {
        // The other half of the pair: the 404 above must not swallow the legitimately-empty case.
        assertThat(noteService.findNotes(storedCase())).isEmpty();
    }

    @Test
    @DisplayName("findNote 404s when the note belongs to another case")
    void findNoteRefusesANoteBelongingToAnotherCase() {
        String owner = storedCase();
        String other = storedCase();
        String noteId = noteService.createNote(owner, create("original")).getId();

        assertThatThrownBy(() -> noteService.findNote(other, noteId)).isInstanceOf(NotFoundError.class);
        assertThat(noteService.findNote(owner, noteId).getId()).isEqualTo(noteId);
    }

    @Test
    @DisplayName("removes after an existence check, scoped to the parent case")
    void removesAfterAnExistenceCheck() {
        String caseId = storedCase();
        String noteId = noteService.createNote(caseId, create("original")).getId();

        noteService.removeNote(caseId, noteId);

        assertThat(notes.findById(noteId)).isEmpty();
    }

    @Test
    @DisplayName("refuses to remove a note through another case, and leaves it stored")
    void refusesToRemoveANoteThroughAnotherCase() {
        // Not a reference block -- there, the delete's own `where` carries deedPollId and the guard is
        // asserted only on the read paths. Here the scope is what the delete is given, so it is worth
        // asserting that a wrong parent cannot delete somebody else's note.
        String owner = storedCase();
        String other = storedCase();
        String noteId = noteService.createNote(owner, create("original")).getId();

        assertThatThrownBy(() -> noteService.removeNote(other, noteId)).isInstanceOf(NotFoundError.class);

        assertThat(notes.findById(noteId)).isPresent();
    }

    @Test
    @DisplayName("an update omitting the text moves only the author")
    void anUpdateOmittingTheTextMovesOnlyTheAuthor() {
        // Not a reference block: the reference's schema test proves an empty patch parses, and this proves
        // what the service then does with one -- the absent-versus-present distinction reaching the write.
        String caseId = storedCase();
        String noteId = noteService.createNote(caseId, create("original"), new NoteAuthor("Steve Newman")).getId();

        noteService.updateNote(caseId, noteId, new UpdateNoteInput(Optional.empty()), new NoteAuthor("Jordan Blake"));

        DeedPollNote saved = stored(noteId);
        assertThat(saved.getNote()).isEqualTo("original");
        assertThat(saved.getModifiedBy()).isEqualTo("Jordan Blake");
    }

    /** Backdates a stored note, so an ordering assertion does not depend on the clock's resolution. */
    private void setCreatedOn(String noteId, LocalDateTime createdOn) {
        DeedPollNote note = stored(noteId);
        note.setCreatedOn(createdOn);
        notes.saveAll(List.of(note));
        notes.flush();
    }
}
