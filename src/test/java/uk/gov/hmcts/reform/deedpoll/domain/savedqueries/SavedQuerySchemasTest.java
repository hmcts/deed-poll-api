package uk.gov.hmcts.reform.deedpoll.domain.savedqueries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseQuery;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CaseSort;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ListFilters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validating a submitted saved query.
 *
 * <p>No reference test to port -- saved queries are new behaviour rather than something the TypeScript store
 * had. What is asserted here is mostly a negative: that this class does <em>not</em> validate a condition, an
 * order or a filter key itself. Every such assertion is written against a value whose refusal message comes
 * verbatim from {@link CaseQuery}, {@link CaseSort} or {@code ListFilters}, so reimplementing any of them
 * later -- even correctly -- fails these tests. That is the point of writing them this way.
 *
 * <p>Plain unit tests with no container, deliberately: the Jenkins agent has no Docker and excludes every
 * container-backed class, so the permission and validation logic that CI actually runs is the logic asserted
 * here.
 */
class SavedQuerySchemasTest {

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The failures a rejection carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(Runnable parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    @Nested
    @DisplayName("creating")
    class Creating {

        @Test
        @DisplayName("accepts a whole query, storing the conditions as the wire strings the URL carried")
        void acceptsAWholeQuery() {
            CreateSavedQueryInput created = SavedQuerySchemas.parseCreateSavedQuery(payload(
                "name", "Enroled this month — Ops",
                "description", "What the Ops team watches",
                "shared", true,
                "filter", "enroled",
                "conditions", List.of("type:is:Adult", "dateReceived:after:2026-06-01"),
                "sortField", "dateReceived",
                "sortDirection", "desc",
                "searchTerm", "Smith"));

            assertThat(created.name()).isEqualTo("Enroled this month — Ops");
            assertThat(created.description()).contains("What the Ops team watches");
            assertThat(created.shared()).isTrue();
            assertThat(created.definition().filter()).contains("enroled");
            // Unchanged, and not structured objects: this is what lets CaseQuery.parse validate a stored
            // query with no adapter, and what makes a saved query round-trip a list URL.
            assertThat(created.definition().conditions())
                .containsExactly("type:is:Adult", "dateReceived:after:2026-06-01");
            assertThat(created.definition().sort())
                .contains(new CaseSort("dateReceived", CaseSort.Direction.DESCENDING));
            assertThat(created.definition().searchTerm()).contains("Smith");
        }

        @Test
        @DisplayName("accepts a query that narrows nothing, since 'everything, newest first' is a view")
        void acceptsAQueryThatNarrowsNothing() {
            CreateSavedQueryInput created =
                SavedQuerySchemas.parseCreateSavedQuery(payload("name", "Everything"));

            assertThat(created.definition()).isEqualTo(SavedQueryDefinition.NONE);
            assertThat(created.shared()).isFalse();
            assertThat(created.description()).isEmpty();
        }

        @Test
        @DisplayName("requires a name, and reads a blank one as none")
        void requiresAName() {
            for (Object missing : new Object[] {null, "", "   "}) {
                Map<String, Object> raw = payload("name", missing);
                assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(raw)))
                    .as("a name of '%s'", missing)
                    .containsExactly(new FieldError("name", "name is required"));
            }
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(payload())))
                .containsExactly(new FieldError("name", "name is required"));
        }

        @Test
        @DisplayName("trims a name and stores it trimmed, so a padded one cannot render blank")
        void trimsAName() {
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(payload("name", "  Ops  ")).name())
                .isEqualTo("Ops");
        }

        @Test
        @DisplayName("bounds the name, because it renders inside another service's navigation bar")
        void boundsTheName() {
            String tooLong = "n".repeat(SavedQuerySchemas.MAX_NAME_LENGTH + 1);
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(payload("name", tooLong))))
                .containsExactly(new FieldError("name", "name must be 100 characters or fewer"));

            String atTheLimit = "n".repeat(SavedQuerySchemas.MAX_NAME_LENGTH);
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(payload("name", atTheLimit)).name())
                .isEqualTo(atTheLimit);
        }

        @Test
        @DisplayName("bounds the description, and reads a blank one as none")
        void boundsTheDescription() {
            String tooLong = "d".repeat(SavedQuerySchemas.MAX_DESCRIPTION_LENGTH + 1);
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "description", tooLong))))
                .containsExactly(
                    new FieldError("description", "description must be 500 characters or fewer"));

            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "description", "  ")).description()).isEmpty();
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "description", null)).description()).isEmpty();
        }

        @Test
        @DisplayName("refuses a non-boolean shared rather than reading a truthy string")
        void refusesANonBooleanShared() {
            // Publishing is visible to every other caseworker, so "false" quietly read as true would be a
            // disclosure rather than a typo.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "shared", "true"))))
                .containsExactly(new FieldError("shared", "shared must be true or false"));
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "shared", null))))
                .containsExactly(new FieldError("shared", "shared cannot be null"));
        }

        @Test
        @DisplayName("strips the fields a caller does not own, so a query cannot be posted as somebody else's")
        void stripsTheFieldsACallerDoesNotOwn() {
            CreateSavedQueryInput created = SavedQuerySchemas.parseCreateSavedQuery(payload(
                "name", "Ops",
                "ownerId", "somebody-else",
                "ownerName", "Somebody Else",
                "id", "11111111-1111-1111-1111-111111111111",
                "page", 4,
                "createdOn", "2026-01-01T00:00:00"));

            // Nowhere for any of them to land: stripped structurally rather than by a rule to remember.
            assertThat(created.name()).isEqualTo("Ops");
            assertThat(created.definition()).isEqualTo(SavedQueryDefinition.NONE);
        }

        @Test
        @DisplayName("reports every offending field at once, not the first")
        void reportsEveryOffendingFieldAtOnce() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(payload(
                "name", "",
                "filter", "nonsense",
                "conditions", List.of("shoeSize:is:9"),
                "sortField", "shoeSize"))))
                .containsExactly(
                    new FieldError("name", "name is required"),
                    new FieldError("filter", "filter names no case list filter: 'nonsense'"),
                    new FieldError("where.0", "names no case field: 'shoeSize'"),
                    new FieldError("sort", "names no sortable case field: 'shoeSize'"));
        }

        @Test
        @DisplayName("carries one summary message for the whole refusal")
        void carriesOneSummaryMessage() {
            assertThatThrownBy(() -> SavedQuerySchemas.parseCreateSavedQuery(payload()))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Saved query is not valid");
        }
    }

    @Nested
    @DisplayName("the filter key, resolved through ListFilters")
    class Filter {

        @Test
        @DisplayName("accepts every filter the case list offers")
        void acceptsEveryFilterTheCaseListOffers() {
            // Read from LIST_FILTERS rather than listed here, so a filter added later is covered without
            // this test being remembered.
            ListFilters.LIST_FILTERS.forEach(filter ->
                assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                    payload("name", "Ops", "filter", filter.key())).definition().filter())
                    .as("filter %s", filter.key())
                    .contains(filter.key()));
        }

        @Test
        @DisplayName("refuses an unknown key, where the case list falls back to 'all'")
        void refusesAnUnknownKey() {
            // Different situations, not an inconsistency: a bookmarked list URL gets a safe answer, whereas
            // a saved query silently meaning "all" is a wrong view presented as a saved one -- and there is
            // somebody in the act of saving it who can be told.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "filter", "enrolled"))))
                .containsExactly(new FieldError("filter", "filter names no case list filter: 'enrolled'"));
        }

        @Test
        @DisplayName("truncates the key it quotes back, so a refusal stays bounded")
        void truncatesTheKeyItQuotesBack() {
            String enormous = "x".repeat(500);
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "filter", enormous))))
                .containsExactly(new FieldError("filter",
                    "filter names no case list filter: '" + "x".repeat(60) + "…'"));
        }

        @Test
        @DisplayName("reads an absent or blank filter as none, which is not the same as 'all'")
        void readsAnAbsentFilterAsNone() {
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops")).definition().filter()).isEmpty();
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "filter", "  ")).definition().filter()).isEmpty();
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "filter", "all")).definition().filter()).contains("all");
        }
    }

    @Nested
    @DisplayName("the conditions, validated by CaseQuery itself")
    class Conditions {

        @Test
        @DisplayName("refuses a condition with CaseQuery's own message, addressed to its position")
        void refusesAConditionWithCaseQuerysOwnMessage() {
            // Every string here is verbatim CaseQuery's, so a second parser written later -- even a correct
            // one -- fails this.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(payload(
                "name", "Ops",
                "conditions", List.of("type:is:Adult", "dateReceived:contains:2026", "iShall:contains:9"))))
            ).containsExactly(
                new FieldError("where.1",
                    "contains does not apply to dateReceived, which takes: is, before, after, between, "
                        + "isEmpty, isNotEmpty"),
                new FieldError("where.2", "iShall has no option '9'"));
        }

        @Test
        @DisplayName("caps the condition count because CaseQuery caps it, not via a check here")
        void capsTheConditionCountBecauseCaseQueryDoes() {
            List<String> tooMany = IntStream.rangeClosed(0, CaseQuery.MAX_CONDITIONS)
                .mapToObj(index -> "type:is:Adult")
                .toList();

            // The message is CaseQuery's, including the number -- so the cap cannot drift from the live
            // query's, which is the whole reason the conditions are stored as wire strings.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "conditions", tooMany))))
                .containsExactly(new FieldError("where",
                    "takes at most " + CaseQuery.MAX_CONDITIONS + " conditions, and was given "
                        + tooMany.size()));

            assertThat(SavedQuerySchemas.parseCreateSavedQuery(payload(
                "name", "Ops", "conditions", tooMany.subList(0, CaseQuery.MAX_CONDITIONS)))
                .definition().conditions()).hasSize(CaseQuery.MAX_CONDITIONS);
        }

        @Test
        @DisplayName("refuses a field name crafted to look like SQL, because CaseQuery resolves the name")
        void refusesAFieldNameCraftedToLookLikeSql() {
            String injected = "newSurname'; DROP TABLE deedpoll_savedquery; --";
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "conditions", List.of(injected + ":is:x")))))
                .containsExactly(new FieldError("where.0", "names no case field: '" + injected + "'"));
        }

        @Test
        @DisplayName("refuses anything that is not a list of text")
        void refusesAnythingThatIsNotAListOfText() {
            String expected = "conditions must be a list of field:operator:value text";
            // A bare string is refused rather than read as one condition: one shape is what makes the
            // round trip back to a list URL possible.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "conditions", "type:is:Adult"))))
                .containsExactly(new FieldError("conditions", expected));
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "conditions", List.of(7)))))
                .containsExactly(new FieldError("conditions", expected));
        }

        @Test
        @DisplayName("reads an absent or empty list as asking nothing")
        void readsAnAbsentListAsAskingNothing() {
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "conditions", List.of())).definition().conditions()).isEmpty();
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops")).definition().conditions()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the order, validated by CaseSort itself")
    class Sort {

        @Test
        @DisplayName("recomposes the wire string, so CaseSort keeps its three distinct refusals")
        void recomposesTheWireString() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "shoeSize"))))
                .containsExactly(new FieldError("sort", "names no sortable case field: 'shoeSize'"));

            // A multiselect and a derived field are refused differently, which a hand-rolled
            // sortableFields() check here would have collapsed into one message.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "iShall"))))
                .containsExactly(new FieldError("sort",
                    "iShall holds several values at once and cannot be sorted"));
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "daysReceivedToEnroled"))))
                .containsExactly(new FieldError("sort",
                    "cannot sort by 'daysReceivedToEnroled': it is derived, not stored"));
        }

        @Test
        @DisplayName("refuses a direction that is not one of CaseSort's two tokens")
        void refusesAnUnknownDirection() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "newSurname", "sortDirection", "DESC"))))
                .containsExactly(new FieldError("sort",
                    "takes a direction of asc, desc, and was given 'DESC'"));
        }

        @Test
        @DisplayName("reads a field with no direction as ascending, as the parameter does")
        void readsAFieldWithNoDirectionAsAscending() {
            assertThat(SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "newSurname")).definition().sort())
                .contains(new CaseSort("newSurname", CaseSort.Direction.ASCENDING));
        }

        @Test
        @DisplayName("refuses a direction with no field, since a direction alone orders nothing")
        void refusesADirectionWithNoField() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortDirection", "desc"))))
                .containsExactly(new FieldError("sortField",
                    "sortField is required when sortDirection is given"));
        }

        @Test
        @DisplayName("stores no order at all where none was named, rather than pinning today's default")
        void storesNoOrderWhereNoneWasNamed() {
            SavedQueryDefinition definition =
                SavedQuerySchemas.parseCreateSavedQuery(payload("name", "Ops")).definition();

            assertThat(definition.sort()).isEmpty();
            assertThat(definition.sortField()).isNull();
            assertThat(definition.sortDirection()).isNull();
        }

        @Test
        @DisplayName("decomposes an order into the two columns it is stored as")
        void decomposesAnOrderIntoTheTwoColumns() {
            SavedQueryDefinition definition = SavedQuerySchemas.parseCreateSavedQuery(
                payload("name", "Ops", "sortField", "dateReceived", "sortDirection", "desc")).definition();

            assertThat(definition.sortField()).isEqualTo("dateReceived");
            // The wire token, not the constant's name -- what CaseSort.parse will read back.
            assertThat(definition.sortDirection()).isEqualTo("desc");
        }
    }

    @Nested
    @DisplayName("patching")
    class Patching {

        @Test
        @DisplayName("accepts an empty patch, which changes nothing")
        void acceptsAnEmptyPatch() {
            UpdateSavedQueryInput patch = SavedQuerySchemas.parseUpdateSavedQuery(payload());

            assertThat(patch).isEqualTo(UpdateSavedQueryInput.EMPTY);
            assertThat(patch.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("distinguishes absent from cleared for every nullable field")
        void distinguishesAbsentFromCleared() {
            UpdateSavedQueryInput cleared = SavedQuerySchemas.parseUpdateSavedQuery(payload(
                "description", null,
                "filter", null,
                "sortField", null,
                "searchTerm", null));

            assertThat(cleared.description().isCleared()).isTrue();
            assertThat(cleared.filter().isCleared()).isTrue();
            assertThat(cleared.sort().isCleared()).isTrue();
            assertThat(cleared.searchTerm().isCleared()).isTrue();
            // Absent, not cleared: not mentioning a field must leave it exactly as it is.
            assertThat(cleared.name().present()).isFalse();
            assertThat(cleared.conditions().present()).isFalse();
            assertThat(cleared.shared().present()).isFalse();
        }

        @Test
        @DisplayName("reads a blanked description or search term as a clear, not as an empty string")
        void readsABlankedTextFieldAsAClear() {
            UpdateSavedQueryInput patch = SavedQuerySchemas.parseUpdateSavedQuery(
                payload("description", "  ", "searchTerm", ""));

            assertThat(patch.description().isCleared()).isTrue();
            assertThat(patch.searchTerm().isCleared()).isTrue();
        }

        @Test
        @DisplayName("refuses a null where the column has none, naming the field rather than the constraint")
        void refusesANullWhereTheColumnHasNone() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload("name", null))))
                .containsExactly(new FieldError("name", "name cannot be null"));
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload("shared", null))))
                .containsExactly(new FieldError("shared", "shared cannot be null"));
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload("conditions", null))))
                .containsExactly(new FieldError("conditions",
                    "conditions cannot be null; send an empty list instead"));
        }

        @Test
        @DisplayName("applies the create rules to whatever is present")
        void appliesTheCreateRulesToWhateverIsPresent() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload(
                "name", "n".repeat(SavedQuerySchemas.MAX_NAME_LENGTH + 1),
                "filter", "nonsense",
                "conditions", List.of("shoeSize:is:9"),
                "sortField", "iShall"))))
                .containsExactly(
                    new FieldError("name", "name must be 100 characters or fewer"),
                    new FieldError("filter", "filter names no case list filter: 'nonsense'"),
                    new FieldError("where.0", "names no case field: 'shoeSize'"),
                    new FieldError("sort", "iShall holds several values at once and cannot be sorted"));
        }

        @Test
        @DisplayName("reads a blanked name as required rather than as a clear")
        void readsABlankedNameAsRequired() {
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload("name", "  "))))
                .containsExactly(new FieldError("name", "name is required"));
        }

        @Test
        @DisplayName("patches an order as one value, since two columns hold one thing")
        void patchesAnOrderAsOneValue() {
            UpdateSavedQueryInput patch = SavedQuerySchemas.parseUpdateSavedQuery(
                payload("sortField", "newSurname", "sortDirection", "asc"));

            assertThat(patch.sort().value())
                .isEqualTo(new CaseSort("newSurname", CaseSort.Direction.ASCENDING));
        }

        @Test
        @DisplayName("refuses a direction patched without a field, rather than merging it with the stored one")
        void refusesADirectionPatchedWithoutAField() {
            // Merging would validate nothing: CaseSort is validated whole, and a direction alone has no
            // field to be valid against.
            assertThat(errorsFrom(() -> SavedQuerySchemas.parseUpdateSavedQuery(
                payload("sortDirection", "desc"))))
                .containsExactly(new FieldError("sortField",
                    "sortField is required when sortDirection is given"));
        }

        @Test
        @DisplayName("clears both order columns together when the field is cleared")
        void clearsBothOrderColumnsTogether() {
            UpdateSavedQueryInput patch = SavedQuerySchemas.parseUpdateSavedQuery(
                payload("sortField", null, "sortDirection", null));

            assertThat(patch.sort().isCleared()).isTrue();
        }

        @Test
        @DisplayName("patches the conditions to an empty list, which is how a query stops asking anything")
        void patchesTheConditionsToAnEmptyList() {
            UpdateSavedQueryInput patch =
                SavedQuerySchemas.parseUpdateSavedQuery(payload("conditions", List.of()));

            assertThat(patch.conditions().present()).isTrue();
            assertThat(patch.conditions().value()).isEmpty();
        }

        @Test
        @DisplayName("strips the fields a patch may not reassign, including the owner")
        void stripsTheFieldsAPatchMayNotReassign() {
            UpdateSavedQueryInput patch = SavedQuerySchemas.parseUpdateSavedQuery(payload(
                "ownerId", "somebody-else",
                "ownerName", "Somebody Else",
                "id", "11111111-1111-1111-1111-111111111111",
                "modifiedOn", "2026-01-01T00:00:00"));

            assertThat(patch.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("carries the same summary message a create refusal does")
        void carriesTheSameSummaryMessage() {
            assertThatThrownBy(() -> SavedQuerySchemas.parseUpdateSavedQuery(payload("name", null)))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Saved query is not valid");
        }
    }

    @Nested
    @DisplayName("the definition")
    class Definition {

        @Test
        @DisplayName("copies its conditions, so a caller cannot mutate a stored view")
        void copiesItsConditions() {
            List<String> mutable = new ArrayList<>(List.of("type:is:Adult"));
            SavedQueryDefinition definition = new SavedQueryDefinition(
                Optional.empty(), mutable, Optional.empty(), Optional.empty());

            mutable.add("type:is:Minor");

            assertThat(definition.conditions()).containsExactly("type:is:Adult");
        }
    }
}
