package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteSchemas;

/**
 * The body of {@code POST /caseworker/cases/&#123;id&#125;/notes}.
 *
 * <p>One field, {@code note}, and it is required. The parent case is the path segment, so a body cannot move a
 * note to another case; the author comes from the caller's token, so a body cannot claim to be somebody else.
 * Both absences are structural rather than checked -- {@link CreateNoteInput} has nowhere to put either.
 *
 * <p>Keyed rather than declared even for one field, for two reasons. The trim and the emptiness rule belong to
 * {@link NoteSchemas}, so the API and the browser cannot disagree about what counts as a note; and a caller
 * sending {@code createdBy} is stripped rather than refused, which is the reference's
 * "{@code z.object()} strips unknown keys". See {@link KeyedRequestBody}.
 */
public final class CreateNoteRequest extends KeyedRequestBody {

    /**
     * The validated create input this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError attributed to {@code note} if the text is
     *     missing, not text, blank once trimmed, or over the limit
     */
    public CreateNoteInput toInput() {
        return NoteSchemas.parseCreateNote(fields());
    }
}
