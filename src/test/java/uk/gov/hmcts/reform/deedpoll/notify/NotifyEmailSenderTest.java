package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The production sender over a real HTTP round trip to {@link StubNotify}, with no database or container.
 *
 * <p>{@link StubNotifyTest} proves the double behaves like the client expects and
 * {@link NotificationOutboxTest} proves what each verdict does to a row. This class is the seam between them:
 * the sender uses only the values frozen on the row, carries the row id as Notify's reference, and translates
 * the client's status-bearing exception into the taxonomy the outbox understands.
 */
class NotifyEmailSenderTest {

    private static final UUID ROW_ID = UUID.fromString("e5dd8b51-b705-41ea-8246-1db549b4e6bd");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StubNotify notify = new StubNotify();

    private NotifyEmailSender sender;

    @BeforeEach
    void startStub() {
        notify.start();
        sender = new NotifyEmailSender(notify.client(), notify.properties());
    }

    @AfterEach
    void stopStub() {
        notify.stop();
    }

    @Test
    @DisplayName("returns Notify's id and template version, sending only the values frozen on the row")
    void sendsTheFrozenRow() throws Exception {
        Notification row = notification();

        SendOutcome outcome = sender.send(row);

        assertThat(outcome)
            .isEqualTo(new SendOutcome.Sent(
                StubNotify.NOTIFICATION_ID,
                StubNotify.TEMPLATE_VERSION,
                "Your application has been rejected",
                "Because the Master said so."));
        assertThat(notify.emailRequests()).singleElement().satisfies(request -> {
            JsonNode body;
            try {
                body = JSON.readTree(request.getBodyAsString());
            } catch (Exception exception) {
                throw new AssertionError("StubNotify received unreadable JSON", exception);
            }
            assertThat(body.path("template_id").asText()).isEqualTo(StubNotify.TEMPLATE_ID);
            assertThat(body.path("email_address").asText()).isEqualTo(row.getRecipient());
            assertThat(body.path("reference").asText())
                .as("the outbox row and Notify notification are traceable from either end")
                .isEqualTo(ROW_ID.toString());
            assertThat(body.path("personalisation").path("judges_reason").asText())
                .isEqualTo("No evidence of use");
        });
    }

    @Test
    @DisplayName("turns a 400 refusal into a permanent verdict")
    void classifiesAPermanentRefusal() {
        notify.stubSendStatus(400);

        assertThat(sender.send(notification()))
            .isEqualTo(SendOutcome.failed(
                NotificationFailure.PERMANENT,
                "GOV.UK Notify refused the send with status 400"));
        assertThat(notify.emailRequests()).hasSize(1);
    }

    @Test
    @DisplayName("turns a 500 response into a temporary verdict")
    void classifiesATemporaryFailure() {
        notify.stubSendStatus(500);

        assertThat(sender.send(notification()))
            .isEqualTo(SendOutcome.failed(
                NotificationFailure.TEMPORARY,
                "GOV.UK Notify refused the send with status 500"));
        assertThat(notify.emailRequests()).hasSize(1);
    }

    @Test
    @DisplayName("fails an obsolete template permanently without asking Notify")
    void refusesAnObsoleteTemplateWithoutHttp() {
        Notification row = notification();
        row.setTemplate("RENAMED_AFTER_ENQUEUE");

        assertThat(sender.send(row))
            .isEqualTo(SendOutcome.failed(
                NotificationFailure.PERMANENT,
                "no such email template: RENAMED_AFTER_ENQUEUE"));
        assertThat(notify.emailRequests()).isEmpty();
    }

    private static Notification notification() {
        Notification row = new Notification();
        row.setId(ROW_ID);
        row.setDeedPollId("case-1");
        row.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
        row.setRecipient("applicant@example.com");
        row.setPersonalisation(Map.of(
            "judges_reason", "No evidence of use",
            "additional_notes", "Please contact the court."));
        row.setAttempts(1);
        row.setCreatedBy("A Caseworker");
        return row;
    }
}
