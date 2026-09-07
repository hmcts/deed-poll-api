package uk.gov.hmcts.reform.deedpoll.domain;

import java.util.List;

/**
 * The caller's input is not acceptable -- the domain rejected it, not the transport.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it rather than
 * {@code InvalidInputException}: the name is the vocabulary every ported module and ported test
 * says, and "Error" here is the domain's word for a rejection, not Java's {@link Error}. It extends
 * {@link RuntimeException}, so an unrecognised failure still reaches a generic handler.
 *
 * <p>It carries the per-field failures as well as a summary message, and that is not decoration:
 * the reference records that a message-only error quietly downgrades every field-level validation
 * failure to an unattributed banner, and the forms render {@code errors} against the individual
 * inputs. The list defaults to empty rather than null so a caller doing {@code errors().forEach}
 * never has to null-check.
 *
 * <p>Deliberately not called {@code ValidationError}: the reference's
 * {@code deed-poll-validation.ts} already uses that name for a per-field result that is collected
 * and returned rather than thrown.
 *
 * <p>The reference declares four more errors alongside this one -- {@link NotFoundError},
 * {@link ForbiddenError}, {@link UnauthorizedError} and {@link ConflictError} -- and a
 * {@code zodErrorToFieldErrors} helper. Each arrived with the ported module that throws it, so the
 * vocabulary is a record of what the service actually refuses rather than a set of classes built for
 * an imagined caller. All five are now ported; only the helper has no equivalent, because Bean
 * Validation replaces Zod.
 *
 * <p>{@code DomainErrorHandler} maps this to a 400 and publishes {@link #errors()} alongside the
 * message, which is the whole reason the list is carried rather than flattened into the text.
 */
public class InvalidInputError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<FieldError> errors;

    public InvalidInputError(String message) {
        this(message, List.of());
    }

    public InvalidInputError(String message, List<FieldError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    /** The per-field failures, never null and never modifiable. */
    public List<FieldError> errors() {
        return errors;
    }
}
