package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GazetteConfigurationTest {
    private static final String TEMPLATE = "20000000-0000-0000-0000-000000000001";
    private static final String REPLY = "10000000-0000-0000-0000-000000000001";

    @Test
    void requiresEnabledKeyReplyRetentionAndTheCorrectGroupTemplate() {
        NotifyProperties complete = properties("synthetic", REPLY, 4, Map.of("gazette-adult", TEMPLATE));
        assertThat(new GazetteConfiguration(complete, true).forGroups(1, 0).templateId().toString())
            .isEqualTo(TEMPLATE);
        for (GazetteConfiguration config : java.util.List.of(new GazetteConfiguration(complete, false),
            new GazetteConfiguration(properties("", REPLY, 4, Map.of("gazette-adult", TEMPLATE)), true),
            new GazetteConfiguration(properties("synthetic", "", 4, Map.of("gazette-adult", TEMPLATE)), true),
            new GazetteConfiguration(properties("synthetic", REPLY, 0, Map.of("gazette-adult", TEMPLATE)), true),
            new GazetteConfiguration(properties("synthetic", REPLY, 79, Map.of("gazette-adult", TEMPLATE)), true),
            new GazetteConfiguration(properties("synthetic", REPLY, 4, Map.of("gazette-adult", "1-1-1-1-1")), true))) {
            assertThatThrownBy(() -> config.forGroups(1, 0)).isInstanceOfSatisfying(GazetteRefusal.class,
                error -> assertThat(error.code()).isEqualTo(GazetteRefusal.Code.GAZETTE_UNAVAILABLE));
        }
        assertThatThrownBy(() -> new GazetteConfiguration(complete, true).forGroups(1, 1))
            .isInstanceOf(GazetteRefusal.class);
        assertThatThrownBy(() -> new GazetteConfiguration(complete, true).forGroups(0, 1))
            .isInstanceOf(GazetteRefusal.class);
    }

    private static NotifyProperties properties(String key, String reply, Integer weeks, Map<String, String> templates) {
        return new NotifyProperties("https://notify.invalid", key, templates, reply, weeks);
    }
}
