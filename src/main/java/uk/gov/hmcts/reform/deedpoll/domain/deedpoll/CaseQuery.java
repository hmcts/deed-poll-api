package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A field-level question about the case list: a flat list of conditions, combined with AND.
 *
 * <p>New surface rather than a port. The Dynamics original offered five saved system views and no
 * field-level query, and those five are still {@link ListFilters} -- this composes with them rather
 * than replacing them, so a bookmarked {@code ?filter=open} keeps meaning what it meant.
 *
 * <p><strong>AND only: a list, not a tree.</strong> No OR, no grouping, no nesting. A flat list covers
 * the real caseworker question ("Adult applications received in June still awaiting enrolment") and OR
 * is not a small addition to it -- it changes the representation from a list to a tree, and every
 * consumer with it. It is out of scope deliberately rather than deferred vaguely.
 *
 * <p><strong>Nothing reaches a query without passing through here.</strong> A condition names a field,
 * and a field name reaches a query -- which is the shape of an injection. So the field name is resolved
 * against {@link FormFields} to a {@link FieldDef} the service itself wrote, and that resolved definition
 * is what a {@link Condition} carries: {@link #parse} either puts a published {@code FieldDef} there or
 * throws, so the caller's string never becomes the name in a query. An operator and an enum member are
 * checked the same way and for the same reason.
 *
 * <p><strong>Every bad condition is reported, not the first.</strong> A query builder sends several
 * conditions at once, so failing on the first would make fixing a request a round trip per mistake. The
 * errors are collected into one {@link InvalidInputError}, each addressed to the condition's position --
 * {@code where.0}, {@code where.1} -- because the parameter is repeated and a position is the only thing
 * that identifies which of three conditions on the same field was the wrong one.
 */
public record CaseQuery(List<Condition> conditions) {

    /**
     * No conditions: what an unfiltered list asks, and what a request with no {@code where} means.
     */
    public static final CaseQuery NONE = new CaseQuery(List.of());

    /**
     * The most conditions one request may carry.
     *
     * <p>Capped for the reason the page size is capped: each condition is another predicate against a
     * table with no index for this, so an uncapped count is how a list endpoint becomes a denial of
     * service against its own database. Twenty is far more than a query builder offers and far less
     * than a loop can do damage with.
     */
    public static final int MAX_CONDITIONS = 20;

    /** The summary every refusal from this class carries; the per-condition detail is in the errors. */
    private static final String INVALID_QUERY = "Case query is not valid";

    /** How much of a caller's token a refusal quotes back, so the response stays bounded. */
    private static final int MAX_QUOTED = 60;

    public CaseQuery {
        conditions = List.copyOf(conditions);
    }

    /** Whether this asks anything at all. */
    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /**
     * One condition: a field, what is asked of it, and the values it is asked with.
     *
     * <p>The values are already coerced to the type the field's kind implies -- {@link LocalDateTime}
     * for {@code DATE}, {@link BigDecimal} for {@code NUMBER}, {@link Boolean} for {@code BOOLEAN},
     * {@link Integer} for a {@code MULTISELECT} option, and a {@link String} for everything else
     * (an {@code ENUM} member is its validated name, which the translation binds to the column's own
     * enum type). Coercing here rather than at translation time is the same discipline
     * {@link DeedPollFields} applies to a write: one point of coercion means a value that cannot be
     * read as its field's type is refused where a caller can be told about it, rather than throwing out
     * of a query builder.
     *
     * <p>Never null and never empty of anything the operator's arity requires, because
     * {@link #parse} is the only way to get one.
     */
    public record Condition(FieldDef field, QueryOperator operator, List<Object> values) {

        public Condition {
            values = List.copyOf(values);
        }

        /** The stored field this asks about. */
        public String fieldName() {
            return field.name();
        }

        /** The kind of that field, which is what decides how the condition translates. */
        public FieldKind kind() {
            return field.kind();
        }

        /** The single value of a single-valued operator. */
        public Object value() {
            return values.get(0);
        }
    }

    /**
     * Reads the repeated {@code where} parameters into a validated query.
     *
     * <p>Each entry is {@code field:operator:value}, <strong>split on the first two colons only</strong>
     * -- everything after the second is the value, so a colon inside a value needs no escaping beyond
     * the URL encoding it already has. An entry with one colon and no value part is how a no-value
     * operator is written ({@code parents:isEmpty}).
     *
     * @param where the raw parameter values, in the order the request sent them; null or empty is
     *     {@link #NONE} rather than an error, because a list with no query is the normal case
     * @throws InvalidInputError carrying one {@link FieldError} per bad condition, addressed to its
     *     position in {@code where}
     */
    public static CaseQuery parse(List<String> where) {
        if (where == null || where.isEmpty()) {
            return NONE;
        }
        if (where.size() > MAX_CONDITIONS) {
            throw new InvalidInputError(INVALID_QUERY, List.of(new FieldError(
                "where", "takes at most " + MAX_CONDITIONS + " conditions, and was given " + where.size())));
        }

        List<Condition> conditions = new ArrayList<>();
        List<FieldError> errors = new ArrayList<>();
        for (int index = 0; index < where.size(); index++) {
            String position = "where." + index;
            try {
                conditions.add(condition(where.get(index)));
            } catch (BadCondition bad) {
                errors.add(new FieldError(position, bad.getMessage()));
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidInputError(INVALID_QUERY, errors);
        }
        return new CaseQuery(conditions);
    }

    /**
     * One entry parsed and validated, or {@link BadCondition} saying what is wrong with it.
     */
    private static Condition condition(String raw) {
        String entry = raw == null ? "" : raw;
        int firstColon = entry.indexOf(':');
        if (firstColon < 0) {
            throw new BadCondition("must be field:operator:value, and was " + quoted(entry));
        }
        int secondColon = entry.indexOf(':', firstColon + 1);

        String name = entry.substring(0, firstColon).trim();
        String operatorPart = secondColon < 0
            ? entry.substring(firstColon + 1)
            : entry.substring(firstColon + 1, secondColon);
        String token = operatorPart.trim();
        // Absent rather than empty when there is no second colon at all, which is not the same thing as
        // a trailing colon with nothing after it -- both mean "no value", and neither is an error yet.
        String valuePart = secondColon < 0 ? null : entry.substring(secondColon + 1);

        FieldDef field = queryableField(name);
        QueryOperator operator = operatorFor(token, field);
        return new Condition(field, operator, values(field, operator, valuePart));
    }

    /**
     * The field a condition names, refusing anything that is not one this service published.
     *
     * <p><strong>The security-relevant line in this class.</strong> Only a name in
     * {@link FormFields#FIELD_BY_NAME} resolves, so a name crafted to look like SQL is refused here and
     * never reaches a query -- and the refusal quotes it back truncated, so the response stays bounded
     * whatever was sent.
     *
     * <p>Queryable is not the same as editable: all five read-only fields are queryable, because
     * {@code referencenumber} and {@code state} are among the most useful things to ask about and
     * neither is editable. What a field does need is a column -- the two computed day counts are
     * derived at read time, so there is nothing to query them against.
     */
    private static FieldDef queryableField(String name) {
        FieldDef field = FormFields.FIELD_BY_NAME.get(name);
        if (field == null) {
            throw new BadCondition("names no case field: " + quoted(name));
        }
        if (!DeedPollFields.readableFields().contains(name)) {
            throw new BadCondition("cannot be queried: " + quoted(name) + " is derived, not stored");
        }
        return field;
    }

    /** The operator a condition names, refused if it is unknown or wrong for the field's kind. */
    private static QueryOperator operatorFor(String token, FieldDef field) {
        QueryOperator operator = QueryOperator.forWire(token)
            .orElseThrow(() -> new BadCondition("names no operator: " + quoted(token)));
        if (!operator.appliesTo(field.kind())) {
            throw new BadCondition(operator.wire() + " does not apply to " + field.name()
                + ", which takes: " + QueryOperator.wireTokensFor(field.kind()));
        }
        return operator;
    }

    /**
     * The condition's values, split by the operator's arity and coerced to the field's type.
     *
     * <p>A list-taking operator splits on commas and trims each element, because whitespace after a
     * comma in a hand-written URL is punctuation rather than content. A single-valued operator does not
     * split and does not trim: a comma is then part of the value, and so is a space that a caller
     * deliberately searched for.
     */
    private static List<Object> values(FieldDef field, QueryOperator operator, String valuePart) {
        List<String> raw = split(operator, valuePart);
        if (!operator.arity().accepts(raw.size())) {
            throw new BadCondition(operator.wire() + " " + operator.arity().requirement()
                + ", and was given " + raw.size());
        }
        List<Object> coerced = raw.stream().map(value -> coerce(field, value)).toList();
        if (operator == QueryOperator.BETWEEN) {
            requireAscending(coerced);
        }
        return coerced;
    }

    private static List<String> split(QueryOperator operator, String valuePart) {
        if (valuePart == null || valuePart.isEmpty()) {
            return List.of();
        }
        if (!operator.arity().takesList()) {
            return List.of(valuePart);
        }
        return Arrays.stream(valuePart.split(",", -1))
            .map(String::trim)
            .toList();
    }

    /**
     * One value as its field's kind implies.
     *
     * <p>Refusing rather than coercing loosely, for the reason {@link DeedPollFields} refuses a
     * fractional integer: a value silently read as something else answers a question the caller did not
     * ask, and a query is exactly where that is invisible. A blank value is refused too, and that is the
     * one place a text value's whitespace is not treated as content: {@code is} with nothing in it is a
     * builder assembling a condition from an empty control, and {@code isEmpty} is the operator that
     * means what it was reaching for.
     */
    private static Object coerce(FieldDef field, String value) {
        if (value.isBlank()) {
            throw new BadCondition(field.name() + " was given a blank value; use isEmpty to match a blank");
        }
        return switch (field.kind()) {
            case TEXT, TEXTAREA -> value;
            case DATE -> Optional.ofNullable(Dates.toDateTime(value))
                .orElseThrow(() -> new BadCondition(field.name() + " takes a date, and was given " + quoted(value)));
            case NUMBER -> number(field, value);
            case BOOLEAN -> bool(field, value);
            case ENUM -> member(field, value);
            case MULTISELECT -> option(field, value);
        };
    }

    private static BigDecimal number(FieldDef field, String value) {
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException notANumber) {
            throw new BadCondition(field.name() + " takes a number, and was given " + quoted(value));
        }
    }

    private static Boolean bool(FieldDef field, String value) {
        String trimmed = value.trim();
        if ("true".equals(trimmed) || "false".equals(trimmed)) {
            return Boolean.valueOf(trimmed);
        }
        throw new BadCondition(field.name() + " takes true or false, and was given " + quoted(value));
    }

    /**
     * An enum member's name, checked against the option set the field names.
     *
     * <p>Kept as the name rather than resolved to a Java enum here: the domain's field metadata knows
     * which {@code LABELS} set a field draws on, not which class the column is, and the translation to
     * a predicate has the column type to hand. What matters is that only a member of the named set gets
     * through, and an unresolvable set admits nothing -- failing closed.
     */
    private static String member(FieldDef field, String value) {
        String trimmed = value.trim();
        if (!labels(field).containsKey(trimmed)) {
            throw new BadCondition(field.name() + " has no value " + quoted(value));
        }
        return trimmed;
    }

    /**
     * A multiselect option as the {@code integer[]} column stores it: the Dynamics value.
     */
    private static Integer option(FieldDef field, String value) {
        String trimmed = value.trim();
        if (!labels(field).containsKey(trimmed)) {
            throw new BadCondition(field.name() + " has no option " + quoted(value));
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException notAnOption) {
            // Unreachable while every multiselect set is integer-keyed, which FormFieldsTest pins.
            throw new BadCondition(field.name() + " has no option " + quoted(value));
        }
    }

    private static Map<String, String> labels(FieldDef field) {
        return Optional.ofNullable(field.enumSet())
            .map(DynamicsValues.LABELS::get)
            .orElse(Map.of());
    }

    /**
     * Both bounds of a {@code between} in order, refusing a reversed pair.
     *
     * <p>Refused rather than swapped. A reversed range is a request that can only ever match nothing,
     * and silently reordering it would answer a different question from the one asked -- the caller
     * almost certainly filled two controls the wrong way round and needs to be told, not humoured.
     */
    @SuppressWarnings("unchecked")
    private static void requireAscending(List<Object> bounds) {
        Comparable<Object> low = (Comparable<Object>) bounds.get(0);
        if (low.compareTo(bounds.get(1)) > 0) {
            throw new BadCondition("between takes its bounds low first, and they are reversed");
        }
    }

    /** A caller's token, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        String bounded = token.length() <= MAX_QUOTED ? token : token.substring(0, MAX_QUOTED) + "…";
        return "'" + bounded + "'";
    }

    /**
     * One condition's rejection, on its way to becoming a {@link FieldError}.
     *
     * <p>An exception rather than a returned result because the checks nest -- a value cannot be
     * coerced before its field is known -- and a chain of {@code Optional} would say the same thing with
     * more ceremony. It never escapes this class: {@link #parse} catches every one and collects them,
     * which is what makes a request with three mistakes report three.
     */
    private static final class BadCondition extends RuntimeException {

        private static final long serialVersionUID = 1L;

        BadCondition(String message) {
            super(message, null, false, false);
        }
    }
}
