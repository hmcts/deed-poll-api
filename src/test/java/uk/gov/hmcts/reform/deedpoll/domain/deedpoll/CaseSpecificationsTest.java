package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The translation from a validated {@link CaseQuery} into SQL, against a real Postgres.
 *
 * <p><strong>A real database rather than an assertion about the predicate tree.</strong> Every
 * interesting claim here is one about SQL's semantics and not the application's: that {@code lower(col)
 * like lower(?)} is case-insensitive, that a caseworker's own {@code %} is not a wildcard, that
 * {@code NOT IN} drops a NULL row, that an empty {@code integer[]} has no length. A test asserting the
 * shape of a {@code Predicate} would pass on every one of those being wrong.
 *
 * <p>Results are narrowed to the rows each test persisted. Other suites commit cases into the same
 * database, so a size assertion over the whole table would be a test of what else has run.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseSpecificationsTest extends AbstractPostgresContainerTest {

    /** The "all" tab: no state criterion, so a condition is the only thing narrowing the result. */
    private static final ListFilters.ListFilter ALL = ListFilters.filterFor("all");

    /**
     * The states "open" excludes, as {@code ListFilters} defines it.
     */
    private static final Set<DeedPollState> FINISHED = EnumSet.of(DeedPollState.Enroled, DeedPollState.Returned);

    /**
     * One queryable field per kind, and a value the field will really take.
     *
     * <p>Drives the sweep that every operator of every kind translates, so it names a field of each kind
     * rather than one field: the translation dispatches on the kind first.
     */
    private static final Map<FieldKind, String> REPRESENTATIVE = Map.of(
        FieldKind.TEXT, "newForename",
        FieldKind.TEXTAREA, "referralNotes",
        FieldKind.DATE, "dateReceived",
        FieldKind.NUMBER, "ageOfMinor",
        FieldKind.BOOLEAN, "inPerson",
        FieldKind.ENUM, "type",
        FieldKind.MULTISELECT, "iShall");

    @Autowired
    private DeedPollRepository deedPolls;

    @PersistenceContext
    private EntityManager entityManager;

    /** The ids this test persisted, so an assertion is never about another suite's committed rows. */
    private final Set<String> mine = new LinkedHashSet<>();

    /**
     * A persisted case with the given fields set, by the names {@link DeedPollFields} publishes.
     *
     * <p>Written through the field registry rather than the setters so a fixture reads as the same
     * vocabulary the query does -- a condition on {@code newForename} is tested against a row whose
     * {@code newForename} was set by that name.
     */
    private DeedPoll persisted(Object... fields) {
        DeedPoll deedPoll = new DeedPoll();
        for (int i = 0; i < fields.length; i += 2) {
            DeedPollFields.write(deedPoll, String.valueOf(fields[i]), fields[i + 1]);
        }
        entityManager.persist(deedPoll);
        entityManager.flush();
        mine.add(deedPoll.getId());
        return deedPoll;
    }

    /** The ids of this test's own rows that the given conditions admit, under the "all" tab. */
    private List<String> matching(String... where) {
        return matching(ALL, where);
    }

    /**
     * As {@link #matching(String...)}, but composed with one filter tab.
     */
    private List<String> matching(ListFilters.ListFilter filter, String... where) {
        return deedPolls.findAll(CaseSpecifications.matching(filter, CaseQuery.parse(List.of(where)))).stream()
            .map(DeedPoll::getId)
            .filter(mine::contains)
            .toList();
    }

    @Nested
    @DisplayName("every operator translates")
    class EveryOperator {

        /**
         * The whole of decision 5's table, executed.
         *
         * <p>Walks every operator {@link QueryOperator} permits for a kind and runs it against the
         * database. It asserts nothing about the rows -- what it catches is a kind/operator pairing the
         * translation has no case for, which would otherwise reach a caller as a 500 for a condition the
         * validation deliberately allowed. Extending {@link QueryOperator} without extending
         * {@code CaseSpecifications} fails here rather than in production.
         */
        @ParameterizedTest
        @EnumSource(FieldKind.class)
        @DisplayName("every operator permitted for a kind has a predicate")
        void translatesEveryOperatorOfEveryKind(FieldKind kind) {
            String field = REPRESENTATIVE.get(kind);
            List<QueryOperator> operators = QueryOperator.forKind(kind);

            assertThat(operators).isNotEmpty();
            for (QueryOperator operator : operators) {
                String where = field + ":" + operator.wire() + valuePartFor(kind, operator);
                assertThat(matching(where)).as(where).isEmpty();
            }
        }

        /** A value part the field's kind and the operator's arity will both accept. */
        private String valuePartFor(FieldKind kind, QueryOperator operator) {
            return switch (operator.arity()) {
                case NONE -> "";
                case ONE -> ":" + singleValueFor(kind);
                case TWO -> ":" + singleValueFor(kind) + "," + singleValueFor(kind);
                case AT_LEAST_ONE -> ":" + singleValueFor(kind);
            };
        }

        private String singleValueFor(FieldKind kind) {
            return switch (kind) {
                case TEXT, TEXTAREA -> "Nobody";
                case DATE -> "2026-01-01T00:00:00.000Z";
                case NUMBER -> "7";
                case BOOLEAN -> "true";
                case ENUM -> DeedPollType.Adult.name();
                case MULTISELECT -> "1";
            };
        }
    }

    @Nested
    @DisplayName("text")
    class TextFields {

        @Test
        @DisplayName("is matches exactly, and is not case-insensitive")
        void isMatchesExactly() {
            DeedPoll exact = persisted("newForename", "Alexandra");
            persisted("newForename", "alexandra");

            // The one text operator that is case-sensitive, and deliberately: `is` is what a caller reaches
            // for when the capitalisation is part of the question. `contains` is the case-insensitive one.
            assertThat(matching("newForename:is:Alexandra")).containsExactly(exact.getId());
        }

        @Test
        @DisplayName("contains and startsWith ignore case in both directions")
        void containsIgnoresCase() {
            DeedPoll stored = persisted("newSurname", "MacDonald");

            assertThat(matching("newSurname:contains:cdon")).containsExactly(stored.getId());
            assertThat(matching("newSurname:contains:CDON")).containsExactly(stored.getId());
            assertThat(matching("newSurname:startsWith:macd")).containsExactly(stored.getId());
            assertThat(matching("newSurname:startsWith:MACD")).containsExactly(stored.getId());
        }

        @Test
        @DisplayName("startsWith anchors at the start")
        void startsWithAnchors() {
            DeedPoll stored = persisted("newSurname", "Donaldson");

            assertThat(matching("newSurname:startsWith:Donald")).containsExactly(stored.getId());
            assertThat(matching("newSurname:startsWith:onald")).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"100%", "a_b"})
        @DisplayName("a caseworker's own LIKE wildcards are content, not wildcards")
        void escapesLikeWildcards(String stored) {
            // The failure this prevents: `contains:100%` would otherwise match "1000 Something" too, and
            // `contains:a_b` would match "axb". A caseworker typing a character got a wildcard.
            DeedPoll literal = persisted("newForename", stored);
            persisted("newForename", "1000 Others");
            persisted("newForename", "axb");

            assertThat(matching("newForename:contains:" + stored)).containsExactly(literal.getId());
            assertThat(matching("newForename:is:" + stored)).containsExactly(literal.getId());
        }

        @Test
        @DisplayName("a value containing a colon or a comma survives into the predicate")
        void keepsDelimitersInsideAValue() {
            // Split on the FIRST TWO colons only, and no text operator takes a list -- so neither delimiter
            // is ambiguous inside a single-valued text condition.
            DeedPoll colon = persisted("referralNotes", "Ref: 12/34");
            DeedPoll comma = persisted("referralNotes", "Smith, A");

            assertThat(matching("referralNotes:is:Ref: 12/34")).containsExactly(colon.getId());
            assertThat(matching("referralNotes:contains:Smith, A")).containsExactly(comma.getId());
        }

        @Test
        @DisplayName("isEmpty treats a null and a blank string as the same blank")
        void isEmptyMatchesNullAndEmpty() {
            DeedPoll unset = persisted("dateReceived", received(1));
            DeedPoll blank = persisted("newForename", "");
            DeedPoll populated = persisted("newForename", "Ada");

            assertThat(matching("newForename:isEmpty"))
                .containsExactlyInAnyOrder(unset.getId(), blank.getId());
            assertThat(matching("newForename:isNotEmpty")).containsExactly(populated.getId());
        }
    }

    @Nested
    @DisplayName("date")
    class DateFields {

        @Test
        @DisplayName("before and after are strict, and between includes both bounds")
        void comparesDates() {
            DeedPoll first = persisted("dateReceived", received(1));
            DeedPoll second = persisted("dateReceived", received(2));
            DeedPoll third = persisted("dateReceived", received(3));

            assertThat(matching("dateReceived:before:" + iso(2))).containsExactly(first.getId());
            assertThat(matching("dateReceived:after:" + iso(2))).containsExactly(third.getId());
            assertThat(matching("dateReceived:is:" + iso(2))).containsExactly(second.getId());
            assertThat(matching("dateReceived:between:" + iso(1) + "," + iso(3)))
                .containsExactlyInAnyOrder(first.getId(), second.getId(), third.getId());
        }

        @Test
        @DisplayName("isEmpty matches a null date")
        void isEmptyMatchesANullDate() {
            DeedPoll unset = persisted("newForename", "Ada");
            DeedPoll dated = persisted("dateReceived", received(4));

            assertThat(matching("dateReceived:isEmpty")).containsExactly(unset.getId());
            assertThat(matching("dateReceived:isNotEmpty")).containsExactly(dated.getId());
        }
    }

    @Nested
    @DisplayName("number")
    class NumberFields {

        @Test
        @DisplayName("gt and lt are strict, and between includes both bounds")
        void comparesNumbers() {
            DeedPoll seven = persisted("ageOfMinor", 7);
            DeedPoll eight = persisted("ageOfMinor", 8);
            DeedPoll nine = persisted("ageOfMinor", 9);

            assertThat(matching("ageOfMinor:is:8")).containsExactly(eight.getId());
            assertThat(matching("ageOfMinor:gt:8")).containsExactly(nine.getId());
            assertThat(matching("ageOfMinor:lt:8")).containsExactly(seven.getId());
            assertThat(matching("ageOfMinor:between:7,8"))
                .containsExactlyInAnyOrder(seven.getId(), eight.getId());
        }

        @Test
        @DisplayName("a fractional bound against a whole-number column compares, rather than truncating")
        void comparesAFractionalBoundExactly() {
            // The reason the column is widened to a decimal rather than the value narrowed to an integer:
            // narrowing 7.5 would have to pick 7 or 8, and either answers a question the caller did not ask.
            DeedPoll seven = persisted("ageOfMinor", 7);
            DeedPoll eight = persisted("ageOfMinor", 8);

            assertThat(matching("ageOfMinor:gt:7.5")).containsExactly(eight.getId());
            assertThat(matching("ageOfMinor:lt:7.5")).containsExactly(seven.getId());
            assertThat(matching("ageOfMinor:is:7.5")).isEmpty();
        }

        @Test
        @DisplayName("a decimal column compares as a decimal")
        void comparesADecimalColumn() {
            DeedPoll refunded = persisted("refundAmount", new BigDecimal("42.50"));

            assertThat(matching("refundAmount:is:42.50")).containsExactly(refunded.getId());
            assertThat(matching("refundAmount:gt:42.49")).containsExactly(refunded.getId());
            assertThat(matching("refundAmount:gt:42.50")).isEmpty();
        }

        @Test
        @DisplayName("isEmpty matches a null number")
        void isEmptyMatchesANullNumber() {
            DeedPoll unset = persisted("newForename", "Ada");
            DeedPoll aged = persisted("ageOfMinor", 12);

            assertThat(matching("ageOfMinor:isEmpty")).containsExactly(unset.getId());
            assertThat(matching("ageOfMinor:isNotEmpty")).containsExactly(aged.getId());
        }
    }

    @Nested
    @DisplayName("boolean and enum")
    class BooleanAndEnum {

        @Test
        @DisplayName("is matches a boolean column, and a null is neither true nor false")
        void matchesABoolean() {
            DeedPoll yes = persisted("inPerson", true);
            DeedPoll no = persisted("inPerson", false);
            persisted("newForename", "Unstated");

            assertThat(matching("inPerson:is:true")).containsExactly(yes.getId());
            assertThat(matching("inPerson:is:false")).containsExactly(no.getId());
        }

        @Test
        @DisplayName("an enum condition binds the column's own constant")
        void matchesAnEnum() {
            DeedPoll adult = persisted("type", DeedPollType.Adult);
            DeedPoll minor = persisted("type", DeedPollType.Minor);
            persisted("type", DeedPollType.DeedOfRelinquishment);

            assertThat(matching("type:is:Adult")).containsExactly(adult.getId());
            assertThat(matching("type:isOneOf:Adult,Minor"))
                .containsExactlyInAnyOrder(adult.getId(), minor.getId());
        }

        @Test
        @DisplayName("isEmpty matches an unset enum")
        void isEmptyMatchesAnUnsetEnum() {
            DeedPoll undecided = persisted("newForename", "Ada");
            DeedPoll decided = persisted("masterDecision", MasterDecision.Approved);

            assertThat(matching("masterDecision:isEmpty")).containsExactly(undecided.getId());
            assertThat(matching("masterDecision:isNotEmpty")).containsExactly(decided.getId());
        }
    }

    @Nested
    @DisplayName("multiselect")
    class Multiselect {

        @Test
        @DisplayName("contains asks whether the option is one of the selections")
        void containsAnOption() {
            DeedPoll wife = persisted("iShall", List.of(1, 4));
            DeedPoll alone = persisted("iShall", List.of(5));

            assertThat(matching("iShall:contains:1")).containsExactly(wife.getId());
            assertThat(matching("iShall:contains:4")).containsExactly(wife.getId());
            assertThat(matching("iShall:contains:5")).containsExactly(alone.getId());
        }

        @Test
        @DisplayName("an empty selection is empty, whether it is stored as an empty array or not at all")
        void isEmptyMatchesBothShapesOfNothing() {
            // Both really occur: the entity defaults the column to [], and the schema permits NULL.
            DeedPoll defaulted = persisted("newForename", "Ada");
            DeedPoll nulled = persisted("newForename", "Grace");
            nulled.setIShall(null);
            entityManager.flush();
            DeedPoll selected = persisted("iShall", List.of(2));

            assertThat(matching("iShall:isEmpty"))
                .containsExactlyInAnyOrder(defaulted.getId(), nulled.getId());
            assertThat(matching("iShall:isNotEmpty")).containsExactly(selected.getId());
        }
    }

    @Nested
    @DisplayName("composition")
    class Composition {

        @Test
        @DisplayName("a filter and a query both apply")
        void appliesTheFilterAndTheQuery() {
            // Decision 3: filter=open&where=type:is:Adult means both. The tabs are ported system views and
            // are not reimplemented as conditions, so this is the assertion that they still narrow.
            DeedPoll openAdult = persisted("type", DeedPollType.Adult, "state", DeedPollState.Received);
            persisted("type", DeedPollType.Adult, "state", DeedPollState.Enroled);
            persisted("type", DeedPollType.Minor, "state", DeedPollState.Received);

            assertThat(matching(ListFilters.filterFor("open"), "type:is:Adult"))
                .containsExactly(openAdult.getId());
        }

        @Test
        @DisplayName("several conditions combine with AND")
        void combinesConditionsWithAnd() {
            DeedPoll both = persisted("type", DeedPollType.Adult, "newSurname", "Wren", "ageOfMinor", 9);
            persisted("type", DeedPollType.Adult, "newSurname", "Doe", "ageOfMinor", 9);
            persisted("type", DeedPollType.Minor, "newSurname", "Wren", "ageOfMinor", 9);

            assertThat(matching("type:is:Adult", "newSurname:is:Wren", "ageOfMinor:gt:8"))
                .containsExactly(both.getId());
        }

        @Test
        @DisplayName("no filter criterion and no condition is an unrestricted query")
        void unrestrictedWithNeither() {
            DeedPoll only = persisted("newForename", "Ada");

            assertThat(matching()).containsExactly(only.getId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"all", "open", "received", "enroled", "returned"})
        @DisplayName("each tab's specification admits exactly what its named finder does")
        void agreesWithTheNamedFinders(String key) {
            // The specification restates the state criterion the named finders encode, and the subtle half
            // of it is that a case with NO state counts as open -- a bare NOT IN evaluates to NULL for a
            // NULL state and drops the row. This pins the two statements of that rule together, so the
            // duplication cannot drift without failing.
            persisted("state", DeedPollState.Received);
            persisted("state", DeedPollState.Enroled);
            persisted("state", DeedPollState.Returned);
            persisted("state", DeedPollState.AwaitingSignature);
            persisted("newForename", "No state at all");

            ListFilters.ListFilter filter = ListFilters.filterFor(key);
            List<String> byFinder = namedFinderIds(filter);

            assertThat(matching(filter)).containsExactlyInAnyOrderElementsOf(byFinder);
        }

        /** The same tab answered by the repository's own finders, narrowed to this test's rows. */
        private List<String> namedFinderIds(ListFilters.ListFilter filter) {
            List<DeedPoll> found = filter.where()
                .map(this::byNamedFinder)
                .orElseGet(deedPolls::findAllByOrderByCreatedOnDesc);
            return found.stream().map(DeedPoll::getId).filter(mine::contains).toList();
        }

        private List<DeedPoll> byNamedFinder(ListFilters.StateWhere where) {
            return switch (where) {
                case ListFilters.In in -> deedPolls.findByStateInOrderByCreatedOnDesc(in.states());
                case ListFilters.NotIn notIn -> deedPolls.findOpenOrderByCreatedOnDesc(notIn.states());
            };
        }
    }

    @Nested
    @DisplayName("the open tab")
    class OpenTab {

        @Test
        @DisplayName("a case with no state is open, and a finished one is not")
        void treatsANullStateAsOpen() {
            DeedPoll noState = persisted("newForename", "Ada");
            DeedPoll active = persisted("state", DeedPollState.AwaitingSignature);
            persisted("state", DeedPollState.Enroled);

            assertThat(deedPolls.findAll(CaseSpecifications.inState(new ListFilters.NotIn(FINISHED))).stream()
                .map(DeedPoll::getId)
                .filter(mine::contains)
                .toList())
                .containsExactlyInAnyOrder(noState.getId(), active.getId());
        }
    }

    private static LocalDateTime received(int day) {
        return LocalDateTime.of(2026, 3, day, 9, 0);
    }

    private static String iso(int day) {
        return received(day).toString();
    }
}
