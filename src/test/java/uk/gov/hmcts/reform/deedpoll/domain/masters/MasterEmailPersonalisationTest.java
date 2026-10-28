package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.notify.NotifyProperties;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What a Master email says (plan D3, step 5), without a database. */
class MasterEmailPersonalisationTest {

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private final MasterEmailLinks links = new MasterEmailLinks(
        new AdminProperties("https://deed-poll-admin.aat.platform.hmcts.net"),
        new NotifyProperties("https://notify.example.test", "", Map.of(), "", null));

    @Test
    @DisplayName("a digest names its window in dd-mm-yy words and lists one line per referral, in order")
    void digest() {
        Map<String, String> personalisation = MasterEmailPersonalisation.of(
            "Master Sullivan", MasterEmailKind.DIGEST, "2026-10-28:PM",
            List.of(new MasterEmailPersonalisation.Line(FIRST, "DP2026-000123", List.of("Minor", "MinorTransgender",
                                                                                        "Other"),
                                                        Urgency.Urgent, LocalDateTime.of(2026, 10, 27, 9, 15)),
                    new MasterEmailPersonalisation.Line(SECOND, "DP2026-000124", List.of(), Urgency.Normal,
                                                        LocalDateTime.of(2026, 1, 2, 16, 0))),
            links);

        assertThat(personalisation).containsOnlyKeys("master_name", "referral_count", "period", "referral_list")
            .containsEntry("master_name", "Master Sullivan")
            .containsEntry("referral_count", "2")
            .containsEntry("period", "the afternoon of 28-10-26")
            .containsEntry("referral_list",
                "DP2026-000123 – Applicant is a minor: Transgender; Other – Urgent – referred 27-10-26 – "
                    + "https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/" + FIRST + "\n"
                    + "DP2026-000124 – no reasons recorded – Standard – referred 02-01-26 – "
                    + "https://deed-poll-admin.aat.platform.hmcts.net/judicial/referrals/" + SECOND);
    }

    @Test
    @DisplayName("the day referred is London's: 23:30 UTC in British Summer Time is the next day")
    void referredInLondon() {
        String line = MasterEmailPersonalisation.line(new MasterEmailPersonalisation.Line(
            FIRST, "DP2026-000125", List.of("Other"), Urgency.Normal, LocalDateTime.of(2026, 6, 30, 23, 30)), links);

        assertThat(line).contains(" – referred 01-07-26 – ");
    }

    @Test
    @DisplayName("a morning digest says so, and a reminder says what it is waiting for")
    void periods() {
        assertThat(MasterEmailPersonalisation.period(MasterEmailKind.DIGEST, "2026-10-28:AM"))
            .isEqualTo("the morning of 28-10-26");
        assertThat(MasterEmailPersonalisation.period(MasterEmailKind.REMINDER, "2026-10-28T10:15|run"))
            .isEqualTo("waiting for your direction");
        assertThatThrownBy(() -> MasterEmailPersonalisation.period(MasterEmailKind.DIGEST, "2026-10-28"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a referral with no display reference is still listed, with its link")
    void noReference() {
        assertThat(MasterEmailPersonalisation.line(new MasterEmailPersonalisation.Line(
            FIRST, null, List.of("Other"), Urgency.Normal, LocalDateTime.of(2026, 10, 27, 9, 0)), links))
            .startsWith("(no reference) – Other – Standard – referred 27-10-26 – https://");
    }
}
