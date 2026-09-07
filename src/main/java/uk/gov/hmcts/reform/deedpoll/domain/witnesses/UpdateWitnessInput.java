package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

import java.util.Optional;

/**
 * Update payload for a witness: the same rules as create, applied only to the fields that are present.
 *
 * <p>Ported from the {@code UpdateWitnessInput} type in {@code reference/ts/witnesses/schemas.ts}, which
 * is {@code createWitnessSchema.partial()}.
 *
 * <p>{@link Optional} throughout rather than nullable fields, for the reason {@code UpdateNoteInput}
 * gives: absent and present are the only two states this patch has, because all four columns are
 * {@code NOT NULL} and {@link WitnessSchemas#parseUpdateWitness} refuses an explicit null rather than
 * storing one. Nullable fields would collapse "leave this alone" and "clear this" into the same value,
 * and the reference tests assert those stay distinct.
 *
 * <p>{@code deedPollId} is not expressible here, which is how a patch is prevented from moving a witness
 * to another case -- structurally, rather than by a rule in the service that a later method could forget.
 */
public record UpdateWitnessInput(
    Optional<String> witnessName,
    Optional<String> witnessAddress,
    Optional<String> witnessOccupation,
    Optional<Boolean> declarant) {

    /** An empty patch, which is accepted and changes nothing. */
    public static final UpdateWitnessInput EMPTY = new UpdateWitnessInput(
        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    /** True when the patch names no field at all, so a caller can tell a no-op from a change. */
    public boolean isEmpty() {
        return witnessName.isEmpty()
            && witnessAddress.isEmpty()
            && witnessOccupation.isEmpty()
            && declarant.isEmpty();
    }
}
