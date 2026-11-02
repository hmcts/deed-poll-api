package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** What a Master email says (plan F1, step 8, decision A-1, and the settings' wording), without a database. */
class MasterEmailPersonalisationTest {

    private static final Duration SEVEN_DAYS = Duration.ofDays(7);
    private static final Duration FORTY_EIGHT_HOURS = Duration.ofHours(48);

    private final MasterEmailLinks links = new MasterEmailLinks(
        new AdminProperties("https://deed-poll-admin.aat.platform.hmcts.net/"),
        new NotifyProperties("https://notify.example.test", "", Map.of(), "", null));

    @Test
    @DisplayName("the Master's name, the count, one link to My referrals and the two settings, in that order, and "
        + "nothing else")
    void fiveKeys() {
        Map<String, String> personalisation =
            MasterEmailPersonalisation.of("Master Sullivan", 3, links, SEVEN_DAYS, FORTY_EIGHT_HOURS);

        assertThat(personalisation).containsExactly(
            entry("master_name", "Master Sullivan"),
            entry("referral_count", "3"),
            entry("my_referrals_link", "https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/mine"),
            entry("processing_window", "7 days"),
            entry("reminder_interval", "48 hours"));
    }

    @Test
    @DisplayName("one referral is written as 1")
    void one() {
        assertThat(MasterEmailPersonalisation.of("Master Okafor", 1, links, SEVEN_DAYS, FORTY_EIGHT_HOURS))
            .containsEntry("referral_count", "1");
    }

    @Test
    @DisplayName("the settings read as the Delivery Manager set them, singular at one")
    void settingsInWords() {
        Map<String, String> one =
            MasterEmailPersonalisation.of("Master Okafor", 1, links, Duration.ofDays(1), Duration.ofHours(1));
        assertThat(one).containsEntry("processing_window", "1 day").containsEntry("reminder_interval", "1 hour");

        Map<String, String> longest =
            MasterEmailPersonalisation.of("Master Okafor", 1, links, Duration.ofDays(30), Duration.ofHours(168));
        assertThat(longest).containsEntry("processing_window", "30 days")
            .containsEntry("reminder_interval", "168 hours");
    }

    @Test
    @DisplayName("a window that is not whole days, which only api's PUT can set, reads in hours, then in minutes")
    void partDays() {
        assertThat(MasterEmailPersonalisation.of("Master Okafor", 1, links, Duration.ofHours(36), FORTY_EIGHT_HOURS))
            .containsEntry("processing_window", "36 hours");
        assertThat(MasterEmailPersonalisation.of("Master Okafor", 1, links, Duration.ofMinutes(2000),
                                                 FORTY_EIGHT_HOURS))
            .containsEntry("processing_window", "2000 minutes");
    }
}
