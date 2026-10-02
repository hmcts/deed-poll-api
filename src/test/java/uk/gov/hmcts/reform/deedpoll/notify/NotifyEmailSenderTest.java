package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    /**
     * Real rather than mocked, because it is a no-op for every template this class sends: none of the three carries
     * a document, so {@code resolve} answers with the row's own map and never reaches the document service at all.
     * A mock would have made that a stubbing decision instead of the property it is -- see
     * {@code DocumentUploadsTest} for the templates that do carry one.
     */
    private DocumentUploads uploads;

    private NotifyEmailSender sender;

    @BeforeEach
    void startStub() {
        notify.start();
        uploads = new DocumentUploads(mock(CorrespondenceDocumentReader.class), notify.properties());
        sender = new NotifyEmailSender(notify.client(), notify.properties(), uploads);
    }

    @AfterEach
    void stopStub() {
        notify.stop();
    }

    static java.util.stream.Stream<EmailTemplate> documentFreeTemplates() {
        return java.util.Arrays.stream(EmailTemplate.values()).filter(t -> t.documentKeys().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("documentFreeTemplates")
    void everyDocumentFreeTemplatePreparesWithoutSendingAndDeliversFrozenContent(EmailTemplate template) {
        Notification row = notification();
        row.setTemplate(template.name());
        PreparedNotification prepared = sender.prepare(row);
        assertThat(prepared).isInstanceOf(PreparedNotification.Ready.class);
        assertThat(notify.emailRequests()).isEmpty();
        assertThat(((PreparedNotification.Ready) prepared).delivery().send()).isInstanceOf(SendOutcome.Sent.class);
        assertThat(notify.emailRequests()).hasSize(1);
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
    @DisplayName("sends a template with attached files the frozen row alone, with no stand-in for either file")
    void sendsNoStandInForAnAttachment() throws Exception {
        // A template whose preview demands a value for its file, which a send does not: the row goes out as frozen.
        notify.stubTemplateRequiring("judges_reason", "LOC021 statutory declaration.pdf");

        assertThat(sender.send(notification())).isInstanceOf(SendOutcome.Sent.class);
        assertThat(notify.emailRequests()).singleElement().satisfies(request -> {
            JsonNode personalisation = JSON.readTree(request.getBodyAsString()).path("personalisation");
            assertThat(personalisation.has("LOC021 statutory declaration.pdf")).isFalse();
            assertThat(request.getBodyAsString()).doesNotContain(AttachmentStandIns.STAND_IN);
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
    @DisplayName("sends the reply-to address with the email that invites a reply, and only that one")
    void sendsTheReplyToForTheEmailThatInvitesOne() {
        sender.send(confirmationRow());
        sender.send(notification());

        assertThat(notify.emailRequests()).hasSize(2);
        assertThat(replyToOf(0))
            .as("the confirmation email asks the applicant to reply, so Notify is told where to")
            .isEqualTo(StubNotify.REPLY_TO_ID);
        // The other half of decision 10, and the reason the two client overloads are kept apart: configuring a
        // reply-to for ④ must not change what ③ sends. The four-argument call carries no email_reply_to_id at all.
        assertThat(replyToOf(1))
            .as("the rejection email invites no reply, so it is sent exactly as it was before there was one")
            .isEmpty();
    }

    @Test
    @DisplayName("refuses to send an email that invites a reply when no reply-to is configured")
    void refusesAnInvitedReplyWithNowhereToReplyTo() {
        // Notify's client drops a blank reply-to and sends from its no-reply sender, so this would otherwise be a
        // send that looks successful and loses the answer the email exists to collect -- an applicant who appears
        // never to have replied. Permanent because no retry configures an inbox, and EmailAvailability.provisioned
        // is how a caseworker is told before they compose one.
        NotifyEmailSender noReplyTo = new NotifyEmailSender(
            notify.client(),
            new NotifyProperties(notify.baseUrl(), notify.apiKey(),
                                 notify.properties().templates(), "", StubNotify.RETENTION_WEEKS),
            uploads);

        assertThat(noReplyTo.send(confirmationRow()))
            .isEqualTo(SendOutcome.failed(
                NotificationFailure.PERMANENT,
                "no reply-to address is configured, and the request-confirmation-to-proceed email asks for a "
                    + "reply"));
        assertThat(notify.emailRequests()).as("and nothing was sent from no-reply").isEmpty();

        // The email that invites no reply is unaffected: it needs no inbox and is sent as it always was.
        assertThat(noReplyTo.send(notification())).isInstanceOf(SendOutcome.Sent.class);
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

    @Test
    @DisplayName("a document that cannot be resolved stops the send rather than sending the email without it")
    void refusesASendWhoseDocumentCannotBeResolved() {
        // The one branch of the sender the three document-free templates cannot reach. Without it a change that
        // moved the resolve after sendEmail, or dropped the refusal check, would put the raw supporting-document
        // id in front of the applicant where the deed's download link belongs -- Notify renders it as text, the
        // row is marked Sent, and every test here would still pass. DocumentUploadsTest proves the refusals
        // themselves; this proves the sender acts on one.
        Notification row = notification();
        row.setTemplate(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.name());
        row.setPersonalisation(Map.of());

        assertThat(sender.send(row))
            .isEqualTo(SendOutcome.failed(
                NotificationFailure.TECHNICAL,
                "the " + FormsForCompletionPersonalisation.DEED_DOCUMENT
                    + " document reference is missing from this email"));
        assertThat(notify.emailRequests()).as("and Notify was never asked").isEmpty();
    }

    /** What Notify was told to set as the reply-to on the nth send, or empty if it was told nothing. */
    private String replyToOf(int request) {
        try {
            return JSON.readTree(notify.emailRequests().get(request).getBodyAsString())
                .path("email_reply_to_id")
                .asText();
        } catch (Exception unreadable) {
            throw new AssertionError("StubNotify received unreadable JSON", unreadable);
        }
    }

    /** The same row, for the template whose words ask the applicant to reply. */
    private static Notification confirmationRow() {
        Notification row = notification();
        row.setTemplate(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.name());
        return row;
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
