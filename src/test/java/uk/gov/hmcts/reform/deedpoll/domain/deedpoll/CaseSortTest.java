package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and validating the {@code sort} parameter.
 *
 * <p>No reference test to port -- the Dynamics original sorted each system view for you. What is asserted
 * here is that the default is today's order, that a field name reaching an ORDER BY is one this service
 * published, and that the two orders nobody can honestly give -- a multi-valued column and a derived one --
 * are refused rather than quietly ignored.
 */
class CaseSortTest {

    @Test
    @DisplayName("reads no parameter as today's order, newest first")
    void readsNoParameterAsNewestFirst() {
        // The existing behaviour, so a caller who has never heard of this parameter sees no change.
        assertThat(CaseSort.parse(null)).isEqualTo(CaseSort.NEWEST_FIRST);
        assertThat(CaseSort.parse("")).isEqualTo(CaseSort.NEWEST_FIRST);
        assertThat(CaseSort.parse("   ")).isEqualTo(CaseSort.NEWEST_FIRST);
        assertThat(CaseSort.NEWEST_FIRST.field()).isEqualTo("createdOn");
        assertThat(CaseSort.NEWEST_FIRST.direction()).isEqualTo(CaseSort.Direction.DESCENDING);
        assertThat(CaseSort.NEWEST_FIRST.direction().isDescending()).isTrue();
    }

    @Test
    @DisplayName("reads no parameter as the fallback the caller supplies, and a named one in preference to it")
    void readsNoParameterAsTheSuppliedFallback() {
        // What lets a filter tab carry its own order: the "awaiting confirmation" list is useless newest-first, so
        // the controller passes that tab's default. A caller who names `sort` still wins -- a tab that forced its
        // order would make its own column headers unclickable.
        assertThat(CaseSort.parse(null, ListFilters.LONGEST_WAIT_FIRST))
            .isEqualTo(ListFilters.LONGEST_WAIT_FIRST);
        assertThat(CaseSort.parse("  ", ListFilters.LONGEST_WAIT_FIRST))
            .isEqualTo(ListFilters.LONGEST_WAIT_FIRST);
        assertThat(CaseSort.parse("newSurname:desc", ListFilters.LONGEST_WAIT_FIRST))
            .isEqualTo(new CaseSort("newSurname", CaseSort.Direction.DESCENDING));
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {

        @Test
        @DisplayName("reads a field and a direction")
        void readsAFieldAndADirection() {
            assertThat(CaseSort.parse("newSurname:asc"))
                .isEqualTo(new CaseSort("newSurname", CaseSort.Direction.ASCENDING));
            assertThat(CaseSort.parse("dateReceived:desc"))
                .isEqualTo(new CaseSort("dateReceived", CaseSort.Direction.DESCENDING));
        }

        @Test
        @DisplayName("reads a bare field name as ascending")
        void readsABareFieldNameAsAscending() {
            // Ascending is what an undirected sort conventionally means, and the request is unambiguous.
            assertThat(CaseSort.parse("newSurname"))
                .isEqualTo(new CaseSort("newSurname", CaseSort.Direction.ASCENDING));
        }

        @Test
        @DisplayName("trims the whitespace a hand-written URL carries")
        void trimsWhitespace() {
            assertThat(CaseSort.parse(" newSurname : desc "))
                .isEqualTo(new CaseSort("newSurname", CaseSort.Direction.DESCENDING));
        }

        @Test
        @DisplayName("sorts by an audit column, which is not a form field")
        void sortsByAnAuditColumn() {
            // createdOn is the default, so it has to be nameable: otherwise "oldest first" is the one order
            // that cannot be asked for.
            assertThat(CaseSort.parse("createdOn:asc"))
                .isEqualTo(new CaseSort("createdOn", CaseSort.Direction.ASCENDING));
            assertThat(CaseSort.parse("modifiedOn:desc").field()).isEqualTo("modifiedOn");
            assertThat(CaseSort.parse("modifiedBy:asc").field()).isEqualTo("modifiedBy");
        }

        @Test
        @DisplayName("sorts by a read-only field, because sortable is not editable")
        void sortsByAReadOnlyField() {
            // The same distinction the query surface makes: reference and state are among the most useful
            // columns to order a caseworker's list by, and neither is editable.
            assertThat(CaseSort.parse("referencenumber:asc").field()).isEqualTo("referencenumber");
            assertThat(CaseSort.parse("state:desc").field()).isEqualTo("state");
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @Test
        @DisplayName("refuses a field this service does not publish")
        void refusesAnUnknownField() {
            assertThatThrownBy(() -> CaseSort.parse("nonsense:asc"))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Case list sort is not valid")
                .satisfies(thrown -> assertThat(errorOf(thrown))
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("sort");
                        assertThat(error.message()).contains("names no sortable case field", "'nonsense'");
                    }));
        }

        @Test
        @DisplayName("refuses a field name crafted to look like SQL, rather than escaping it")
        void refusesAFieldNameCraftedToLookLikeSql() {
            // The security-relevant assertion: a sort field reaches an ORDER BY, so the name has to be one
            // this service published and not one a caller invented.
            assertThatThrownBy(() -> CaseSort.parse("createdOn desc; drop table deedpoll--:asc"))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> CaseSort.parse("(select 1):asc"))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("quotes a very long name back truncated, so the response stays bounded")
        void quotesALongNameTruncated() {
            String name = "x".repeat(500);

            assertThatThrownBy(() -> CaseSort.parse(name + ":asc"))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(errorOf(thrown).message()).hasSizeLessThan(120));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"iShall", "iHaveSubscribed", "andAlsoMyOld"})
        @DisplayName("refuses a multiselect field, saying it holds several values")
        void refusesAMultiselectField(String field) {
            // Queryable but not sortable: ordering a set of option values means choosing whether {1,3}
            // precedes {2}, and every answer to that is arbitrary. Refused rather than ignored, because a
            // list in an order nobody asked for, with nothing said, is what this surface exists to prevent.
            assertThat(FormFields.FIELD_BY_NAME.get(field).kind()).isEqualTo(FieldKind.MULTISELECT);

            assertThatThrownBy(() -> CaseSort.parse(field + ":asc"))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(errorOf(thrown).message())
                    .contains(field, "holds several values"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"daysReceivedToEnroled", "daysReceivedToReturned"})
        @DisplayName("refuses a derived field, which has no column to order by")
        void refusesADerivedField(String field) {
            assertThat(FormFields.FIELD_BY_NAME).containsKey(field);

            assertThatThrownBy(() -> CaseSort.parse(field + ":asc"))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(errorOf(thrown).message())
                    .contains(field, "derived, not stored"));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"newSurname:ASC", "newSurname:descending", "newSurname:down", "newSurname:"})
        @DisplayName("refuses a direction that is not one of the two tokens")
        void refusesAnUnknownDirection(String sort) {
            // Matched exactly, as an operator token is: accepting ASC as well as asc would make the wire
            // format two formats. An empty direction is a control nobody filled in, not a default.
            assertThatThrownBy(() -> CaseSort.parse(sort))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(errorOf(thrown).message()).contains("asc, desc"));
        }

        @Test
        @DisplayName("refuses a field the record is constructed with directly")
        void refusesAFieldConstructedDirectly() {
            // The guard behind parse(): a later caller in this codebase cannot make an unsortable order by
            // going round the parser.
            assertThatThrownBy(() -> new CaseSort("iShall", CaseSort.Direction.ASCENDING))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("iShall");
        }
    }

    @Nested
    @DisplayName("the sortable set")
    class Sortable {

        @Test
        @DisplayName("is every single-valued stored form field, plus the process and audit columns")
        void isEverySingleValuedStoredFieldPlusAudit() {
            assertThat(CaseSort.sortableFields())
                .contains("newSurname", "dateReceived", "type", "state", "referencenumber", "refundAmount",
                    "inPerson", "createdOn", "modifiedOn", "createdBy", "modifiedBy",
                    // Sortable without being on a form: it is the order the "awaiting confirmation" tab is asked
                    // for, so a name the parser refused would be an order this service could not express itself.
                    "awaitingConfirmationSince")
                .doesNotContain("iShall", "iHaveSubscribed", "andAlsoMyOld",
                    "daysReceivedToEnroled", "daysReceivedToReturned")
                // Not offered by any list header, and displayReference sorts as text rather than as the
                // reference numbers it renders.
                .doesNotContain("id", "notes", "witnesses", "documents");
        }

        @Test
        @DisplayName("holds only stored columns, so every member has something to order by")
        void holdsOnlyStoredColumns() {
            assertThat(CaseSort.sortableFields()).allSatisfy(field ->
                assertThat(DeedPollFields.readableFields()).contains(field));
        }

        @Test
        @DisplayName("parses every member of itself")
        void parsesEveryMemberOfItself() {
            // The set and the parser cannot disagree: a name published as sortable that the parser refuses
            // would be a sort control offering an option that 400s.
            assertThat(CaseSort.sortableFields()).allSatisfy(field ->
                assertThat(CaseSort.parse(field + ":desc").field()).isEqualTo(field));
        }

        @Test
        @DisplayName("cannot be modified by a caller")
        void cannotBeModifiedByACaller() {
            assertThatThrownBy(() -> CaseSort.sortableFields().add("nonsense"))
                .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("ends with the audit columns in a fixed order, because the order is published")
        void endsWithTheAuditColumnsInAFixedOrder() {
            // This set feeds the `sort` parameter's description in the OpenAPI document, and deed-poll-admin
            // vendors that document. The audit four were a Set.of, whose iteration order is randomised per JVM
            // by a startup salt, so every regeneration of the spec produced a spurious diff in these names with
            // nothing having changed -- which is how a real change hides. Iteration order is part of this
            // method's contract, not an implementation detail.
            assertThat(CaseSort.sortableFields())
                .endsWith("createdOn", "modifiedOn", "createdBy", "modifiedBy");
        }

        @Test
        @DisplayName("iterates identically every time it is read")
        void iteratesIdenticallyEveryTime() {
            // Within one JVM a Set.of is stably ordered, so this alone would not have caught the bug above --
            // it guards the weaker property that nothing recomputes the set per call.
            assertThat(List.copyOf(CaseSort.sortableFields()))
                .isEqualTo(List.copyOf(CaseSort.sortableFields()));
        }
    }

    /** The single field error a refusal carried. */
    private static FieldError errorOf(Throwable thrown) {
        return ((InvalidInputError) thrown).errors().get(0);
    }
}
