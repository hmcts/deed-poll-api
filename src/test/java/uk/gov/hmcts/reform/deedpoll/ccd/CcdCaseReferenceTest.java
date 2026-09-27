package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The format of a stored CCD case reference, and its agreement with the database's own statement of it.
 */
class CcdCaseReferenceTest {

    private static final Path V21 = Path.of("src/main/resources/db/migration/V21__ccd_case_reference.sql");

    @ParameterizedTest
    @ValueSource(strings = {"1234567890123456", "0000000000000001", "1760000000000000"})
    @DisplayName("accepts sixteen digits, leading zeros included")
    void acceptsSixteenDigits(String reference) {
        assertThat(CcdCaseReference.isValid(reference)).isTrue();
        // Returned exactly as given: a reference held as a number would have lost the leading zeros.
        assertThat(CcdCaseReference.require(reference)).isEqualTo(reference);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "123456789012345",
        "12345678901234567",
        "1234-5678-9012-3456",
        "123456789012345a",
        " 1234567890123456",
        "1234567890123456\n",
        "1.234567890123456E15",
        "１２３４５６７８９０１２３４５６"
    })
    @DisplayName("refuses anything but exactly sixteen ASCII digits")
    void refusesAnythingElse(String reference) {
        assertThat(CcdCaseReference.isValid(reference)).isFalse();
        assertThatThrownBy(() -> CcdCaseReference.require(reference))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A CCD case reference is sixteen digits");
    }

    @Test
    @DisplayName("the refusal names the rule and never echoes the value")
    void theRefusalDoesNotEchoTheValue() {
        assertThatThrownBy(() -> CcdCaseReference.require("not-a-reference"))
            .hasMessageNotContaining("not-a-reference");
    }

    @Test
    @DisplayName("of reads the SDK's number as the stored string, and refuses what cannot be stored")
    void ofReadsTheSdksNumber() {
        assertThat(CcdCaseReference.of(1_234_567_890_123_456L)).contains("1234567890123456");
        assertThat(CcdCaseReference.of(null)).isEmpty();
        // Fifteen digits: a number cannot say whether it lost a leading zero, so none is invented.
        assertThat(CcdCaseReference.of(234_567_890_123_456L)).isEmpty();
        assertThat(CcdCaseReference.of(-1_234_567_890_123_456L)).isEmpty();
        assertThat(CcdCaseReference.of(12_345_678_901_234_567L)).isEmpty();
    }

    @Test
    @DisplayName("PATTERN is the regular expression V21's CHECK states")
    void thePatternIsTheDatabaseRule() throws IOException {
        // Two statements of one rule. Held together by reading the migration, because a reference the service
        // accepts and Postgres refuses fails the bind after CCD has already created the case.
        assertThat(Files.readString(V21))
            .contains("CHECK (\"ccdCaseReference\" ~ '" + CcdCaseReference.PATTERN.pattern() + "')")
            .contains("\"ccdCaseReference\" varchar(16)");
    }
}
