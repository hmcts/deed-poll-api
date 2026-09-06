package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One case note, as the caseworker surface publishes it.
 *
 * <p>Every column of {@code deedpoll_notes} except the foreign key, which is the path segment the note was
 * fetched under and so would only repeat itself. The four audit values are all published because the notes
 * grid pairs each timestamp with its author -- see {@code NotesView} -- and because "who changed this, when"
 * is the question a note history is read for.
 *
 * <p>The parent case is deliberately absent rather than nested: a note carrying its case, which carries its
 * notes, is a cycle a serialiser has to be told about, and the caller already knows which case it asked for.
 *
 * @param id the note id, which is what an amend addresses
 * @param note the note text as stored, already trimmed on the way in
 * @param createdBy the name recorded when the note was written, or null for a note that predates the column
 * @param modifiedBy the name recorded for the last amendment, or null if never amended by a named caller
 * @param createdOn when the note was written
 * @param modifiedOn when it was last amended -- equal to createdOn for a note never edited
 */
public record NoteResponse(
    String id,
    String note,
    String createdBy,
    String modifiedBy,
    LocalDateTime createdOn,
    LocalDateTime modifiedOn) {

    /** One stored note as the response carries it. */
    public static NoteResponse from(DeedPollNote note) {
        return new NoteResponse(
            note.getId(),
            note.getNote(),
            note.getCreatedBy(),
            note.getModifiedBy(),
            note.getCreatedOn(),
            note.getModifiedOn());
    }

    /** A case's notes, preserving the order the domain fetched them in. */
    public static List<NoteResponse> from(List<DeedPollNote> notes) {
        return notes.stream().map(NoteResponse::from).toList();
    }
}
