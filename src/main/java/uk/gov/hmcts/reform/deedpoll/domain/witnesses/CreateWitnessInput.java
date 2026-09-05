package uk.gov.hmcts.reform.deedpoll.domain.witnesses;

/**
 * Create payload for a witness or declarant ({@code deedpoll_witness}), already validated.
 *
 * <p>Ported from the {@code CreateWitnessInput} type in {@code reference/ts/witnesses/schemas.ts}. All
 * four fields are required, because all four source columns are {@code NOT NULL} -- unlike the case
 * itself, a witness is not logged incrementally: it is entered from a signed declaration that already
 * carries every value.
 *
 * <p>{@code declarant} distinguishes a declarant from an ordinary witness, so one row type covers both.
 * It is a primitive {@code boolean} rather than a {@link Boolean}: the column has no null and the schema
 * refuses one, so a null here would be a state the database cannot hold.
 *
 * <p>Only reachable through {@link WitnessSchemas#parseCreateWitness}, which is what makes "unknown keys
 * are stripped" structural rather than a rule someone has to remember -- a caller sending
 * {@code deedPollId} or {@code createdOn} has nowhere for it to land. The parent id is an argument to
 * {@link WitnessService#createWitness}, not a payload field, so a witness cannot be posted onto a case
 * other than the one in the URL.
 *
 * <p>Values are stored as submitted, not trimmed. The reference's schema declares only
 * {@code z.string().max(...)} on each field, and adding a trim here would mean this port stores something
 * different from the specification it is asserting against -- see {@link WitnessSchemas} for where the
 * notes port's trim came from and why it does not generalise.
 */
public record CreateWitnessInput(
    String witnessName,
    String witnessAddress,
    String witnessOccupation,
    boolean declarant) {
}
