package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the per-kind operator table, which is the query surface's permission model.
 *
 * <p>No reference test to port -- the Dynamics original had no field-level query. The table itself is
 * the specification, so it is asserted kind by kind rather than derived from the enum, which would only
 * assert that the code agrees with itself.
 */
class QueryOperatorTest {

    @Test
    @DisplayName("gives text the five operators a caseworker searching a name needs")
    void givesTextItsOperators() {
        assertThat(QueryOperator.wireTokensFor(FieldKind.TEXT))
            .isEqualTo("is, contains, startsWith, isEmpty, isNotEmpty");
    }

    @Test
    @DisplayName("gives a textarea the same operators as text, since it is text in a bigger box")
    void givesTextareaTheSameOperatorsAsText() {
        assertThat(QueryOperator.forKind(FieldKind.TEXTAREA))
            .isEqualTo(QueryOperator.forKind(FieldKind.TEXT));
    }

    @Test
    @DisplayName("gives a date its range operators")
    void givesDateItsOperators() {
        assertThat(QueryOperator.wireTokensFor(FieldKind.DATE))
            .isEqualTo("is, before, after, between, isEmpty, isNotEmpty");
    }

    @Test
    @DisplayName("gives a number its range operators")
    void givesNumberItsOperators() {
        assertThat(QueryOperator.wireTokensFor(FieldKind.NUMBER))
            .isEqualTo("is, gt, lt, between, isEmpty, isNotEmpty");
    }

    @Test
    @DisplayName("gives an enum membership, one value or several")
    void givesEnumItsOperators() {
        assertThat(QueryOperator.wireTokensFor(FieldKind.ENUM))
            .isEqualTo("is, isOneOf, isEmpty, isNotEmpty");
    }

    @Test
    @DisplayName("gives a multiselect only contains, since an option set has no ordering or substring")
    void givesMultiselectItsOperators() {
        assertThat(QueryOperator.wireTokensFor(FieldKind.MULTISELECT))
            .isEqualTo("contains, isEmpty, isNotEmpty");
    }

    @Test
    @DisplayName("gives a boolean only is -- there is no third state to ask isEmpty about")
    void givesBooleanOnlyIs() {
        assertThat(QueryOperator.forKind(FieldKind.BOOLEAN)).containsExactly(QueryOperator.IS);
    }

    @Test
    @DisplayName("leaves no kind without at least one operator")
    void leavesNoKindWithoutAnOperator() {
        // A kind with no operators would silently make every field of that kind unqueryable rather than
        // refusing a condition, which is the failure this table exists to prevent.
        Arrays.stream(FieldKind.values())
            .forEach(kind -> assertThat(QueryOperator.forKind(kind)).as(kind.name()).isNotEmpty());
    }

    @Nested
    @DisplayName("forWire")
    class ForWire {

        @Test
        @DisplayName("resolves the wire spelling, which is not the constant's name")
        void resolvesTheWireSpelling() {
            assertThat(QueryOperator.forWire("startsWith")).contains(QueryOperator.STARTS_WITH);
            assertThat(QueryOperator.forWire("gt")).contains(QueryOperator.GREATER_THAN);
            assertThat(QueryOperator.forWire("isOneOf")).contains(QueryOperator.IS_ONE_OF);
        }

        @Test
        @DisplayName("does not resolve the constant's own name, which is not the wire format")
        void doesNotResolveTheConstantName() {
            assertThat(QueryOperator.forWire("STARTS_WITH")).isEmpty();
            assertThat(QueryOperator.forWire("IS")).isEmpty();
        }

        @Test
        @DisplayName("matches exactly, so the wire format stays one format")
        void matchesExactly() {
            assertThat(QueryOperator.forWire("startswith")).isEmpty();
            assertThat(QueryOperator.forWire("Is")).isEmpty();
        }

        @Test
        @DisplayName("resolves nothing for an unknown token")
        void resolvesNothingForAnUnknownToken() {
            assertThat(QueryOperator.forWire("like")).isEmpty();
            assertThat(QueryOperator.forWire("")).isEmpty();
        }

        @Test
        @DisplayName("resolves every operator, so none is unreachable from the wire")
        void resolvesEveryOperator() {
            List<QueryOperator> resolved = Arrays.stream(QueryOperator.values())
                .map(operator -> QueryOperator.forWire(operator.wire()).orElseThrow())
                .toList();

            assertThat(resolved).containsExactly(QueryOperator.values());
        }
    }

    @Nested
    @DisplayName("arity")
    class ArityOf {

        @Test
        @DisplayName("takes no value for the two emptiness operators")
        void takesNoValueForEmptiness() {
            assertThat(QueryOperator.IS_EMPTY.arity()).isEqualTo(QueryOperator.Arity.NONE);
            assertThat(QueryOperator.IS_NOT_EMPTY.arity()).isEqualTo(QueryOperator.Arity.NONE);
        }

        @Test
        @DisplayName("splits on commas only for the two list operators, so a text comma is unambiguous")
        void splitsOnCommasOnlyForListOperators() {
            List<QueryOperator> listTaking = Arrays.stream(QueryOperator.values())
                .filter(operator -> operator.arity().takesList())
                .toList();

            assertThat(listTaking).containsExactly(QueryOperator.BETWEEN, QueryOperator.IS_ONE_OF);
        }

        @Test
        @DisplayName("counts what each arity accepts")
        void countsWhatEachArityAccepts() {
            assertThat(QueryOperator.Arity.NONE.accepts(0)).isTrue();
            assertThat(QueryOperator.Arity.NONE.accepts(1)).isFalse();
            assertThat(QueryOperator.Arity.ONE.accepts(1)).isTrue();
            assertThat(QueryOperator.Arity.ONE.accepts(2)).isFalse();
            assertThat(QueryOperator.Arity.TWO.accepts(2)).isTrue();
            assertThat(QueryOperator.Arity.TWO.accepts(1)).isFalse();
            assertThat(QueryOperator.Arity.TWO.accepts(3)).isFalse();
            assertThat(QueryOperator.Arity.AT_LEAST_ONE.accepts(1)).isTrue();
            assertThat(QueryOperator.Arity.AT_LEAST_ONE.accepts(4)).isTrue();
            assertThat(QueryOperator.Arity.AT_LEAST_ONE.accepts(0)).isFalse();
        }
    }
}
