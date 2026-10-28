package uk.gov.hmcts.reform.deedpoll.domain;

import java.util.List;

/**
 * The request is well-formed and permitted, but the current state of the data forbids it.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it for the reason given
 * on {@link InvalidInputError}. {@code DomainErrorHandler} maps it to a 409.
 *
 * <p>Distinct from {@link InvalidInputError} because nothing about the input is wrong: deleting a document
 * type is a perfectly good request that fails only because documents still reference it, and it would
 * succeed unchanged once they no longer do. The caller should remove the references or retry later, not
 * correct a field -- which is what separates a 409 from a 400.
 *
 * <p>It arrives with {@code DocumentTypeService.removeDocumentType}, the one module that throws it. Its
 * restrict-on-delete foreign key already refuses, but as a driver error the reference's route layer did not
 * recognise -- so an ordinary caseworker action answered 500. {@link #ConflictError} is that answer made
 * true.
 *
 * <p>{@link ForbiddenError} and {@link UnauthorizedError} arrived after this one, with authentication, so
 * the reference's error vocabulary is now fully ported -- each with the module that throws it, which is what
 * keeps the set a record of what the service actually refuses. Only {@code zodErrorToFieldErrors} has no
 * equivalent, because Bean Validation replaces Zod.
 */
public class ConflictError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final List<FieldError> errors;

    public ConflictError(String message) {
        this(message, List.of());
    }

    /**
     * A conflict with one field's value, such as a Masters-list email another entry already holds, named so a form
     * can show it against that input. The message must not echo the value.
     */
    public ConflictError(String message, List<FieldError> errors) {
        super(message);
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
