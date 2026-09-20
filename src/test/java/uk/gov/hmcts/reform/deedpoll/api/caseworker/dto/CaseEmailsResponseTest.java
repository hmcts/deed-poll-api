package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.notify.CaseCorrespondence;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailOverview;
import uk.gov.hmcts.reform.deedpoll.notify.EmailAvailability;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlotDefaults;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseEmailsResponseTest {

    @Test
    @DisplayName("maps availability, history and slot defaults into one response")
    void mapsTheWholeEmailTab() {
        EmailTemplate template = EmailTemplate.APPLICATION_REJECTED;
        CaseCorrespondence correspondence = new CaseCorrespondence(
            UUID.fromString("33333333-3333-3333-3333-333333333333"),
            template.key(),
            template.displayName(),
            LocalDateTime.of(2026, 9, 20, 10, 0),
            "A Caseworker",
            "applicant@example.com",
            NotificationState.Sent,
            null,
            "Your application has been rejected",
            "Because the Master said so.",
            List.of());
        CaseEmailOverview overview = new CaseEmailOverview(
            List.of(EmailAvailability.applicable(template, true)),
            List.of(correspondence),
            List.of(new EmailSlotDefaults(template, Map.of(
                EmailSlot.JUDGES_REASON.placeholder(), "No evidence of settled intention",
                EmailSlot.ADDITIONAL_NOTES.placeholder(), ""))));

        CaseEmailsResponse response = CaseEmailsResponse.from(overview);

        assertThat(response.availability()).singleElement()
            .satisfies(entry -> assertThat(entry.template()).isEqualTo(template.key()));
        assertThat(response.history()).singleElement().satisfies(entry -> {
            assertThat(entry.id()).isEqualTo(correspondence.id().toString());
            assertThat(entry.subject()).isEqualTo(correspondence.subject());
            assertThat(entry.body()).isEqualTo(correspondence.body());
        });
        assertThat(response.slotDefaults()).singleElement().satisfies(defaults -> {
            assertThat(defaults.template()).isEqualTo(template.key());
            assertThat(defaults.fields()).containsAllEntriesOf(overview.slotDefaults().getFirst().fields());
        });
    }
}
