package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The order the case list is asked for: one field, and a direction.
 *
 * <p>New surface rather than a port -- the Dynamics original sorted each system view for you. It sits
 * beside {@link CaseQuery} for the same reason and with the same discipline: it says <em>what</em> order
 * was asked for and never <em>how</em> the database is told, so the wire format survives the move to
 * CCD's Elasticsearch indexer. {@code DeedPollService} is the only thing that turns one of these into a
 * {@code Sort}.
 *
 * <p><strong>One field, not a list.</strong> A list page has one clickable column header at a time, and
 * a second sort key changes nothing a caseworker can see except in a tie -- which the id tiebreak
 * {@code DeedPollService} adds already settles, deterministically and for every request. So a second key
 * would be surface with no consumer.
 *
 * <p><strong>Sortable is not the same as queryable.</strong> Two departures, both in the same direction:
 *
 * <ul>
 *   <li>A {@code MULTISELECT} field is queryable and NOT sortable. Ordering a set of option values means
 *       choosing whether {@code {1,3}} precedes {@code {2}}, and every answer to that is arbitrary. It is
 *       refused rather than silently ignored, because a list that came back in an order the caller did not
 *       ask for, with nothing said, is the failure this whole surface exists to avoid.</li>
 *   <li>The four audit columns are sortable and NOT queryable, because {@link FormFields} does not
 *       describe them -- they are not on the form. {@code createdOn} has to be sortable whatever else is:
 *       it is the default, so refusing to let a caller name it would make "oldest first" the one order
 *       that cannot be asked for.</li>
 * </ul>
 *
 * <p>Every sortable name is a stored column this service published, so the name that reaches a query is
 * never the caller's string -- the same reasoning as {@link CaseQuery#parse}, and the same reason a name
 * crafted to look like SQL is refused here rather than escaped later.
 */
public record CaseSort(String field, Direction direction) {

    /**
     * Today's order, and the default: newest first.
     *
     * <p>The reference's {@code orderBy}, and what every existing caller already gets -- so a request that
     * sends no {@code sort} sees exactly the list it saw before this parameter existed.
     */
    public static final CaseSort NEWEST_FIRST = new CaseSort("createdOn", Direction.DESCENDING);

    /** The summary every refusal from this class carries. */
    private static final String INVALID_SORT = "Case list sort is not valid";

    /** How much of a caller's token a refusal quotes back, so the response stays bounded. */
    private static final int MAX_QUOTED = 60;

    public CaseSort {
        if (!sortableFields().contains(field)) {
            // Not a caller's mistake by the time it reaches here: parse() is the only way in from a request,
            // so this is a programming error and not something to report as a FieldError.
            throw new IllegalArgumentException("Not a sortable case field: " + field);
        }
    }

    /** Which way round. */
    public enum Direction {

        /** Smallest, earliest or alphabetically first, first. */
        ASCENDING("asc"),

        /**
         * Largest, latest or alphabetically last, first -- which is what the default order is.
         */
        DESCENDING("desc");

        private static final Map<String, Direction> BY_WIRE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Direction::wire, direction -> direction));

        private final String wire;

        Direction(String wire) {
            this.wire = wire;
        }

        /** The token a caller sends, which is not the constant's name. */
        public String wire() {
            return wire;
        }

        /** Whether this is the descending direction, for a caller translating without a switch. */
        public boolean isDescending() {
            return this == DESCENDING;
        }

        /** The direction a caller named, or empty for a token that is not one. */
        public static Optional<Direction> forWire(String token) {
            return Optional.ofNullable(BY_WIRE.get(token));
        }

        /** Both tokens, for a message a caller reads. */
        public static String wireTokens() {
            return Arrays.stream(values()).map(Direction::wire).collect(Collectors.joining(", "));
        }
    }

    /**
     * Reads the {@code sort} parameter into a validated order.
     *
     * <p>{@code field:asc} or {@code field:desc}, split on the first colon -- a field name never contains
     * one. A bare field name is read as ascending: ascending is what an undirected sort conventionally
     * means, and refusing a request whose meaning is unambiguous would be strictness for its own sake. An
     * empty direction after the colon is NOT the same thing and is refused, for the reason {@link CaseQuery}
     * refuses a blank value -- it is a builder assembling a parameter from a control nobody filled in.
     *
     * <p>The direction token is matched exactly, as {@link QueryOperator#forWire} matches an operator:
     * accepting {@code DESC} as well as {@code desc} would make the wire format two formats, and the
     * refusal already says which two tokens there are.
     *
     * @param sort the raw parameter, or null for a request that sent none
     * @return the order asked for, or {@link #NEWEST_FIRST} for a request that asked for none
     * @throws InvalidInputError addressed to {@code sort}, naming what is wrong with it
     */
    public static CaseSort parse(String sort) {
        if (sort == null || sort.isBlank()) {
            return NEWEST_FIRST;
        }
        int colon = sort.indexOf(':');
        String name = (colon < 0 ? sort : sort.substring(0, colon)).trim();
        String directionToken = colon < 0 ? null : sort.substring(colon + 1).trim();

        return new CaseSort(sortableField(name), direction(directionToken));
    }

    /**
     * Every field the list may be sorted by: the single-valued form fields, plus the audit columns.
     *
     * <p>Published because the query-builder UI needs exactly this list to populate a sort control, and
     * because a set computed from the registries cannot drift from what {@link #parse} accepts. Note it is
     * deliberately not exposed in a refusal message: there are more than sixty of them, and a bounded
     * response matters more than an exhaustive one.
     */
    public static Set<String> sortableFields() {
        return Sortable.NAMES;
    }

    /**
     * The field a caller named, refusing anything that is not sortable and saying which reason it is.
     *
     * <p>Three refusals rather than one, because they call for different fixes: a misspelling, a field that
     * has no column, and a field that has one but holds several values at once.
     */
    private static String sortableField(String name) {
        if (sortableFields().contains(name)) {
            return name;
        }
        FieldDef field = FormFields.FIELD_BY_NAME.get(name);
        if (field == null) {
            throw refusal("names no sortable case field: " + quoted(name));
        }
        if (field.kind() == FieldKind.MULTISELECT) {
            throw refusal(field.name() + " holds several values at once and cannot be sorted");
        }
        throw refusal("cannot sort by " + quoted(name) + ": it is derived, not stored");
    }

    private static Direction direction(String token) {
        if (token == null) {
            return Direction.ASCENDING;
        }
        return Direction.forWire(token)
            .orElseThrow(() -> refusal("takes a direction of " + Direction.wireTokens()
                + ", and was given " + quoted(token)));
    }

    private static InvalidInputError refusal(String message) {
        return new InvalidInputError(INVALID_SORT, List.of(new FieldError("sort", message)));
    }

    /** A caller's token, quoted and truncated, safe to put in a message. */
    private static String quoted(String token) {
        String bounded = token.length() <= MAX_QUOTED ? token : token.substring(0, MAX_QUOTED) + "…";
        return "'" + bounded + "'";
    }

    /**
     * The sortable names, computed once from the field registries.
     *
     * <p>In a holder rather than a constant of the enclosing record, because {@link #NEWEST_FIRST} is a
     * {@code CaseSort} and its constructor checks this set: a plain static field declared after it would
     * still be null at that point, and the class would fail to initialise. A holder is initialised on first
     * touch instead, so declaration order stops mattering.
     */
    private static final class Sortable {

        /**
         * The stored columns that are sortable without being form fields.
         *
         * <p>Named explicitly rather than taken as "everything readable that is not a form field", which
         * would also admit {@code id} and {@code displayReference} -- neither of which any list header
         * offers, and {@code displayReference} sorts as text, so its order is not the order of the reference
         * numbers it renders. These four are the audit pair a caseworker really asks for ("what changed most
         * recently") and the two authors beside them.
         */
        private static final Set<String> AUDIT_FIELDS =
            Set.of("createdOn", "modifiedOn", "createdBy", "modifiedBy");

        private static final Set<String> NAMES = names();

        private Sortable() {
        }

        private static Set<String> names() {
            Set<String> names = new LinkedHashSet<>();
            FormFields.FIELD_BY_NAME.forEach((name, field) -> {
                // Stored, so there is a column to order by -- the two computed day counts are derived at
                // read time and have none. Single-valued, so an order exists at all.
                if (field.kind() != FieldKind.MULTISELECT && DeedPollFields.readableFields().contains(name)) {
                    names.add(name);
                }
            });
            names.addAll(AUDIT_FIELDS);
            return Collections.unmodifiableSet(names);
        }
    }
}
