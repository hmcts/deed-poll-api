package uk.gov.hmcts.reform.deedpoll.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;

import java.util.List;

/**
 * The body of every refusal this service issues.
 *
 * <p>Ported from the shape {@code apps/api/src/errors.ts} returns: a {@code error} summary, and
 * {@code errors} carrying the per-field failures when there are any. One shape for every status, so a client
 * has one thing to parse rather than a guess per route.
 *
 * <p><strong>The per-field list is the domain's {@link FieldError}, not a copy of it.</strong> Everywhere else
 * in this package a DTO is written rather than a domain type exposed, and the reasoning does not apply here:
 * {@code FieldError} is a {@code (field, message)} pair with nothing to hide and no audience-specific shape,
 * and the reference records that a conversion is where a field name gets lost. The port already collapsed
 * three structurally identical reference types into this one for that reason -- adding a fourth at the
 * transport boundary would reintroduce exactly what was removed.
 *
 * <p>It lives in {@code api} rather than in either namespace's {@code dto} package because both namespaces
 * refuse requests the same way. That is deliberate and it is not the sharing
 * {@code NamespaceSeparationSecurityTest} forbids: the rule is about case data, where a field added for a
 * caseworker must not become visible to a citizen, and an error envelope carries no case data at all.
 *
 * @param error a summary of what was refused, safe to show a caller
 * @param errors the per-field failures, empty when the refusal is not about a particular field
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String error, List<FieldError> errors) {

    /**
     * The summary for a query or path parameter this service will not accept.
     *
     * <p>Named rather than written out twice, because two paths reach it: a value Spring itself cannot
     * convert, and one a controller rejects after binding. A caller should not have to tell which by reading
     * the wording.
     */
    public static final String INVALID_PARAMETER = "Invalid request parameter";

    /**
     * A refusal that is not addressed to any one field, so {@code errors} is omitted from the JSON.
     */
    public static ApiError of(String message) {
        return new ApiError(message, List.of());
    }

    /** A refusal carrying the fields that caused it. */
    public static ApiError of(String message, List<FieldError> errors) {
        return new ApiError(message, List.copyOf(errors));
    }
}
