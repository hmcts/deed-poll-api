package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CaseListPropertiesTest {

    @Test
    @DisplayName("unset is decision 9's five seconds")
    void unsetIsFiveSeconds() {
        assertThat(new CaseListProperties(null).statementTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("a timeout Postgres would read as none refuses to start")
    void underOneMillisecondIsRefused() {
        assertThatThrownBy(() -> new CaseListProperties(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CaseListProperties(Duration.ofNanos(999_999)))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(new CaseListProperties(Duration.ofMillis(1)).statementTimeout()).isEqualTo(Duration.ofMillis(1));
    }
}
