package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A create payload or an update patch: the fields a caller means to write, and nothing else.
 *
 * <p>The reference passes a plain object around and relies on JavaScript's three-way distinction
 * between a key that is absent, a key holding {@code null}, and a key holding a value. That distinction
 * is the whole of PATCH semantics -- clearing {@code dateEnroled} un-enrols a case, whereas not
 * mentioning it must leave it alone -- and a Java class of nullable fields cannot express it. So the
 * patch is a keyed map, exactly as the reference's is, with {@link #has} answering "was this field part
 * of the write".
 *
 * <p>Only the names {@link DeedPollFields#isWritable} accepts can be in a patch. That is the reference's
 * "{@code z.object()} strips unknown keys" behaviour, and it is what keeps {@code referencenumber},
 * {@code displayReference} and the audit columns out of a caller's reach. {@link #of} drops an unknown
 * key silently, as the schema does; {@link #set} refuses one, because naming a field in code is a
 * statement of intent and a typo there should not fail silently.
 *
 * <p>Mutable on purpose. Every write in {@code service.ts} is a sequence of {@code Object.assign} calls
 * onto one payload, and reproducing that as a chain of copies would obscure the order the gates run in --
 * which is the one thing about that sequence worth reviewing. {@link #copy} is what keeps a caller's
 * patch untouched.
 */
public final class DeedPollPatch {

    /**
     * Fields that arrive as ISO date strings and must be coerced before anything reads them as dates.
     *
     * <p>Ported from the reference's {@code DATE_FIELDS}. It is a list rather than "every field whose
     * column is a timestamp" because the audit columns are timestamps too and are not writable.
     */
    private static final Set<String> DATE_FIELDS = Set.of(
        "minorDateOfBirth",
        "dateOfDeclaration",
        "dateOfApplication",
        "dateReceived",
        "awaitingFurtherDocsInfo",
        "signatureRequestedDate",
        "signatureReceivedDate",
        "dateReadyForMaster",
        "dateReferred",
        "dateEnroled",
        "dateReturned",
        "dateNoticeSentToLondonGazette",
        "masterDateOfDecision",
        "dateDecisionDirectionsReceived",
        "dateOfRefund");

    private final Map<String, Object> values = new LinkedHashMap<>();

    private DeedPollPatch() {
    }

    /** An empty patch, which writes nothing. */
    public static DeedPollPatch empty() {
        return new DeedPollPatch();
    }

    /**
     * A patch from a field map, dropping any name a caller may not write.
     *
     * <p>A null VALUE is kept -- that is a deliberate clear. It is only unknown KEYS that are dropped.
     */
    public static DeedPollPatch of(Map<String, Object> fields) {
        DeedPollPatch patch = new DeedPollPatch();
        fields.forEach((field, value) -> {
            if (DeedPollFields.isWritable(field)) {
                patch.values.put(field, value);
            }
        });
        return patch;
    }

    /** Whether this write mentions the field at all, clear or otherwise. */
    public boolean has(String field) {
        return values.containsKey(field);
    }

    /** The value for a field, or null -- which is also what a deliberate clear looks like. */
    public Object get(String field) {
        return values.get(field);
    }

    /** The fields of this write, in the order they were added. */
    public Map<String, Object> values() {
        return Collections.unmodifiableMap(values);
    }

    /** Whether this write touches nothing. */
    public boolean isEmpty() {
        return values.isEmpty();
    }

    /**
     * Sets one field.
     *
     * @throws IllegalArgumentException if the field is not one a patch may write
     */
    public DeedPollPatch set(String field, Object value) {
        if (!DeedPollFields.isWritable(field)) {
            throw new IllegalArgumentException("Not a writable DeedPoll field: " + field);
        }
        values.put(field, value);
        return this;
    }

    /** Removes a field from this write, so it is left alone rather than cleared. */
    public DeedPollPatch remove(String field) {
        values.remove(field);
        return this;
    }

    /**
     * Merges another patch's fields in, overwriting on collision. The reference's {@code Object.assign}.
     */
    public DeedPollPatch merge(Map<String, Object> patch) {
        patch.forEach(this::set);
        return this;
    }

    /** An independent copy, so a caller's patch is never mutated by a write. */
    public DeedPollPatch copy() {
        DeedPollPatch patch = new DeedPollPatch();
        patch.values.putAll(values);
        return patch;
    }

    /**
     * Coerces the ISO date strings in this patch to date-times, leaving everything else untouched.
     *
     * <p>Ported from the reference's {@code coerceDates}. Dates stay strings through schema validation on
     * purpose -- the forms submit {@code YYYY-MM-DD} and the API tends to send a full timestamp -- and
     * this is the single point at which they become dates, before any rule reads one.
     *
     * <p>An empty string is left as it is rather than becoming a date, matching the reference's
     * {@code v.length > 0} guard; the schema has already refused it, so reaching here with one means a
     * caller bypassed validation and a silent 1970 would be worse than the write failing.
     */
    public DeedPollPatch coerceDates() {
        for (String field : DATE_FIELDS) {
            Object value = values.get(field);
            if (value instanceof String text && !text.isEmpty()) {
                values.put(field, Dates.toDateTime(text));
            }
        }
        return this;
    }

    /** A date field of this patch, for the rules that read one. Null when absent, cleared or not a date. */
    public LocalDateTime dateOf(String field) {
        return Dates.toDateTime(values.get(field));
    }

    /** Writes every field of this patch onto a case. */
    public void applyTo(DeedPoll record) {
        values.forEach((field, value) -> DeedPollFields.write(record, field, value));
    }

    /** The field names of this write, for a caller that needs to report them. */
    public List<String> fields() {
        return List.copyOf(values.keySet());
    }
}
