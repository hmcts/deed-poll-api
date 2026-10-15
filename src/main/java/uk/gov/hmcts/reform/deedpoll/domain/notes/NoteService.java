package uk.gov.hmcts.reform.deedpoll.domain.notes;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditValue;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
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
 * <p>One rule here is not in the reference's service: deletion requires the Administrator role. The reference
 * expressed that as middleware in front of its route, and there is no route layer to hang it on in the domain,
 * so {@link #removeNote} takes the caller and refuses. Editing stays open to both roles, which is the split the
 * business asked for rather than an oversight.
 *
 * <p>The service returns entities, not view models, exactly as the reference does. Formatting is
 * {@link NotesView}'s job, and the caseworker API will map to its own DTOs.
 *
 * <p><strong>Every write records one audit event</strong> in its own transaction, with the note's text before and
 * after -- including the notes other services write as a side effect of theirs, because a note is a write to the
 * case whoever asked for it.
 */
@Service
public class NoteService {

    private static final String NOTE = "note";

    private final DeedPollRepository deedPolls;
    private final DeedPollNoteRepository notes;
    private final AuditTrail audit;

    public NoteService(DeedPollRepository deedPolls, DeedPollNoteRepository notes, AuditTrail audit) {
        this.deedPolls = deedPolls;
        this.notes = notes;
        this.audit = audit;
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
        DeedPollNote saved = notes.save(note);
        record(AuditAction.NOTE_CREATED, AuditActor.of(author), saved, List.of(NOTE), null, saved.getNote());
        return saved;
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
        final String before = note.getNote();
        dto.note().ifPresent(note::setNote);
        note.setModifiedBy(author.author());
        // Stamped here rather than left to @PreUpdate. Hibernate only fires @PreUpdate when its dirty check
        // finds a changed property, so an empty patch by the note's own last author would change nothing,
        // issue no UPDATE and leave modifiedOn where it was -- and NotesView derives the grid's
        // "unmodified" flag from createdOn.equals(modifiedOn), so an edited note would keep reporting that
        // it had never been touched. The reference's `prisma.update` writes @updatedAt unconditionally, and
        // UpdateNoteRequest promises the same ("an empty patch re-stamps modifiedBy").
        note.setModifiedOn(AuditClock.now());
        DeedPollNote saved = notes.save(note);
        record(AuditAction.NOTE_UPDATED, AuditActor.of(author), saved,
               dto.note().isPresent() ? List.of(NOTE) : List.of(), before, saved.getNote());
        return saved;
    }

    /**
     * Deletes a note. <strong>Administrators only.</strong>
     *
     * <p>Exposed since 2026-09-08 by {@code DELETE /caseworker/cases/&#123;id&#125;/notes/&#123;noteId&#125;}. This
     * comment previously said no endpoint exposed it and that the domain kept it so the next caller would not
     * invent an unscoped one -- which is exactly what happened, and the route reuses this rather than adding a
     * second path to the same delete.
     *
     * <p><strong>The role check is here rather than on the route.</strong> The business splits editing from
     * deleting -- a Basic User may create, view and edit notes, and only deletion is restricted -- and notes
     * are the audit record of what happened on a case. Enforcing it in the domain means the rule holds for
     * every caller, including a citizen surface or a batch job that does not exist yet, rather than only for
     * the one HTTP route that does. There is deliberately <em>no</em> ungated overload: a caller who could
     * reach a two-argument {@code removeNote} would skip the check entirely, so unlike
     * {@link #createNote(String, CreateNoteInput)} and {@link #updateNote(String, String, UpdateNoteInput)} --
     * whose short forms only mean "no author recorded" -- there is exactly one way to delete a note.
     *
     * <p><strong>Refuse first, then resolve.</strong> The check runs before the lookup so that a caller
     * without the role gets the same 403 whether or not the note exists; looking it up first would let them
     * tell an existing note from a missing one by the status code. Authorship is not consulted: "only the
     * author may delete" is a third rule the business did not choose.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError if the caller is not an Administrator
     * @throws NotFoundError if there is no such note, or it belongs to another case
     */
    @Transactional
    public void removeNote(String deedPollId, String id, CurrentUser caller) {
        caller.require(Capability.DELETE_NOTE);
        DeedPollNote note = findNote(deedPollId, id);
        notes.delete(note);
        record(AuditAction.NOTE_DELETED, AuditActor.of(caller), note, List.of(NOTE), note.getNote(), null);
    }

    /** A note has one field: an event names it when the write did, and carries its before and after. */
    private void record(AuditAction action, AuditActor actor, DeedPollNote note, List<String> fields,
                        String before, String after) {
        audit.record(AuditEntry.of(action, actor, AuditEntities.NOTE, note.getId(), note.getDeedPoll().getId())
                         .withFields(fields),
                     List.of(AuditValue.of(NOTE, before, after)));
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }
}
