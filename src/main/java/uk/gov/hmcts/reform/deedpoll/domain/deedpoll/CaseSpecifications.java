package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A case query, and a filter's state criterion, as something the database can answer.
 *
 * <p>This is the "how" half of the list endpoint, and {@link CaseQuery} is the "what". The split is
 * deliberate and load-bearing: decentralised CCD ships an Elasticsearch indexer, so the question a
 * caller asks has to outlive the thing that answers it. Nothing above this class knows a predicate
 * exists, and nothing in this class decides whether a question was legitimate -- {@link CaseQuery#parse}
 * has already refused anything that was not.
 *
 * <p><strong>No value and no name is ever concatenated into a query.</strong> Every comparison goes
 * through the Criteria API, which binds its values, and every field name is
 * {@link CaseQuery.Condition#fieldName()} -- which is a {@link FieldDef} name this service published,
 * not a string a caller sent. A name crafted to look like SQL never gets this far, and if it somehow
 * did, {@code root.get} would raise rather than interpolate.
 *
 * <p><strong>Typed paths, not casts.</strong> Each kind reads its column through
 * {@code root.<T>get(name)}, so the value binds against the type the column actually has. That is what
 * makes an enum condition bind an enum constant and a number condition compare numerically rather than
 * as text, and it is why the kind switch below is exhaustive rather than generic: the type is the
 * translation.
 */
public final class CaseSpecifications {

    /**
     * The LIKE escape character, applied to a caseworker's own {@code %} and {@code _}.
     *
     * <p>Without this, searching for a name containing an underscore would match any character, and
     * {@code contains} of a bare {@code %} would match every row -- a caseworker typing a literal
     * character would silently get a wildcard, which is the same class of surprise as an unvalidated
     * field name even though it is not a security one.
     */
    private static final char LIKE_ESCAPE = '\\';

    private CaseSpecifications() {
    }

    /**
     * Everything a saved-query filter and a field-level query both admit.
     *
     * <p>The two compose with AND, which is what makes {@code ?filter=open&where=type:is:Adult} mean
     * both. The filter is still {@link ListFilters}' own criterion, translated here rather than
     * reimplemented as a condition: a bookmarked {@code ?filter=open} has to keep meaning what it meant.
     *
     * @param filter the tab, whose criterion may be absent ({@code all})
     * @param query the conditions, possibly {@link CaseQuery#NONE}
     */
    public static Specification<DeedPoll> matching(ListFilters.ListFilter filter, CaseQuery query) {
        List<Specification<DeedPoll>> parts = new ArrayList<>();
        filter.where().map(CaseSpecifications::inState).ifPresent(parts::add);
        query.conditions().stream().map(CaseSpecifications::satisfying).forEach(parts::add);
        // Unrestricted rather than an always-true predicate: "all" with no conditions is the absence of a
        // where clause, not a where clause that happens to admit everything.
        return parts.isEmpty() ? Specification.unrestricted() : Specification.allOf(parts);
    }

    /**
     * One filter tab's state criterion.
     *
     * <p><strong>A case with no state counts as open</strong>, which is why {@code NotIn} is a three-part
     * predicate and not {@code not(state in (…))}. SQL evaluates a bare {@code NOT IN} to NULL rather
     * than true for a NULL state, so the row would be dropped -- and the column is nullable with nothing
     * defaulting it. {@code DeedPollRepository.findOpenOrderByCreatedOnDesc} carries the full reasoning
     * and is the pinned statement of it; this reproduces it, and
     * {@code CaseSpecificationsTest} asserts the two agree.
     */
    public static Specification<DeedPoll> inState(ListFilters.StateWhere where) {
        return switch (where) {
            case ListFilters.In in -> (root, query, cb) -> root.get("state").in(in.states());
            case ListFilters.NotIn notIn -> (root, query, cb) -> cb.or(
                cb.isNull(root.get("state")),
                cb.not(root.get("state").in(notIn.states())));
        };
    }

    /** One condition, as a specification of its own so the composition above stays a flat list. */
    private static Specification<DeedPoll> satisfying(CaseQuery.Condition condition) {
        return (root, query, cb) -> predicate(condition, root, cb);
    }

    /**
     * One condition as a predicate, dispatched on the field's kind and then its operator.
     *
     * <p>Kind first, because the kind decides the column's type and therefore what the operator means:
     * {@code contains} is a substring of text and a membership of a multiselect, and the same word is
     * the right one for a caseworker in both.
     */
    private static Predicate predicate(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        return switch (condition.kind()) {
            case TEXT, TEXTAREA -> text(condition, root, cb);
            case DATE -> date(condition, root, cb);
            case NUMBER -> number(condition, root, cb);
            case BOOLEAN -> bool(condition, root, cb);
            case ENUM -> enumeration(condition, root, cb);
            case MULTISELECT -> multiselect(condition, root, cb);
        };
    }

    /**
     * A text condition.
     *
     * <p>{@code contains} and {@code startsWith} lower both sides, because a caseworker typing a surname
     * should not have to match the stored capitalisation. {@code is} does not: an exact match is an exact
     * match, and it is the operator to reach for when the case matters.
     *
     * <p>{@code isEmpty} also matches the empty string. To a caseworker looking at a blank cell a NULL
     * and a {@code ''} are the same blank, and a query that distinguished them would answer a question
     * nobody asked.
     */
    private static Predicate text(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        Expression<String> column = root.get(condition.fieldName());
        return switch (condition.operator()) {
            case IS -> cb.equal(column, condition.value());
            case CONTAINS -> like(cb, column, "%" + escaped(condition.value()) + "%");
            case STARTS_WITH -> like(cb, column, escaped(condition.value()) + "%");
            case IS_EMPTY -> cb.or(cb.isNull(column), cb.equal(column, ""));
            case IS_NOT_EMPTY -> cb.and(cb.isNotNull(column), cb.notEqual(column, ""));
            default -> throw unsupported(condition);
        };
    }

    /**
     * A date condition. {@code between} is inclusive of both bounds, which are known to be in order --
     * {@link CaseQuery} refuses a reversed pair rather than swapping it.
     */
    private static Predicate date(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        Expression<LocalDateTime> column = root.get(condition.fieldName());
        return switch (condition.operator()) {
            case IS -> cb.equal(column, condition.value());
            case BEFORE -> cb.lessThan(column, dateAt(condition, 0));
            case AFTER -> cb.greaterThan(column, dateAt(condition, 0));
            case BETWEEN -> cb.between(column, dateAt(condition, 0), dateAt(condition, 1));
            case IS_EMPTY -> cb.isNull(column);
            case IS_NOT_EMPTY -> cb.isNotNull(column);
            default -> throw unsupported(condition);
        };
    }

    /**
     * A number condition, compared as an exact decimal.
     *
     * <p>The column is widened rather than the value narrowed. {@code refundAmount} is a decimal and
     * {@code ageOfMinor} is an integer, and narrowing a {@link BigDecimal} to reach the second would
     * have to decide what {@code gt:7.5} means against a column that cannot hold 7.5 -- truncating it
     * to 7 answers a different question, and rejecting it at this point has no caller to tell. Widening
     * keeps the comparison exactly the one that was asked; it forgoes an index, which none of these
     * columns has.
     */
    private static Predicate number(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        Path<Number> stored = root.get(condition.fieldName());
        Expression<BigDecimal> column = cb.toBigDecimal(stored);
        return switch (condition.operator()) {
            case IS -> cb.equal(column, condition.value());
            case GREATER_THAN -> cb.greaterThan(column, numberAt(condition, 0));
            case LESS_THAN -> cb.lessThan(column, numberAt(condition, 0));
            case BETWEEN -> cb.between(column, numberAt(condition, 0), numberAt(condition, 1));
            // Asked of the column itself: a cast of NULL is NULL, but saying so through the raw path is
            // both plainer SQL and independent of how the cast handles one.
            case IS_EMPTY -> cb.isNull(stored);
            case IS_NOT_EMPTY -> cb.isNotNull(stored);
            default -> throw unsupported(condition);
        };
    }

    /**
     * A boolean condition, which is only ever {@code is}.
     *
     * <p>No {@code isEmpty}: a three-state boolean would need one, and every boolean column here is
     * either NOT NULL or read as false when absent. A nullable one asked {@code is:false} therefore does
     * not match a NULL row, which is the honest answer to "is this false" about a row that does not say.
     */
    private static Predicate bool(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        Expression<Boolean> column = root.get(condition.fieldName());
        return switch (condition.operator()) {
            case IS -> cb.equal(column, condition.value());
            default -> throw unsupported(condition);
        };
    }

    /**
     * An enum condition, bound as the column's own constant.
     *
     * <p>{@link CaseQuery} validated the value against the option set the field names and kept it as a
     * member NAME, because the domain's field metadata knows which set a field draws on and not which
     * Java enum the column is. Here the column is to hand, so the name resolves through
     * {@link Path#getJavaType()} -- which means the parameter binds as the enum Hibernate maps, and the
     * translation never has to name a single one of the ten enum types by hand.
     */
    private static Predicate enumeration(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        Path<Object> column = root.get(condition.fieldName());
        return switch (condition.operator()) {
            case IS -> cb.equal(column, member(column, condition.value()));
            case IS_ONE_OF -> column.in(condition.values().stream()
                .map(value -> member(column, value))
                .toList());
            case IS_EMPTY -> cb.isNull(column);
            case IS_NOT_EMPTY -> cb.isNotNull(column);
            default -> throw unsupported(condition);
        };
    }

    /**
     * A multiselect condition, over an {@code integer[]} column of Dynamics option values.
     *
     * <p>Expressed with Hibernate's array operators rather than raw {@code array_position} SQL, so what
     * is written here is still "does this array contain this option" and the dialect decides how to ask
     * it. {@code contains} is exact -- there is no such thing as half of an option value.
     *
     * <p>{@code isEmpty} accepts three shapes of nothing: a NULL column, an empty array, and the NULL
     * that Postgres's own {@code array_length} returns for an empty array. The entity defaults the
     * column to {@code []} and the schema permits NULL, so both really occur.
     */
    private static Predicate multiselect(CaseQuery.Condition condition, Root<DeedPoll> root, CriteriaBuilder cb) {
        HibernateCriteriaBuilder hcb = hibernate(cb);
        Expression<Integer[]> column = root.get(condition.fieldName());
        Expression<Integer> length = hcb.arrayLength(column);
        return switch (condition.operator()) {
            case CONTAINS -> hcb.arrayContains(column, optionAt(condition, 0));
            case IS_EMPTY -> cb.or(cb.isNull(column), cb.isNull(length), cb.equal(length, 0));
            // Null-safe without a matching isNull: a NULL length is not greater than zero.
            case IS_NOT_EMPTY -> cb.greaterThan(length, 0);
            default -> throw unsupported(condition);
        };
    }

    /**
     * {@code lower(column) like lower(?)}, with the caller's own wildcards already escaped.
     */
    private static Predicate like(CriteriaBuilder cb, Expression<String> column, String pattern) {
        return cb.like(cb.lower(column), cb.lower(cb.literal(pattern)), LIKE_ESCAPE);
    }

    /**
     * A value with its LIKE wildcards escaped, so a caseworker's own {@code %} or {@code _} is content.
     *
     * <p>The escape character itself is escaped first, or escaping the wildcards would produce a
     * sequence a caller could have sent to escape something of their own.
     */
    private static String escaped(Object value) {
        StringBuilder pattern = new StringBuilder();
        for (char character : String.valueOf(value).toCharArray()) {
            if (character == LIKE_ESCAPE || character == '%' || character == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(character);
        }
        return pattern.toString();
    }

    /**
     * One enum constant of the column's own type, by the name {@link CaseQuery} validated.
     *
     * <p>Matched by iterating the constants rather than through {@code Enum.valueOf}, which would need
     * an unchecked cast to a raw {@code Class<Enum>} to compile -- the same reason
     * {@code DeedPollFields.asType} iterates.
     */
    private static Object member(Path<Object> column, Object name) {
        Object[] members = column.getJavaType().getEnumConstants();
        if (members != null) {
            for (Object candidate : members) {
                if (((Enum<?>) candidate).name().equals(name)) {
                    return candidate;
                }
            }
        }
        // Unreachable: CaseQuery admits only a member of the set the field names, and FormFieldsTest pins
        // every enum field to a set whose keys are that column's constants. Loud rather than a predicate
        // that quietly matches nothing, because the two registries having drifted apart is a bug here.
        throw new IllegalStateException("No " + column.getJavaType().getSimpleName() + " member " + name);
    }

    private static LocalDateTime dateAt(CaseQuery.Condition condition, int index) {
        return (LocalDateTime) condition.values().get(index);
    }

    private static BigDecimal numberAt(CaseQuery.Condition condition, int index) {
        return (BigDecimal) condition.values().get(index);
    }

    private static Integer optionAt(CaseQuery.Condition condition, int index) {
        return (Integer) condition.values().get(index);
    }

    /**
     * Hibernate's builder, for the array operators JPA's own has no equivalent of.
     *
     * <p>A cast rather than an unwrap because that is what Hibernate publishes: its
     * {@code CriteriaBuilder} implementation IS a {@link HibernateCriteriaBuilder}, and Spring Data
     * hands the provider's own builder to a {@link Specification} untouched.
     */
    private static HibernateCriteriaBuilder hibernate(CriteriaBuilder cb) {
        return (HibernateCriteriaBuilder) cb;
    }

    /**
     * A kind and operator pairing this class has no translation for.
     *
     * <p>Only reachable if {@link QueryOperator}'s kind lists and the switches above disagree, which is
     * what {@code CaseSpecificationsTest} asserts they do not: it walks every operator of every kind and
     * translates it. A new operator therefore fails a test rather than a request.
     */
    private static IllegalStateException unsupported(CaseQuery.Condition condition) {
        return new IllegalStateException(
            "No predicate for " + condition.operator().wire() + " on a " + condition.kind() + " field");
    }
}
