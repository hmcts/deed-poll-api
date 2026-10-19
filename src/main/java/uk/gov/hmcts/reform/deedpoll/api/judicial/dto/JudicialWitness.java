package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;

import java.util.List;

/**
 * One witness to the application, as a judge reads it (business decision 1 of 2026-10-19).
 *
 * <p>The witness's name, address and occupation, and whether they are the declarant -- what the caseworker
 * {@code WitnessResponse} publishes about the person -- and <strong>no id</strong>, for the reason
 * {@link JudicialCaseNote} has none: no judicial route addresses a witness, so an id would be a key published for
 * nothing. The two timestamps are the office's record-keeping and are left out with the rest of it.
 *
 * @param witnessName the witness's name as recorded
 * @param witnessAddress their address, one free-text field as the source form holds it
 * @param witnessOccupation their occupation
 * @param declarant whether this witness is the declarant
 */
public record JudicialWitness(String witnessName, String witnessAddress, String witnessOccupation,
                              boolean declarant) {

    /** One stored witness, narrowed to what a judge is shown. */
    public static JudicialWitness from(DeedPollWitness witness) {
        return new JudicialWitness(
            witness.getWitnessName(),
            witness.getWitnessAddress(),
            witness.getWitnessOccupation(),
            witness.isDeclarant());
    }

    /** The application's witnesses, in the order the domain fetched them -- oldest first. */
    public static List<JudicialWitness> from(List<DeedPollWitness> witnesses) {
        return witnesses.stream().map(JudicialWitness::from).toList();
    }
}
