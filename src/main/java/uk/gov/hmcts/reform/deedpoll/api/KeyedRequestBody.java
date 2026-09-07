package uk.gov.hmcts.reform.deedpoll.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A request body bound as the keys the caller actually sent, rather than as declared components.
 *
 * <p><strong>Why any write payload in this service needs this.</strong> A PATCH turns on a three-way
 * distinction: a field the caller omitted, a field the caller explicitly set to null, and a field the caller
 * gave a value. Clearing {@code dateEnroled} un-enrols a case; not mentioning it must leave it alone. A record
 * of nullable components collapses the first two into the same thing, so every PATCH would silently clear
 * everything the caller never mentioned. Jackson only ever calls a setter for a key that was present, so a map
 * built this way keeps absence and null apart.
 *
 * <p>Which keys are fields at all, and what each accepts, is decided by the ported schema the subclass hands
 * this to -- not by the binding. That is where the reference puts the same decision: its route handlers pass
 * {@code req.body} straight to a Zod schema, whose {@code z.object()} strips unknown keys and reports the rest
 * per field, and the prototype before it relied on a whitelisting validation pipe for exactly that. Validating
 * in the schema keeps one answer to "is this an acceptable field", shared by this API, the form post path and
 * any later caller.
 *
 * <p>It lives in {@code api} rather than in a namespace's {@code dto} package for the reason {@link ApiError}
 * does: it carries no case data and has no audience-specific shape, so a citizen payload reusing the mechanism
 * is not the sharing {@code NamespaceSeparationSecurityTest} forbids. That rule is about a field added for a
 * caseworker becoming visible to a citizen, and there are no fields here at all.
 *
 * <p>Subclassed per operation rather than used directly, so that a create and a patch are distinct types on
 * their two handlers even where they accept identical bodies -- an omitted field is "not supplied yet" on a
 * create and "leave it as it is" on a patch, and keeping them separate is what lets one gain a rule without the
 * other inheriting it.
 */
public abstract class KeyedRequestBody {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    /**
     * One key of the body, whatever it was called.
     *
     * <p>Called for a null value too, which is the whole reason this is a map: a present-and-null key has to
     * survive as far as the schema, which is the only thing that knows whether the column it names can be
     * cleared.
     *
     * <p>Jackson binds a JSON value to {@code Object}, so a number arrives as a number and a string as a
     * string, and a caller sending {@code "migratedRecord": "yes"} is refused by the schema with the field
     * named rather than by the deserialiser with a message about a Java type. That attribution is the point of
     * the whole layer: the reference carries per-field errors because the forms render them against individual
     * inputs.
     */
    @JsonAnySetter
    public void field(String name, Object value) {
        fields.put(name, value);
    }

    /** The body as it arrived, in the order its keys did. */
    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }
}
