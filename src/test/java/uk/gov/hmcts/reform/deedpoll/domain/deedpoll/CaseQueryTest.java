package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and validating the repeated {@code where} parameters.
 *
 * <p>No reference test to port: the Dynamics original had five saved views and no field-level query.
 * What is asserted here is the two things a validator that stands in front of a query has to get right --
 * that nothing but a published field name gets through, and that a request with three mistakes is told
 * about three.
 */
class CaseQueryTest {

    @Test
    @DisplayName("reads no parameters as no query, rather than as an error")
    void readsNoParametersAsNoQuery() {
        // A list with no query is the normal case, not a malformed request.
        assertThat(CaseQuery.parse(null)).isEqualTo(CaseQuery.NONE);
        assertThat(CaseQuery.parse(List.of())).isEqualTo(CaseQuery.NONE);
        assertThat(CaseQuery.parse(List.of()).isEmpty()).isTrue();
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads a field, an operator and a value")
        void readsAFieldAnOperatorAndAValue() {
            CaseQuery.Condition condition = only("newSurname:is:Smith");

            assertThat(condition.fieldName()).isEqualTo("newSurname");
            assertThat(condition.kind()).isEqualTo(FieldKind.TEXT);
            assertThat(condition.operator()).isEqualTo(QueryOperator.IS);
            assertThat(condition.values()).containsExactly("Smith");
            assertThat(condition.value()).isEqualTo("Smith");
        }

        @Test
        @DisplayName("keeps every condition, in the order the request sent them")
        void keepsEveryConditionInOrder() {
            CaseQuery query = CaseQuery.parse(List.of(
                "type:is:Adult", "newSurname:startsWith:sm", "dateReceived:isNotEmpty"));

            assertThat(query.conditions()).extracting(CaseQuery.Condition::fieldName)
                .containsExactly("type", "newSurname", "dateReceived");
            assertThat(query.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("splits on the first two colons only, so a colon inside a value survives")
        void splitsOnTheFirstTwoColonsOnly() {
            // The value is URL-encoded, so a colon arrives as %3A and needs no further escaping. What it
            // must not do is start a third field.
            assertThat(only("referralNotes:contains:14:30 hearing").values())
                .containsExactly("14:30 hearing");
            assertThat(only("returnReason:is:a:b:c").values()).containsExactly("a:b:c");
        }

        @Test
        @DisplayName("keeps a comma inside a single-valued text value, which is never a list")
        void keepsACommaInsideATextValue() {
            // No text operator takes a list, which is what makes this unambiguous.
            assertThat(only("parents:is:Smith, Jones").values()).containsExactly("Smith, Jones");
        }

        @Test
        @DisplayName("reads a no-value operator written with one colon")
        void readsANoValueOperatorWithOneColon() {
            CaseQuery.Condition condition = only("parents:isEmpty");

            assertThat(condition.operator()).isEqualTo(QueryOperator.IS_EMPTY);
            assertThat(condition.values()).isEmpty();
        }

        @Test
        @DisplayName("reads a no-value operator written with a trailing colon and nothing after it")
        void readsANoValueOperatorWithATrailingColon() {
            // A builder assembling `field:operator:value` from empty inputs produces this; it says the
            // same thing as the one-colon form and there is nothing to be gained by refusing it.
            assertThat(only("parents:isEmpty:").values()).isEmpty();
        }

        @Test
        @DisplayName("trims a field name and an operator, but not a value")
        void trimsAFieldNameAndAnOperatorButNotAValue() {
            // Whitespace around a name in a hand-edited URL is a nuisance; whitespace inside a search
            // value is content, and trimming it would change what was asked.
            assertThat(only(" newSurname : is : Smith ").values()).containsExactly(" Smith ");
        }

        @Test
        @DisplayName("refuses an entry with no colon at all")
        void refusesAnEntryWithNoColon() {
            assertThat(errorFor("newSurname")).isEqualTo(new FieldError("where.0",
                "must be field:operator:value, and was 'newSurname'"));
        }

        @Test
        @DisplayName("addresses each error to the condition's position, since the parameter repeats")
        void addressesEachErrorToItsPosition() {
            // A position is the only thing that identifies which of three conditions on one field was
            // wrong.
            assertThat(refusalFor("newSurname:is:Smith", "nonsense:is:x").errors())
                .extracting(FieldError::field)
                .containsExactly("where.1");
        }
    }

    @Nested
    @DisplayName("the field name")
    class TheFieldName {

        @Test
        @DisplayName("refuses a field this service never published, naming it")
        void refusesAnUnpublishedField() {
            assertThat(errorFor("shoeSize:is:9").message()).isEqualTo("names no case field: 'shoeSize'");
        }

        @Test
        @DisplayName("refuses a name crafted to look like SQL, rather than passing it to a query")
        void refusesANameCraftedToLookLikeSql() {
            FieldError error = errorFor("newSurname'); drop table \"DeedPoll\"; --:is:x");

            assertThat(error.message()).startsWith("names no case field:");
            assertThat(error.field()).isEqualTo("where.0");
        }

        @Test
        @DisplayName("quotes a long token back truncated, so the response stays bounded")
        void quotesALongTokenTruncated() {
            String enormous = "x".repeat(5000);

            assertThat(errorFor(enormous + ":is:1").message()).hasSizeLessThan(120).endsWith("…'");
        }

        @Test
        @DisplayName("queries a read-only field, because queryable is not the same as editable")
        void queriesAReadOnlyField() {
            // referencenumber and state are among the most useful things to ask about and neither is
            // editable, so a queryability check built on the write registry would refuse both.
            assertThat(only("referencenumber:is:1234").field().readOnly()).isTrue();
            assertThat(only("state:is:Enroled").field().readOnly()).isTrue();
            assertThat(DeedPollFields.isWritable("referencenumber")).isFalse();
        }

        @Test
        @DisplayName("refuses a derived field, which has no column to query")
        void refusesADerivedField() {
            // The two day counts are computed at read time from the dates either side of them.
            assertThat(errorFor("daysReceivedToEnroled:is:5").message())
                .isEqualTo("cannot be queried: 'daysReceivedToEnroled' is derived, not stored");
        }

        @Test
        @DisplayName("accepts every stored form field, so the queryable set is the form minus the two counts")
        void acceptsEveryStoredFormField() {
            List<String> queryable = FormFields.allFields().stream()
                .map(FieldDef::name)
                .filter(name -> DeedPollFields.readableFields().contains(name))
                .toList();

            assertThat(FormFields.allFields()).hasSize(70);
            assertThat(queryable).hasSize(68)
                .doesNotContain("daysReceivedToEnroled", "daysReceivedToReturned");
            FormFields.allFields().stream()
                .filter(field -> queryable.contains(field.name()))
                .forEach(field -> assertThat(CaseQuery.parse(List.of(anyConditionOn(field))).conditions())
                    .as(field.name())
                    .hasSize(1));
        }

        /** A condition every kind accepts -- a boolean has no emptiness to ask about. */
        private static String anyConditionOn(FieldDef field) {
            return field.kind() == FieldKind.BOOLEAN
                ? field.name() + ":is:true"
                : field.name() + ":isNotEmpty";
        }
    }

    @Nested
    @DisplayName("the operator")
    class TheOperator {

        @Test
        @DisplayName("refuses a token that is not an operator")
        void refusesATokenThatIsNotAnOperator() {
            assertThat(errorFor("newSurname:like:Sm%").message()).isEqualTo("names no operator: 'like'");
        }

        @Test
        @DisplayName("refuses an operator the field's kind does not take, listing the ones it does")
        void refusesAnOperatorTheKindDoesNotTake() {
            assertThat(errorFor("dateReceived:contains:2026").message()).isEqualTo(
                "contains does not apply to dateReceived, which takes: is, before, after, between, "
                    + "isEmpty, isNotEmpty");
        }

        @Test
        @DisplayName("refuses isEmpty on a boolean, which has no third state")
        void refusesIsEmptyOnABoolean() {
            assertThat(errorFor("inPerson:isEmpty").message())
                .isEqualTo("isEmpty does not apply to inPerson, which takes: is");
        }

        @Test
        @DisplayName("refuses startsWith on a multiselect, whose values are option-set integers")
        void refusesStartsWithOnAMultiselect() {
            assertThat(errorFor("iShall:startsWith:1").message())
                .isEqualTo("startsWith does not apply to iShall, which takes: contains, isEmpty, isNotEmpty");
        }
    }

    @Nested
    @DisplayName("the values")
    class TheValues {

        @Test
        @DisplayName("reads a date as a date, from an ISO date or an ISO date-time")
        void readsADateAsADate() {
            assertThat(only("dateReceived:after:2026-06-01").values())
                .containsExactly(LocalDateTime.of(2026, 6, 1, 0, 0));
            assertThat(only("dateReceived:is:2026-06-01T09:30:00").values())
                .containsExactly(LocalDateTime.of(2026, 6, 1, 9, 30));
        }

        @Test
        @DisplayName("refuses a date it cannot read, rather than matching nothing silently")
        void refusesADateItCannotRead() {
            assertThat(errorFor("dateReceived:is:last Tuesday").message())
                .isEqualTo("dateReceived takes a date, and was given 'last Tuesday'");
        }

        @Test
        @DisplayName("reads a number as a number, decimals included")
        void readsANumberAsANumber() {
            assertThat(only("refundAmount:gt:42.50").values()).containsExactly(new BigDecimal("42.50"));
            assertThat(only("referencenumber:is:1234").values()).containsExactly(new BigDecimal("1234"));
        }

        @Test
        @DisplayName("refuses a number it cannot read")
        void refusesANumberItCannotRead() {
            assertThat(errorFor("ageOfMinor:lt:twelve").message())
                .isEqualTo("ageOfMinor takes a number, and was given 'twelve'");
        }

        @Test
        @DisplayName("reads a boolean, and refuses anything that is not one")
        void readsABooleanAndRefusesAnythingElse() {
            assertThat(only("inPerson:is:true").values()).containsExactly(Boolean.TRUE);
            assertThat(only("retainPermanently:is:false").values()).containsExactly(Boolean.FALSE);
            assertThat(errorFor("inPerson:is:maybe").message())
                .isEqualTo("inPerson takes true or false, and was given 'maybe'");
        }

        @Test
        @DisplayName("keeps an enum member's name, checked against the set the field names")
        void keepsAnEnumMembersName() {
            assertThat(only("type:is:Adult").values()).containsExactly("Adult");
            assertThat(only("state:is:AwaitingSignature").values()).containsExactly("AwaitingSignature");
        }

        @Test
        @DisplayName("refuses a value that is not a member of the field's set")
        void refusesAValueOutsideTheFieldsSet() {
            assertThat(errorFor("type:is:Grown-up").message()).isEqualTo("type has no value 'Grown-up'");
            // A member of a different set is no more acceptable than an invented one.
            assertThat(errorFor("type:is:Enroled").message()).isEqualTo("type has no value 'Enroled'");
        }

        @Test
        @DisplayName("reads a multiselect option as the integer the column stores")
        void readsAMultiselectOptionAsAnInteger() {
            assertThat(only("iShall:contains:3").values()).containsExactly(Integer.valueOf(3));
        }

        @Test
        @DisplayName("refuses a multiselect option the set does not have")
        void refusesAMultiselectOptionTheSetDoesNotHave() {
            assertThat(errorFor("iShall:contains:9").message()).isEqualTo("iShall has no option '9'");
            assertThat(errorFor("iShall:contains:and my wife").message())
                .isEqualTo("iShall has no option 'and my wife'");
        }

        @Test
        @DisplayName("refuses a blank value, since isEmpty is the operator that means blank")
        void refusesABlankValue() {
            assertThat(errorFor("newSurname:is:").message())
                .isEqualTo("is takes one value, and was given 0");
            assertThat(errorFor("newSurname:is: ").message())
                .isEqualTo("newSurname was given a blank value; use isEmpty to match a blank");
            // The blank check is the one place a text value's whitespace is not content: a builder
            // assembling `field:operator:value` from an empty control produces exactly this.
            assertThat(errorFor("type:isOneOf:Adult,").message())
                .isEqualTo("type was given a blank value; use isEmpty to match a blank");
        }

        @Test
        @DisplayName("refuses a value given to an operator that takes none")
        void refusesAValueGivenToANoValueOperator() {
            assertThat(errorFor("parents:isEmpty:x").message())
                .isEqualTo("isEmpty takes no value, and was given 1");
        }
    }

    @Nested
    @DisplayName("a list operator")
    class AListOperator {

        @Test
        @DisplayName("splits between on the comma and trims each bound")
        void splitsBetweenOnTheComma() {
            assertThat(only("dateReceived:between:2026-06-01, 2026-06-30").values()).containsExactly(
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 6, 30, 0, 0));
        }

        @Test
        @DisplayName("refuses between with one bound")
        void refusesBetweenWithOneBound() {
            assertThat(errorFor("dateReceived:between:2026-06-01").message())
                .isEqualTo("between takes two comma-separated values, low first, and was given 1");
        }

        @Test
        @DisplayName("refuses between with three bounds")
        void refusesBetweenWithThreeBounds() {
            assertThat(errorFor("ageOfMinor:between:1,2,3").message())
                .isEqualTo("between takes two comma-separated values, low first, and was given 3");
        }

        @Test
        @DisplayName("refuses reversed bounds rather than swapping them")
        void refusesReversedBounds() {
            // Silently reordering would answer a different question from the one asked; the caller has
            // almost certainly filled two controls the wrong way round.
            assertThat(errorFor("dateReceived:between:2026-06-30,2026-06-01").message())
                .isEqualTo("between takes its bounds low first, and they are reversed");
            assertThat(errorFor("refundAmount:between:100,10").message())
                .isEqualTo("between takes its bounds low first, and they are reversed");
        }

        @Test
        @DisplayName("accepts equal bounds, which is a single-day range")
        void acceptsEqualBounds() {
            assertThat(only("dateReceived:between:2026-06-01,2026-06-01").values()).hasSize(2);
        }

        @Test
        @DisplayName("reads isOneOf as one or many members")
        void readsIsOneOfAsOneOrManyMembers() {
            assertThat(only("state:isOneOf:Received").values()).containsExactly("Received");
            assertThat(only("type:isOneOf:Adult, Minor,MinorTransgender").values())
                .containsExactly("Adult", "Minor", "MinorTransgender");
        }

        @Test
        @DisplayName("refuses isOneOf with no members, and names the bad member when there is one")
        void refusesIsOneOfWithNoOrBadMembers() {
            assertThat(errorFor("type:isOneOf").message())
                .isEqualTo("isOneOf takes at least one comma-separated value, and was given 0");
            assertThat(errorFor("type:isOneOf:Adult,Grown-up").message())
                .isEqualTo("type has no value 'Grown-up'");
        }
    }

    @Nested
    @DisplayName("reporting more than one mistake")
    class ReportingMoreThanOneMistake {

        @Test
        @DisplayName("reports every bad condition at once, not the first")
        void reportsEveryBadConditionAtOnce() {
            // A builder sends several conditions together, so failing on the first would make fixing a
            // request a round trip per mistake.
            InvalidInputError refused = refusalFor(
                "shoeSize:is:9",
                "type:is:Adult",
                "dateReceived:contains:2026",
                "iShall:contains:9");

            assertThat(refused.errors()).containsExactly(
                new FieldError("where.0", "names no case field: 'shoeSize'"),
                new FieldError("where.2",
                    "contains does not apply to dateReceived, which takes: is, before, after, between, "
                        + "isEmpty, isNotEmpty"),
                new FieldError("where.3", "iShall has no option '9'"));
            assertThat(refused.getMessage()).isEqualTo("Case query is not valid");
        }

        @Test
        @DisplayName("keeps positions absolute, so an error points at the parameter the caller sent")
        void keepsPositionsAbsolute() {
            assertThat(refusalFor("type:is:Adult", "type:is:Adult", "type:is:Grown-up").errors())
                .extracting(FieldError::field)
                .containsExactly("where.2");
        }

        @Test
        @DisplayName("caps the number of conditions, since each one is another unindexed predicate")
        void capsTheNumberOfConditions() {
            List<String> tooMany = IntStream.rangeClosed(0, CaseQuery.MAX_CONDITIONS)
                .mapToObj(index -> "type:is:Adult")
                .toList();

            assertThat(CaseQuery.parse(tooMany.subList(0, CaseQuery.MAX_CONDITIONS)).conditions())
                .hasSize(CaseQuery.MAX_CONDITIONS);
            assertThatThrownBy(() -> CaseQuery.parse(tooMany))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Case query is not valid");
            assertThat(refusalFor(tooMany.toArray(new String[0])).errors()).containsExactly(
                new FieldError("where", "takes at most 20 conditions, and was given 21"));
        }
    }

    @Nested
    @DisplayName("a condition")
    class AConditionValue {

        @Test
        @DisplayName("holds the field definition this service wrote, not the caller's string")
        void holdsTheFieldDefinitionThisServiceWrote() {
            // What makes a field name unable to reach a query as text: the only way to have a Condition
            // is to have had a name resolve to a FieldDef.
            assertThat(only("type:is:Adult").field()).isSameAs(FormFields.FIELD_BY_NAME.get("type"));
        }

        @Test
        @DisplayName("cannot have its values changed after parsing")
        void cannotHaveItsValuesChangedAfterParsing() {
            List<Object> values = new ArrayList<>(List.of("Smith"));
            CaseQuery.Condition condition = new CaseQuery.Condition(
                FormFields.FIELD_BY_NAME.get("newSurname"), QueryOperator.IS, values);

            values.add("Jones");

            assertThat(condition.values()).containsExactly("Smith");
        }
    }

    /** The one condition a single-entry query parsed to. */
    private static CaseQuery.Condition only(String where) {
        CaseQuery query = CaseQuery.parse(List.of(where));
        assertThat(query.conditions()).hasSize(1);
        return query.conditions().get(0);
    }

    /** The refusal a query provoked, failing the test if it did not provoke one. */
    private static InvalidInputError refusalFor(String... where) {
        try {
            CaseQuery.parse(List.of(where));
        } catch (InvalidInputError refused) {
            return refused;
        }
        throw new AssertionError("expected a refusal for " + List.of(where));
    }

    /** The single error a one-entry query was refused with. */
    private static FieldError errorFor(String where) {
        List<FieldError> errors = refusalFor(where).errors();
        assertThat(errors).hasSize(1);
        return errors.get(0);
    }
}
