package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.api.KeyedRequestBody;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.notes.UpdateNoteInput;

/**
 * The body of {@code PATCH /caseworker/cases/&#123;id&#125;/notes/&#123;noteId&#125;}.
 *
 * <p>{@code createNoteSchema.partial()} in the reference: {@code note} is optional, and if present it gets the
 * create rule. An empty patch is accepted and is not quite a no-op -- it re-stamps {@code modifiedBy}, which is
 * the same thing an empty case patch does.
 *
 * <p><strong>An explicit {@code "note": null} is a 400, not an empty patch</strong>, and that is why this cannot
 * be a record of one nullable component: a record would make the two indistinguishable, so a client clearing a
 * note would silently get a success and an unchanged row. The note column is {@code NOT NULL}, so there is no
 * value for a null to mean -- see {@link UpdateNoteInput}, which has two states where the case's patch has
 * three, and {@link KeyedRequestBody} for the mechanism.
 */
public final class UpdateNoteRequest extends KeyedRequestBody {

    /**
     * The validated patch this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError attributed to {@code note} if it is present
     *     and not acceptable
     */
    public UpdateNoteInput toInput() {
        return NoteSchemas.parseUpdateNote(fields());
    }
}
