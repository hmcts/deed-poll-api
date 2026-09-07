package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.List;

/**
 * Nested CRUD for case notes.
 *
 * <p>Ported from {@code reference/ts/notes/service.ts}. Every operation is scoped to a parent case, so a
 * note can never be read or mutated outside its case. The parent's existence is checked on create -- the
 * foreign key would fail anyway, but a 404 is clearer than a 500.
 *
 * <p><strong>How the reference's belt-and-braces scoping ports.</strong> The reference applies the scope
 * twice on the write paths: a {@code findFirst} guard that produces the {@code NotFoundError}, and
 * {@code deedPollId} in the {@code where} of the {@code update} / {@code delete} that follow -- because
 * with only the guard, cross-case safety would be a convention one line long that a later method could
 * omit. Here the write goes through the entity that guard loaded, and that entity was fetched by id
 * <em>and</em> parent, so an unscoped write is not expressible at all: there is no path that writes a note
 * fetched by id alone. {@link DeedPollNoteRepository} has no such finder either.
 *
 * <p>The service returns entities, not view models, exactly as the reference does. Formatting is
 * {@link NotesView}'s job, and the caseworker API will map to its own DTOs.
 */
@Service
public class NoteService {

    private final DeedPollRepository deedPolls;
    private final DeedPollNoteRepository notes;

    public NoteService(DeedPollRepository deedPolls, DeedPollNoteRepository notes) {
        this.deedPolls = deedPolls;
        this.notes = notes;
    }

    /** Creates a note attributed to the service account. */
    @Transactional
    public DeedPollNote createNote(String deedPollId, CreateNoteInput dto) {
        return createNote(deedPollId, dto, NoteAuthor.NONE);
    }

    /**
     * Creates a note on a case.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional
    public DeedPollNote createNote(String deedPollId, CreateNoteInput dto, NoteAuthor author) {
        DeedPoll parent = requireCase(deedPollId);
        DeedPollNote note = new DeedPollNote();
        note.setDeedPoll(parent);
        note.setNote(dto.note());
        // Both audit columns are stamped on create: the source shows a Modified By on a note that has only
        // ever been created, so "modified" means "last written", not "edited since". The grid's
        // `unmodified` flag is what distinguishes the two, from the timestamps.
        note.setCreatedBy(author.author());
        note.setModifiedBy(author.author());
        return notes.save(note);
    }

    /**
     * Notes for a case, newest first.
     *
     * <p>The parent is checked, so a missing case is a 404 rather than an empty list. Filtering by
     * {@code deedPollId} alone -- which is what the prototype did -- answers an empty list for a case id
     * that does not exist and an empty list for a real case with no notes. Those are different facts and
     * only one of them is true, so a caller who mistypes a case id would be told the case has no notes.
     * The check costs one indexed primary-key lookup.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<DeedPollNote> findNotes(String deedPollId) {
        requireCase(deedPollId);
        return notes.findByDeedPollIdOrderByCreatedOnDesc(deedPollId);
    }

    /**
     * One note of one case.
     *
     * @throws NotFoundError if there is no such note, or it belongs to another case
     */
    @Transactional(readOnly = true)
    public DeedPollNote findNote(String deedPollId, String id) {
        return notes.findByIdAndDeedPollId(id, deedPollId)
            .orElseThrow(() -> new NotFoundError("Note " + id + " not found"));
    }

    /** Amends a note as the service account. */
    @Transactional
    public DeedPollNote updateNote(String deedPollId, String id, UpdateNoteInput dto) {
        return updateNote(deedPollId, id, dto, NoteAuthor.NONE);
    }

    /**
     * Amends a note.
     *
     * <p>{@code createdBy} is never touched: {@link UpdateNoteInput} cannot carry it, so an edit can only
     * ever move {@code modifiedBy} -- the same rule the case's own update path enforces by deleting
     * {@code createdBy} from the patch.
     *
     * @throws NotFoundError if there is no such note, or it belongs to another case
     */
    @Transactional
    public DeedPollNote updateNote(String deedPollId, String id, UpdateNoteInput dto, NoteAuthor author) {
        DeedPollNote note = findNote(deedPollId, id);
        dto.note().ifPresent(note::setNote);
        note.setModifiedBy(author.author());
        // Stamped here rather than left to @PreUpdate. Hibernate only fires @PreUpdate when its dirty check
        // finds a changed property, so an empty patch by the note's own last author would change nothing,
        // issue no UPDATE and leave modifiedOn where it was -- and NotesView derives the grid's
        // "unmodified" flag from createdOn.equals(modifiedOn), so an edited note would keep reporting that
        // it had never been touched. The reference's `prisma.update` writes @updatedAt unconditionally, and
        // UpdateNoteRequest promises the same ("an empty patch re-stamps modifiedBy").
        note.setModifiedOn(AuditClock.now());
        return notes.save(note);
    }

    /**
     * Deletes a note.
     *
     * <p>No endpoint exposes this -- the caseworker surface has no note delete -- but the reference has it
     * and the source grid does, so the domain keeps it rather than the next caller inventing an unscoped
     * one.
     *
     * @throws NotFoundError if there is no such note, or it belongs to another case
     */
    @Transactional
    public void removeNote(String deedPollId, String id) {
        notes.delete(findNote(deedPollId, id));
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }
}
