package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CreateNoteRequest;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.NoteResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.UpdateNoteRequest;
import uk.gov.hmcts.reform.deedpoll.auth.CurrentUsers;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;

import java.util.List;

/**
 * Case notes: the five operations the caseworker surface has on them.
 *
 * <p>Nested under the case, so every id in the URL is part of the scope rather than decoration. Every handler
 * that addresses one note passes <em>both</em> ids to the domain, which is the whole of the scoping rule: a note
 * id belonging to another case answers 404 instead of being read, amended or deleted through the wrong parent.
 * The reference records that passing only the note id compiles and quietly makes every note in the database
 * reachable from any case's URL, and {@code DeedPollNoteRepository} has no unscoped finder for exactly that
 * reason.
 *
 * <p><strong>The delete was added 2026-09-08, and this class previously said there deliberately was not one.</strong>
 * That was true when written: the reference's delete is admin-only and nothing consumed it. What changed is that
 * {@code deed-poll-admin} is losing its own data-access layer to this service, and its note form has always had a
 * delete button -- so the operation now has a consumer, and without this route the cutover would have to drop a
 * working feature. {@code NoteService.removeNote} already existed and was already tested, so this is the route
 * that class predicted rather than a new design.
 *
 * <p>The author of a write is the authenticated caller and never the body. {@code CurrentUser.noteAuthor()}
 * supplies it, and it carries the name only: a note has no admin-only field, so nothing on this path should be
 * able to ask whether the caller is an admin. That is the reference's decision, asserted by a test of its own.
 *
 * <p><strong>The delete is not role-gated, and that is the same decision the rest of this surface took rather
 * than a new one.</strong> The reference restricts it to admins, but the only role name IDAM has confirmed for
 * this service is {@code caseworker-deedpoll-admin} and naming a non-admin caseworker role would be inventing
 * one -- the same reason {@code /caseworker/**} is authenticated but not role-gated throughout. When IDAM
 * onboarding confirms the role names, this route is one of the places to revisit; see {@code docs/RESUME.md}.
 *
 * <p>None of these routes is annotated as requiring authentication, and all five do:
 * {@code SecurityConfiguration} authenticates {@code anyRequest()} that is not on a short infrastructure
 * allowlist, so a route is protected by having been written rather than by having been remembered.
 */
@RestController
@RequestMapping("/caseworker/cases/{id}/notes")
public class CaseworkerNoteController {

    private final NoteService notes;

    public CaseworkerNoteController(NoteService notes) {
        this.notes = notes;
    }

    /**
     * A case's notes, newest first.
     *
     * <p><strong>404s for a case that does not exist, rather than answering an empty list.</strong> Filtering by
     * the parent id alone -- which the prototype did -- gives an empty list both for a mistyped case id and for a
     * real case with no notes, and only one of those is true, so a caseworker who mistyped would be told the case
     * has no notes. {@code NoteService.findNotes} checks the parent for that, which costs one primary-key lookup.
     *
     * <p>The nested notes on {@code GET /caseworker/cases/&#123;id&#125;} are the same rows in the other order --
     * the detail page renders them oldest-first inside a subgrid, and this list is read newest-first. Both are
     * the reference's, which is why neither is normalised to match the other here.
     */
    @GetMapping
    public List<NoteResponse> notes(@PathVariable("id") String id) {
        return NoteResponse.from(notes.findNotes(id));
    }

    /**
     * Adds a note to a case.
     *
     * <p>201 with the created note, as the reference's route sets explicitly. The text is trimmed before it is
     * stored and a blank one is refused, both in {@code NoteSchemas} rather than here, so the API and the SSR
     * form cannot disagree about what counts as a note.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse create(@PathVariable("id") String id, @RequestBody CreateNoteRequest request) {
        return NoteResponse.from(
            notes.createNote(id, request.toInput(), CurrentUsers.required().noteAuthor()));
    }

    /**
     * One note of one case.
     *
     * <p>No check for a missing note, for the reason the read controller gives: the domain reports absence by
     * throwing, so a branch here would be unreachable.
     */
    @GetMapping("/{noteId}")
    public NoteResponse note(@PathVariable("id") String id, @PathVariable("noteId") String noteId) {
        return NoteResponse.from(notes.findNote(id, noteId));
    }

    /**
     * Amends a note.
     *
     * <p>Only {@code modifiedBy} moves: {@code UpdateNoteInput} cannot carry {@code createdBy}, so an amendment
     * cannot rewrite who wrote a note. The reference deletes that key from its patch; here there is nothing to
     * delete.
     */
    @PatchMapping("/{noteId}")
    public NoteResponse update(@PathVariable("id") String id,
                               @PathVariable("noteId") String noteId,
                               @RequestBody UpdateNoteRequest request) {

        return NoteResponse.from(
            notes.updateNote(id, noteId, request.toInput(), CurrentUsers.required().noteAuthor()));
    }

    /**
     * Deletes a note.
     *
     * <p>204 with no body: there is nothing meaningful to return, and the deleted note is not it. Both ids are
     * passed to the domain, so a note addressed through the wrong case 404s rather than being deleted -- which
     * matters more here than on the reads, because an unscoped delete is destructive and silent.
     *
     * <p>No author is recorded. {@code removeNote} takes no {@code WriteContext} and the row is gone, so there is
     * nowhere to record one; a delete audit would be a schema change and a decision, not a parameter. The
     * reference does not record one either.
     *
     * <p>Deleting twice 404s on the second call rather than answering 204 again, because the domain reports
     * absence by throwing. Not idempotent in the strict HTTP sense, and consistent with the reads.
     */
    @DeleteMapping("/{noteId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id, @PathVariable("noteId") String noteId) {
        notes.removeNote(id, noteId);
    }
}
