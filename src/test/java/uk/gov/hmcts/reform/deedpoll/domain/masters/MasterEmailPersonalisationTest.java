package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/** What a Master email says (plan F1, step 8, decision A-1), without a database. */
class MasterEmailPersonalisationTest {

    private final MasterEmailLinks links = new MasterEmailLinks(
        new AdminProperties("https://deed-poll-admin.aat.platform.hmcts.net/"),
        new NotifyProperties("https://notify.example.test", "", Map.of(), "", null));

    @Test
    @DisplayName("the Master's name, the count and one link to My referrals, in that order, and nothing else")
    void threeKeys() {
        Map<String, String> personalisation = MasterEmailPersonalisation.of("Master Sullivan", 3, links);

        assertThat(personalisation).containsExactly(
            entry("master_name", "Master Sullivan"),
            entry("referral_count", "3"),
            entry("my_referrals_link", "https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/mine"));
    }

    @Test
    @DisplayName("one referral is written as 1")
    void one() {
        assertThat(MasterEmailPersonalisation.of("Master Okafor", 1, links))
            .containsEntry("referral_count", "1");
    }
}
