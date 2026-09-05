package uk.gov.hmcts.reform.deedpoll.domain;

/**
 * The request is well-formed and permitted, but the current state of the data forbids it.
 *
 * <p>Ported from {@code reference/ts/errors.ts}, and named as the reference names it for the reason given
 * on {@link InvalidInputError}. The route layer maps it to a 409.
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
 * <p>The reference declares two more errors alongside these three -- {@code ForbiddenError} and
 * {@code UnauthorizedError} -- plus a {@code zodErrorToFieldErrors} helper. They still are not ported:
 * each arrives with the ported module that throws it, so the vocabulary stays a record of what the service
 * actually refuses. Task 12 will need the other two.
 */
public class ConflictError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ConflictError(String message) {
        super(message);
    }
}
