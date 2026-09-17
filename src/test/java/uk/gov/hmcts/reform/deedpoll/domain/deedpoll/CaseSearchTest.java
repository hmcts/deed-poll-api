package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The search term, read from the {@code q} parameter.
 *
 * <p><strong>No container and no context.</strong> Every claim here is about the term itself -- what a blank
 * one means, how a reference normalises, when a term reads as a serial -- and none of it needs a database.
 * That matters beyond speed: container-backed tests are excluded on the CI agent, so the rules stated here
 * are the ones CI actually checks. {@code CaseSpecificationsTest} pins what the database does with them.
 */
class CaseSearchTest {

    @Nested
    @DisplayName("parse")
    class Parse {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("a missing or blank term searches for nothing")
        void blankTermSearchesForNothing(String q) {
            // Not an empty search: `like '%%'` against six columns would answer the same list at six times
            // the cost, so a box submitted empty is the unnarrowed list.
            assertThat(CaseSearch.parse(q).isEmpty()).isTrue();
            assertThat(CaseSearch.parse(q)).isEqualTo(CaseSearch.NONE);
        }

        @Test
        @DisplayName("a term is trimmed, and the whitespace inside it is kept")
        void trimsTheTermAndKeepsWhatIsInsideIt() {
            // Surrounding whitespace is how a copied value arrives; whitespace in the middle is content --
            // "Mary Anne" is a forename somebody has.
            assertThat(CaseSearch.parse("  Mary Anne  ").term()).isEqualTo("Mary Anne");
        }

        @Test
        @DisplayName("an over-long term is truncated rather than refused")
        void truncatesAnOverLongTerm() {
            String tooLong = "a".repeat(CaseSearch.MAX_TERM_LENGTH + 50);

            CaseSearch search = CaseSearch.parse(tooLong);

            assertThat(search.term()).hasSize(CaseSearch.MAX_TERM_LENGTH);
            assertThat(search.isEmpty()).isFalse();
        }

        @Test
        @DisplayName("NONE is empty, and a term is not")
        void noneIsEmpty() {
            assertThat(CaseSearch.NONE.isEmpty()).isTrue();
            assertThat(CaseSearch.NONE.term()).isEmpty();
            assertThat(new CaseSearch("Smith").isEmpty()).isFalse();
        }

        @Test
        @DisplayName("a null term is the empty one rather than a null field")
        void nullTermIsTheEmptyOne() {
            // The record is constructible directly, so the normalisation is in the constructor and not only
            // in parse -- otherwise every reader would have to null-check term().
            assertThat(new CaseSearch(null).isEmpty()).isTrue();
            assertThat(new CaseSearch(null).normalisedReference()).isEmpty();
        }
    }

    @Nested
    @DisplayName("normalisedReference")
    class NormalisedReference {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "KBDP2026-10100",
            "kbdp2026-10100",
            "KBDP202610100",
            "  KBDP 2026-10100 ",
            "kbdp 2026 - 10100"
        })
        @DisplayName("every way of writing one reference normalises to the same string")
        void everyWayOfWritingOneReferenceNormalisesTheSame(String typed) {
            // The claim behind the tolerance: a caseworker reading a reference off paper, out of an email or
            // out of the service's own list is typing the same identifier, and the differences between those
            // three are case, hyphens and spaces.
            assertThat(CaseSearch.parse(typed).normalisedReference()).isEqualTo("kbdp202610100");
        }

        @Test
        @DisplayName("nothing else is stripped, so a name normalises to itself in lower case")
        void stripsNothingElse() {
            // Only the punctuation the reference format uses. Stripping more would make two different names
            // normalise to the same string, and the reference comparison is an equality.
            assertThat(CaseSearch.parse("O'Brien").normalisedReference()).isEqualTo("o'brien");
        }
    }

    @Nested
    @DisplayName("serial")
    class Serial {

        @ParameterizedTest
        @CsvSource({
            "10100, 10100",
            "'  10100  ', 10100",
            "'10-100', 10100",
            "007, 7"
        })
        @DisplayName("a term that reads as a whole number names that serial")
        void wholeNumberNamesASerial(String typed, int expected) {
            assertThat(CaseSearch.parse(typed).serial()).contains(expected);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
            "kbdp2026-10100",
            "Smith",
            "10100a",
            "10100.5",
            "99999999999999999999"
        })
        @DisplayName("anything else names no serial, rather than being refused")
        void anythingElseNamesNoSerial(String typed) {
            // referencenumber is an integer column: a term that is not a whole one contributes no predicate
            // against it at all. Refusing here instead would make every name search a 400, and casting the
            // column to text would be a query Postgres refuses for some of these.
            assertThat(CaseSearch.parse(typed).serial()).isEmpty();
        }

        @Test
        @DisplayName("an empty term names no serial")
        void anEmptyTermNamesNoSerial() {
            assertThat(CaseSearch.NONE.serial()).isEmpty();
        }
    }

    @Nested
    @DisplayName("the searched columns")
    class SearchedColumns {

        @Test
        @DisplayName("are the six name fields, and only those")
        void areTheSixNameFields() {
            // Stated as an assertion because decision 9 is a list: addresses and narrative are excluded, and
            // a column added here reaches every search in the service.
            assertThat(CaseSearch.NAME_FIELDS).containsExactly(
                "newForename", "newSurname", "oldForename", "oldSurname", "parents", "representativeName");
        }

        @Test
        @DisplayName("are every one of them a stored, readable case field")
        void areEveryOneOfThemAStoredField() {
            // A name that is not a column reaches root.get() and throws out of a query builder as a 500, so
            // a rename in the schema has to fail here instead. The registry is the authority, not this list.
            assertThat(DeedPollFields.readableFields()).containsAll(CaseSearch.NAME_FIELDS);
        }

        @Test
        @DisplayName("do not include an address or a narrative field")
        void doNotIncludeAnAddressOrNarrative() {
            assertThat(CaseSearch.NAME_FIELDS)
                .doesNotContain("applicantAddress", "referralNotes", "representativeAddress");
        }
    }
}
