package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

/**
 * The derived (non-stored) day counts added to every record on read.
 *
 * <p>Ported from the {@code ComputedFields} interface in {@code reference/ts/deed-poll/computed.ts}.
 * Both are {@code Integer} rather than {@code OptionalInt}: {@code null} here is a value the read
 * model carries out to JSON and onto a page as "Not provided", not an absence the caller has to
 * branch on -- which is the distinction that made {@code ReferenceNumbers.parse} an
 * {@link java.util.Optional}.
 */
public record ComputedFields(Integer daysReceivedToEnroled, Integer daysReceivedToReturned) {
}
