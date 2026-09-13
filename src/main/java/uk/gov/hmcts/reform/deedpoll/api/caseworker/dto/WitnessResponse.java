package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One witness to a deed poll, as the caseworker surface publishes it.
 *
 * <p>Every column of {@code deedpoll_witness} except the foreign key, for the reason
 * {@link NoteResponse} gives. There are no author columns on that table -- only the two timestamps -- so
 * attribution for a witness change is the case's own {@code modifiedBy} plus the declaration on file, and
 * this DTO cannot pretend otherwise.
 *
 * <p><strong>Read-only for the whole of plan 01, and no longer.</strong> Witnesses arrived nested inside a
 * case and the detail page rendered them, with nothing to create or amend one -- which was the only place
 * on this surface where data was readable and not writable. {@link CreateWitnessRequest} and
 * {@link UpdateWitnessRequest} now sit beside this record, and it is the response to the four routes that
 * answer with a witness. The delete answers 204 with no body, so it has no response DTO -- there is nothing
 * meaningful to return once the row is gone.
 *
 * @param id the witness id
 * @param witnessName the witness's name as recorded
 * @param witnessAddress their address, held as one free-text field exactly as the source form does
 * @param witnessOccupation their occupation
 * @param declarant whether this witness is the declarant, which the detail page tags
 * @param createdOn when the witness was recorded
 * @param modifiedOn when the record was last changed
 */
public record WitnessResponse(
    String id,
    String witnessName,
    String witnessAddress,
    String witnessOccupation,
    boolean declarant,
    LocalDateTime createdOn,
    LocalDateTime modifiedOn) {

    /** One stored witness as the response carries it. */
    public static WitnessResponse from(DeedPollWitness witness) {
        return new WitnessResponse(
            witness.getId(),
            witness.getWitnessName(),
            witness.getWitnessAddress(),
            witness.getWitnessOccupation(),
            witness.isDeclarant(),
            witness.getCreatedOn(),
            witness.getModifiedOn());
    }

    /** A case's witnesses, preserving the order the domain fetched them in. */
    public static List<WitnessResponse> from(List<DeedPollWitness> witnesses) {
        return witnesses.stream().map(WitnessResponse::from).toList();
    }
}
