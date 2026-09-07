package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import org.springframework.http.HttpStatus;
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
 * Case notes: the four operations the caseworker surface has on them.
 *
 * <p>Nested under the case, so every id in the URL is part of the scope rather than decoration. Both handlers
 * that address one note pass <em>both</em> ids to the domain, which is the whole of the scoping rule: a note id
 * belonging to another case answers 404 instead of being read or amended through the wrong parent. The reference
 * records that passing only the note id compiles and quietly makes every note in the database reachable from any
 * case's URL, and {@code DeedPollNoteRepository} has no unscoped finder for exactly that reason.
 *
 * <p><strong>There is deliberately no note delete.</strong> The reference has one, admin-only, and it has no
 * consumer in {@code deed-poll-admin}'s data-access layer. {@code NoteService.removeNote} exists and is tested,
 * so exposing it later is a route rather than a design.
 *
 * <p>The author of a write is the authenticated caller and never the body. {@code CurrentUser.noteAuthor()}
 * supplies it, and it carries the name only: a note has no admin-only field, so nothing on this path should be
 * able to ask whether the caller is an admin. That is the reference's decision, asserted by a test of its own.
 *
 * <p>None of these routes is annotated as requiring authentication, and all four do:
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
}
