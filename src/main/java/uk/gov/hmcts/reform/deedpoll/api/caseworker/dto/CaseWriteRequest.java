package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollSchemas;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A case write body: the fields the caller mentioned, as they arrived.
 *
 * <p><strong>A keyed map rather than seventy-odd nullable components, and it has to be.</strong> A case write
 * turns on the three-way distinction between a field the caller omitted, a field the caller explicitly set to
 * null, and a field the caller gave a value -- clearing {@code dateEnroled} un-enrols a case, whereas not
 * mentioning it must leave it alone. A record of nullable components collapses the first two into the same
 * thing, and every PATCH would silently clear the sixty-odd fields the caller never mentioned. This is the same
 * reasoning, at the transport boundary, that made {@link DeedPollPatch} a map rather than a class; see its
 * javadoc, and the reference's own tests, which assert both directions.
 *
 * <p>So the type this binds to has no declared properties: {@link JsonAnySetter} takes every key, and
 * {@link DeedPollSchemas} is what decides which of them are fields at all and what each accepts. That is not a
 * loophole -- it is where the reference puts the same decision. Its route handlers hand {@code req.body}
 * straight to a Zod schema, whose {@code z.object()} strips unknown keys and reports the rest per field, and
 * the prototype before it relied on a whitelisting validation pipe for exactly that. Validating in the schema
 * rather than in the binding also keeps one answer to "is this an acceptable case field", shared by this API,
 * the form post path and any later caller.
 *
 * <p>Jackson binds a JSON value to {@code Object}, so a number arrives as a number and a string as a string,
 * and a caller sending {@code "migratedRecord": "yes"} is refused by the schema with the field named rather
 * than by the deserialiser with a message about a Java type. That attribution is the point of the whole layer:
 * the reference carries per-field errors because the forms render them against individual inputs.
 *
 * <p>Subclassed rather than used directly so that a create and a patch are distinct types on the two handlers.
 * They accept identical bodies today -- the reference declares its update schema as
 * {@code createDeedPollSchema.partial()} and every create field is already optional -- and they do not mean the
 * same thing: an omitted field is "not supplied yet" on a create and "leave it as it is" on a patch. Keeping
 * them separate is what lets one of them gain a rule without the other inheriting it.
 */
public abstract class CaseWriteRequest {

    private final Map<String, Object> fields = new LinkedHashMap<>();

    /**
     * One key of the body, whatever it was called.
     *
     * <p>Called for a null value too, which is the whole reason this is a map: a present-and-null key has to
     * survive as far as the schema, which is the only thing that knows whether the column it names can be
     * cleared.
     */
    @JsonAnySetter
    public void field(String name, Object value) {
        fields.put(name, value);
    }

    /** The body as it arrived, in the order its keys did. */
    public Map<String, Object> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /**
     * The validated patch this body means.
     *
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError with one entry per offending field, which
     *     {@code DomainErrorHandler} answers as a 400 carrying the attributions
     */
    public abstract DeedPollPatch toPatch();
}
