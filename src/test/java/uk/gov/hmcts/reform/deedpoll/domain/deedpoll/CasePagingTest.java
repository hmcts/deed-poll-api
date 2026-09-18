package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parsing and validating the {@code page} and {@code size} parameters.
 *
 * <p>Two claims matter here and the rest is arithmetic: that the defaults are the list a caller already
 * had, and that <strong>no client input reaches a page larger than the cap</strong> -- which is the
 * difference between a list endpoint and a way to make the service read its own database in full.
 */
class CasePagingTest {

    @Test
    @DisplayName("reads no parameters as the first page at the default size")
    void readsNoParametersAsTheFirstPage() {
        assertThat(CasePaging.parse(null, null)).isEqualTo(CasePaging.FIRST);
        assertThat(CasePaging.parse("", "  ")).isEqualTo(CasePaging.FIRST);
        assertThat(CasePaging.FIRST.page()).isEqualTo(1);
        assertThat(CasePaging.FIRST.size()).isEqualTo(25);
    }

    @Test
    @DisplayName("counts pages from one, and offsets from zero")
    void countsPagesFromOneAndOffsetsFromZero() {
        // 1-based because the number appears in a URL a human reads and shares; index() is the one place
        // that becomes an offset.
        assertThat(CasePaging.parse("1", "10").index()).isZero();
        assertThat(CasePaging.parse("4", "10").index()).isEqualTo(3);
    }

    @Test
    @DisplayName("reads a page and a size a caller asked for")
    void readsAPageAndASize() {
        CasePaging paging = CasePaging.parse(" 3 ", " 40 ");

        assertThat(paging.page()).isEqualTo(3);
        assertThat(paging.size()).isEqualTo(40);
    }

    @Nested
    @DisplayName("a caller's own default size")
    class CallerDefault {

        /** What the judicial pool asks for: ten papers rather than twenty-five table rows. */
        private static final int POOL_SIZE = 10;

        @Test
        @DisplayName("applies to a request that named no size, and not to one that did")
        void appliesOnlyWhenTheRequestNamedNoSize() {
            assertThat(CasePaging.parse(null, null, POOL_SIZE).size()).isEqualTo(POOL_SIZE);
            assertThat(CasePaging.parse("2", "  ", POOL_SIZE).size()).isEqualTo(POOL_SIZE);
            // A caller's default is what a silent request gets, never a limit on what it may ask for: a judge
            // who wants forty on one page gets forty.
            assertThat(CasePaging.parse("2", "40", POOL_SIZE).size()).isEqualTo(40);
            assertThat(CasePaging.parse("2", "40", POOL_SIZE).page()).isEqualTo(2);
        }

        @Test
        @DisplayName("leaves the cap and every refusal exactly where they were")
        void leavesTheCapAndTheRefusalsAlone() {
            // The whole point of the default being a parameter and the cap not being one: a second surface
            // brings its own screenful and inherits the service's protections rather than re-deriving them.
            assertThat(CasePaging.parse("1", "1000", POOL_SIZE).size()).isEqualTo(CasePaging.MAX_SIZE);
            assertThatThrownBy(() -> CasePaging.parse("0", null, POOL_SIZE))
                .isInstanceOf(InvalidInputError.class);
            assertThatThrownBy(() -> CasePaging.parse(null, "lots", POOL_SIZE))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("refuses a default that is not itself a size a request could have asked for")
        void refusesADefaultOutsideTheCap()  {
            // A guard against this codebase rather than against a caller: a default above the cap would be a
            // page size no request could have asked for, reached by a surface rather than by a client, and
            // clamping it silently would hide the mistake in the one place the cap is meant to be absolute.
            assertThatThrownBy(() -> CasePaging.parse(null, null, CasePaging.MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(CasePaging.MAX_SIZE));
            assertThatThrownBy(() -> CasePaging.parse(null, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the two-argument parse is the case list's default, spelled once")
        void theTwoArgumentParseIsTheCaseListsDefault() {
            assertThat(CasePaging.parse("3", null))
                .isEqualTo(CasePaging.parse("3", null, CasePaging.DEFAULT_SIZE));
        }
    }

    @Nested
    @DisplayName("the size cap")
    class SizeCap {

        @ParameterizedTest(name = "size={0}")
        @ValueSource(strings = {"101", "1000", "2147483647"})
        @DisplayName("clamps an over-cap size rather than honouring it")
        void clampsAnOverCapSize(String size) {
            // Not negotiable by the client: an uncapped size is how a list endpoint becomes a denial of
            // service against its own database. Clamped rather than refused, because "as many as I can
            // have" is a request with an answer.
            assertThat(CasePaging.parse("1", size).size()).isEqualTo(CasePaging.MAX_SIZE);
        }

        @Test
        @DisplayName("cannot be exceeded by constructing the record directly either")
        void cannotBeExceededByConstructingDirectly() {
            assertThatThrownBy(() -> new CasePaging(1, CasePaging.MAX_SIZE + 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(String.valueOf(CasePaging.MAX_SIZE));
            assertThatThrownBy(() -> new CasePaging(1, 0))
                .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new CasePaging(0, 25))
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("is at most the cap for every size a caller can send")
        void isAtMostTheCapForEverySizeACallerCanSend() {
            // The claim the checkbox makes, asserted over the range rather than at the boundary.
            for (int size = 1; size <= 400; size++) {
                assertThat(CasePaging.parse("1", String.valueOf(size)).size())
                    .isBetween(1, CasePaging.MAX_SIZE);
            }
        }
    }

    @Nested
    @DisplayName("refusing")
    class Refusing {

        @ParameterizedTest(name = "page={0}")
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("refuses a page below the first, rather than clamping it")
        void refusesAPageBelowTheFirst(String page) {
            // Not clamped to 1: a caller who thinks pages start at zero is off by one on every page after
            // this one, and answering page 1 silently hides that from them.
            assertThatThrownBy(() -> CasePaging.parse(page, null))
                .isInstanceOf(InvalidInputError.class)
                .hasMessage("Case list paging is not valid")
                .satisfies(thrown -> assertThat(onlyError(thrown))
                    .satisfies(error -> {
                        assertThat(error.field()).isEqualTo("page");
                        assertThat(error.message()).contains("1-based", page);
                    }));
        }

        @Test
        @DisplayName("refuses a size of nothing")
        void refusesASizeOfNothing() {
            assertThatThrownBy(() -> CasePaging.parse(null, "0"))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(onlyError(thrown).field()).isEqualTo("size"));
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"lots", "2.5", "1e3", "99999999999999", "1; drop table deedpoll"})
        @DisplayName("refuses a page that is not a whole number")
        void refusesAPageThatIsNotAWholeNumber(String page) {
            assertThatThrownBy(() -> CasePaging.parse(page, null))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(onlyError(thrown).message())
                    .contains("takes a whole number"));
        }

        @Test
        @DisplayName("reports both parameters when both are unusable")
        void reportsBothParametersWhenBothAreUnusable() {
            // One question asked with two parameters, so a caller who got both wrong is told about both
            // rather than made to fix them one round trip at a time.
            assertThatThrownBy(() -> CasePaging.parse("lots", "some"))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(((InvalidInputError) thrown).errors())
                    .extracting(FieldError::field)
                    .containsExactly("page", "size"));
        }

        @Test
        @DisplayName("quotes a very long token back truncated, so the response stays bounded")
        void quotesALongTokenTruncated() {
            assertThatThrownBy(() -> CasePaging.parse("9".repeat(500), null))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(thrown -> assertThat(onlyError(thrown).message()).hasSizeLessThan(120));
        }
    }

    /** The one field error a refusal carried, asserted as the only one. */
    private static FieldError onlyError(Throwable thrown) {
        assertThat(((InvalidInputError) thrown).errors()).hasSize(1);
        return ((InvalidInputError) thrown).errors().get(0);
    }
}
