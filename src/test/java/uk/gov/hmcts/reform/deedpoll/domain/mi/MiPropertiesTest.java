package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MiPropertiesTest {

    @Test
    @DisplayName("unset is ten seconds")
    void unsetIsTenSeconds() {
        assertThat(new MiProperties(null).statementTimeout()).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a timeout Postgres would read as none refuses to start")
    void underOneMillisecondIsRefused() {
        assertThatThrownBy(() -> new MiProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MiProperties(Duration.ofNanos(999_999)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new MiProperties(Duration.ofMillis(1)).statementTimeout()).isEqualTo(Duration.ofMillis(1));
    }
}
