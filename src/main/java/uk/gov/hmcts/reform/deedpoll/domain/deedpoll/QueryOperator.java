package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a case-query condition can ask of a field, and of which kinds of field it can ask it.
 *
 * <p>The reference has no equivalent: the Dynamics original offered five saved system views and no
 * field-level query, so this is new surface rather than a port. It is in the domain and not in
 * {@code api} because which questions may be asked of a field is a property of the field, not of HTTP
 * -- and because the query has to survive the move to CCD's Elasticsearch indexer, which means the
 * vocabulary must describe <em>what</em> is being asked and never <em>how</em> it is answered.
 *
 * <p>{@link #wire()} is the token a caller sends, and it is deliberately not {@link #name()}: the wire
 * spelling is lower camel case ({@code startsWith}, {@code isOneOf}) because it appears in a URL a
 * caseworker reads, while the constant is Java's. Matching is exact rather than case-insensitive --
 * accepting {@code STARTSWITH} would make the wire format two formats, and the refusal already lists
 * the operators the field will take.
 *
 * <p><strong>The kind list on each operator is the whole permission model.</strong> An operator that
 * does not name a kind cannot be applied to a field of that kind, so a new {@link FieldKind} gets no
 * operators until someone says which are meaningful -- failing closed, which is the direction that
 * matters when the value ends up in a query. Note {@code BOOLEAN} takes only {@code is}: a
 * three-state boolean would need {@code isEmpty}, and every boolean column here is either NOT NULL or
 * read as false when absent.
 */
public enum QueryOperator {

    /** Equal to the value. The one operator every single-valued kind takes. */
    IS("is", Arity.ONE,
        FieldKind.TEXT, FieldKind.TEXTAREA, FieldKind.DATE, FieldKind.NUMBER, FieldKind.ENUM,
        FieldKind.BOOLEAN),

    /**
     * Substring of a text field, or membership of a multiselect.
     *
     * <p>Two readings of one word, and deliberately so: to a caseworker "contains" is what both
     * questions sound like, and the kind already says which is meant. On text it is
     * case-insensitive (see {@link #STARTS_WITH}); on a multiselect it is exact, because the stored
     * values are option-set integers and there is no such thing as half of one.
     */
    CONTAINS("contains", Arity.ONE, FieldKind.TEXT, FieldKind.TEXTAREA, FieldKind.MULTISELECT),

    /**
     * Text beginning with the value, case-insensitively.
     *
     * <p>Case-insensitive because a caseworker typing a surname should not have to match the stored
     * capitalisation. That cannot use a plain B-tree index, which is a known and deliberate trade for
     * v1 -- there is no deployed data to measure an index against yet.
     */
    STARTS_WITH("startsWith", Arity.ONE, FieldKind.TEXT, FieldKind.TEXTAREA),

    /** Strictly before the given date. */
    BEFORE("before", Arity.ONE, FieldKind.DATE),

    /** Strictly after the given date. */
    AFTER("after", Arity.ONE, FieldKind.DATE),

    /** Strictly greater than the given number. */
    GREATER_THAN("gt", Arity.ONE, FieldKind.NUMBER),

    /** Strictly less than the given number. */
    LESS_THAN("lt", Arity.ONE, FieldKind.NUMBER),

    /** Between two bounds, inclusive, given low first. */
    BETWEEN("between", Arity.TWO, FieldKind.DATE, FieldKind.NUMBER),

    /**
     * Any one of the listed members -- the multi-select shape of {@link #IS} for an enum.
     */
    IS_ONE_OF("isOneOf", Arity.AT_LEAST_ONE, FieldKind.ENUM),

    /**
     * Null, or empty for a multiselect.
     *
     * <p>Applies to every kind but {@code BOOLEAN}, and asking it of a text column also matches the
     * empty string: to a caseworker looking at a blank cell, a null and a {@code ''} are the same
     * blank, and a query that distinguished them would answer a question nobody asked.
     */
    IS_EMPTY("isEmpty", Arity.NONE,
        FieldKind.TEXT, FieldKind.TEXTAREA, FieldKind.DATE, FieldKind.NUMBER, FieldKind.ENUM,
        FieldKind.MULTISELECT),

    /**
     * The negation of {@link #IS_EMPTY}, kind for kind.
     */
    IS_NOT_EMPTY("isNotEmpty", Arity.NONE,
        FieldKind.TEXT, FieldKind.TEXTAREA, FieldKind.DATE, FieldKind.NUMBER, FieldKind.ENUM,
        FieldKind.MULTISELECT);

    private static final Map<String, QueryOperator> BY_WIRE = Arrays.stream(values())
        .collect(Collectors.toUnmodifiableMap(QueryOperator::wire, operator -> operator));

    private final String wire;
    private final Arity arity;
    private final Set<FieldKind> kinds;

    QueryOperator(String wire, Arity arity, FieldKind... kinds) {
        this.wire = wire;
        this.arity = arity;
        this.kinds = Collections.unmodifiableSet(EnumSet.copyOf(Arrays.asList(kinds)));
    }

    /** The token a caller sends, which is not the constant's name. */
    public String wire() {
        return wire;
    }

    /** How many values the operator takes. */
    public Arity arity() {
        return arity;
    }

    /** Whether this operator can be asked of a field of the given kind. */
    public boolean appliesTo(FieldKind kind) {
        return kinds.contains(kind);
    }

    /** The operator a caller named, or empty for a token that is not one. */
    public static Optional<QueryOperator> forWire(String token) {
        return Optional.ofNullable(BY_WIRE.get(token));
    }

    /**
     * Every operator a field of this kind takes, in declaration order.
     *
     * <p>Published because a refusal that lists the alternatives is the difference between a caller
     * fixing their request and guessing at it, and because the query-builder UI needs exactly this
     * list to populate the operator control for a chosen field. One source for both.
     */
    public static List<QueryOperator> forKind(FieldKind kind) {
        return Arrays.stream(values())
            .filter(operator -> operator.appliesTo(kind))
            .toList();
    }

    /**
     * The wire tokens of {@link #forKind}, comma-separated, for a message a caller reads.
     */
    public static String wireTokensFor(FieldKind kind) {
        return forKind(kind).stream()
            .map(QueryOperator::wire)
            .collect(Collectors.joining(", "));
    }

    /**
     * How many values an operator takes, and how the value part is read.
     *
     * <p>A list-taking arity splits the value part on commas; a single-valued one does not, which is
     * why a comma inside a text value is never ambiguous -- no text operator takes a list.
     */
    public enum Arity {

        /** No value at all: the operator is the whole question. */
        NONE("takes no value"),

        /** Exactly one value, read verbatim. */
        ONE("takes one value"),

        /** Exactly two comma-separated values, low first. */
        TWO("takes two comma-separated values, low first"),

        /** One or more comma-separated values. */
        AT_LEAST_ONE("takes at least one comma-separated value");

        private final String requirement;

        Arity(String requirement) {
            this.requirement = requirement;
        }

        /** What this arity requires, phrased for a caller who did not meet it. */
        public String requirement() {
            return requirement;
        }

        /** Whether the value part is a comma-separated list rather than a single value. */
        public boolean takesList() {
            return this == TWO || this == AT_LEAST_ONE;
        }

        /** Whether a given number of values satisfies this arity. */
        public boolean accepts(int count) {
            return switch (this) {
                case NONE -> count == 0;
                case ONE -> count == 1;
                case TWO -> count == 2;
                case AT_LEAST_ONE -> count >= 1;
            };
        }
    }
}
