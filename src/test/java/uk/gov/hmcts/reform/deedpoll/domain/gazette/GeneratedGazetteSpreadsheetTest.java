package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedGazetteSpreadsheetTest {

    @Test
    void workbooksWithTheSameContentAreEqualAndHashAlike() {
        GeneratedGazetteSpreadsheet one = new GeneratedGazetteSpreadsheet("adult.xlsx", 2, new byte[]{1, 2, 3});
        GeneratedGazetteSpreadsheet two = new GeneratedGazetteSpreadsheet("adult.xlsx", 2, new byte[]{1, 2, 3});

        assertThat(one).isEqualTo(two).hasSameHashCodeAs(two);
    }

    @Test
    void anyDifferenceInFilenameCountOrContentMakesThemUnequal() {
        GeneratedGazetteSpreadsheet base = new GeneratedGazetteSpreadsheet("adult.xlsx", 2, new byte[]{1, 2, 3});

        assertThat(base)
            .isNotEqualTo(new GeneratedGazetteSpreadsheet("minor.xlsx", 2, new byte[]{1, 2, 3}))
            .isNotEqualTo(new GeneratedGazetteSpreadsheet("adult.xlsx", 3, new byte[]{1, 2, 3}))
            .isNotEqualTo(new GeneratedGazetteSpreadsheet("adult.xlsx", 2, new byte[]{1, 2, 4}));
    }

    @Test
    void theCallerCannotChangeTheContentThroughEitherArray() {
        byte[] supplied = {1, 2, 3};
        GeneratedGazetteSpreadsheet sheet = new GeneratedGazetteSpreadsheet("adult.xlsx", 1, supplied);

        supplied[0] = 9;
        sheet.content()[1] = 9;

        assertThat(sheet.content()).containsExactly(1, 2, 3);
    }
}
