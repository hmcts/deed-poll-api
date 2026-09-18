package uk.gov.hmcts.reform.deedpoll.domain;

import java.util.List;

/**
 * The request is well-formed and permitted, and the record it is about is missing something the operation
 * cannot supply.
 *
 * <p><strong>Not a port.</strong> {@code reference/ts/errors.ts} declares five errors and this is not one of
 * them, because the reference sent nothing: the first operation that needs a sixth answer is sending an email,
 * where the recipient is a nullable column on the case rather than a field of the request.
 * {@code DomainErrorHandler} maps it to a 422.
 *
 * <p><strong>Why it is not one of the five.</strong> It is not {@link InvalidInputError}, because there is
 * nothing in the payload to correct -- the caseworker composed a perfectly good email and the case has no
 * address to send it to. It is not {@link ConflictError} either, and the difference is what a client does next:
 * a 409 here means "this will succeed unchanged once the conflicting data is gone", which invites a retry, and
 * a retry of a send to an applicant with no email address will fail identically for ever. The fix is somewhere
 * else entirely -- the case -- and 422 is the status that says the request was understood and could not be
 * acted on.
 *
 * <p>It carries {@link FieldError}s for the reason {@link InvalidInputError} does, with one difference in who
 * the field belongs to: the named field is a column of the stored record rather than a key of the request, so
 * a form cannot render it against an input it owns. Naming it is still what makes the refusal actionable --
 * "this applicant has no email address" is a sentence the Email tab can only write if it is told which column
 * was empty.
 */
public class IncompleteDataError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<FieldError> errors;

    public IncompleteDataError(String message) {
        this(message, List.of());
    }

    public IncompleteDataError(String message, List<FieldError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    /** The stored fields that were missing, never null and never modifiable. */
    public List<FieldError> errors() {
        return errors;
    }
}
