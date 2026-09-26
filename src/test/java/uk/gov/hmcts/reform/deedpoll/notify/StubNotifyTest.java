package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.SendEmailResponse;
import uk.gov.service.notify.TemplatePreview;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The double every later task sends through, and the taxonomy proved over real HTTP.
 *
 * <p>Nothing in this plan can talk to GOV.UK Notify: there is no service, no key and no template. So the stub is
 * load-bearing rather than convenient, and it is worth its own test -- a stub whose response body the Notify
 * client cannot parse would fail every test that used it with an error about JSON rather than about the feature.
 *
 * <p>{@link NotificationFailureTest} states the mapping as arithmetic on an {@code int}. This states it end to
 * end: the client has to reach the stub, read the status off a real response, and carry it on the exception it
 * throws, before {@link NotificationFailure#classify(NotificationClientException)} can see it. That exception's
 * status-carrying constructor is package-private in the client's jar, so over HTTP is the <em>only</em> way to
 * get one -- which is exactly why the stub can be told to answer any status.
 *
 * <p>Container-free: WireMock on a dynamic port, no database, so CI runs it.
 */
class StubNotifyTest {

    private final StubNotify notify = new StubNotify();

    @BeforeEach
    void startStub() {
        notify.start();
    }

    @AfterEach
    void stopStub() {
        notify.stop();
    }

    @Test
    @DisplayName("serves a send the Notify client can parse, and records what it was asked to send")
    void servesASend() throws Exception {
        SendEmailResponse response = notify.client().sendEmail(
            StubNotify.TEMPLATE_ID, "applicant@example.com", Map.of("judges_reason", "No evidence of use"),
            "outbox-row-1");

        assertThat(response.getNotificationId()).isEqualTo(StubNotify.NOTIFICATION_ID);
        assertThat(response.getReference()).contains("outbox-row-1");
        assertThat(notify.emailRequests()).hasSize(1);
        // The request body is what a later task asserts the personalisation of, so it has to survive the round
        // trip readably rather than only the response being right.
        assertThat(notify.emailRequests().getFirst().getBodyAsString())
            .contains("applicant@example.com")
            .contains("No evidence of use")
            .contains(StubNotify.TEMPLATE_ID);
    }

    @Test
    @DisplayName("renders a preview with a subject, html and a version -- Notify's own rendering, not ours")
    void servesAPreview() throws Exception {
        TemplatePreview preview = notify.client()
            .generateTemplatePreview(StubNotify.TEMPLATE_ID, Map.of("judges_reason", "No evidence of use"));

        assertThat(preview.getSubject()).contains("Your application has been rejected");
        assertThat(preview.getHtml()).contains("<p>Because the Master said so.</p>");
        assertThat(preview.getVersion()).isEqualTo(StubNotify.TEMPLATE_VERSION);
        assertThat(notify.previewRequests()).hasSize(1);
    }

    @Test
    @DisplayName("the send and the preview are separate stubs, so a preview can succeed where a send fails")
    void sendAndPreviewAreIndependent() throws Exception {
        notify.stubSendStatus(500);

        // Which is the shape of the real thing: the placeholder template makes a preview fail while the key is
        // what makes a send fail, and a task asserting one must not have to arrange the other.
        assertThat(notify.client().generateTemplatePreview(StubNotify.TEMPLATE_ID, Map.of()).getSubject())
            .isPresent();
        assertThatExceptionOfType(NotificationClientException.class).isThrownBy(() -> notify.client()
            .sendEmail(StubNotify.TEMPLATE_ID, "applicant@example.com", Map.of(), "outbox-row-1"));
    }

    @ParameterizedTest
    @CsvSource({"400,PERMANENT", "403,PERMANENT", "429,TEMPORARY", "500,TEMPORARY", "418,TECHNICAL"})
    @DisplayName("a send answered with any status classifies as the taxonomy says, over real HTTP")
    void classifiesWhateverNotifyAnswersASendWith(int status, NotificationFailure expected) {
        notify.stubSendStatus(status);
        NotificationClient client = notify.client();

        NotificationClientException thrown = catchThrowableOfType(NotificationClientException.class,
            () -> client.sendEmail(StubNotify.TEMPLATE_ID, "applicant@example.com", Map.of(), "outbox-row-1"));

        assertThat(thrown.getHttpResult()).isEqualTo(status);
        assertThat(NotificationFailure.classify(thrown)).isEqualTo(expected);
    }

    @Test
    @DisplayName("a preview answered with 400 is permanent -- which is what the placeholder template will get")
    void classifiesAPreviewRefusal() {
        // The contingency in the plan: until notify-0 provisions a real template ID, a preview against real
        // Notify is a 400. Correct behaviour rather than a defect, and stated here so nobody debugs it twice.
        notify.stubPreviewStatus(400);
        NotificationClient client = notify.client();

        NotificationClientException thrown = catchThrowableOfType(NotificationClientException.class,
            () -> client.generateTemplatePreview(NotifyProperties.UNPROVISIONED_TEMPLATE_ID, Map.of()));

        assertThat(NotificationFailure.classify(thrown)).isEqualTo(NotificationFailure.PERMANENT);
    }

    @Test
    @DisplayName("a template with required placeholders refuses a preview lacking one, in Notify's words")
    void refusesAPreviewLackingARequiredPlaceholder() {
        notify.stubTemplateRequiring("reference_number", "LOC021 statutory declaration.pdf");
        NotificationClient client = notify.client();

        NotificationClientException thrown = catchThrowableOfType(NotificationClientException.class,
            () -> client.generateTemplatePreview(StubNotify.TEMPLATE_ID, Map.of("reference_number", "DP1")));

        // The status and the message the real Notify answered in notify-4a Task 3, read the way the service reads it.
        assertThat(thrown.getHttpResult()).isEqualTo(400);
        assertThat(AttachmentStandIns.missingKeys(thrown.getMessage(), new ObjectMapper()))
            .containsExactly("LOC021 statutory declaration.pdf");
    }

    @Test
    @DisplayName("a template with required placeholders renders each value once all are supplied")
    void rendersAPreviewSupplyingEveryPlaceholder() throws Exception {
        notify.stubTemplateRequiring("reference_number", "LOC021 statutory declaration.pdf");

        TemplatePreview preview = notify.client().generateTemplatePreview(StubNotify.TEMPLATE_ID, Map.of(
            "reference_number", "DP1", "LOC021 statutory declaration.pdf", "a stand-in"));

        assertThat(preview.getHtml().orElseThrow()).contains("<p>DP1</p>").contains("<p>a stand-in</p>");
        assertThat(preview.getVersion()).isEqualTo(StubNotify.TEMPLATE_VERSION);
    }

    @Test
    @DisplayName("a template with required placeholders still accepts a send without them, as the real one does")
    void acceptsASendLackingARequiredPlaceholder() throws Exception {
        // A real send fills an attached file in itself; only the preview demands a value for it.
        notify.stubTemplateRequiring("reference_number", "LOC021 statutory declaration.pdf");

        assertThat(notify.client()
            .sendEmail(StubNotify.TEMPLATE_ID, "applicant@example.com", Map.of("reference_number", "DP1"),
                       "outbox-row-1")
            .getNotificationId()).isEqualTo(StubNotify.NOTIFICATION_ID);
    }

    @Test
    @DisplayName("its API key is well-formed and invented, so the client authenticates against nothing real")
    void apiKeyIsWellFormedAndInvented() {
        // The client splits the last two UUIDs out of the key to sign its JWT, so a malformed key fails inside
        // the client rather than at Notify -- which would make every stubbed status untestable.
        NotificationClient client = notify.client();

        assertThat(client.getServiceId()).isEqualTo(StubNotify.SERVICE_ID);
        assertThat(client.getApiKey()).isNotBlank();
        assertThat(client.getBaseUrl()).isEqualTo(notify.baseUrl());
    }

    @Test
    @DisplayName("stubDefaults resets what a test asked for, so one test's 500 is not the next test's Notify")
    void defaultsReset() throws Exception {
        notify.stubSendStatus(500);
        notify.stubDefaults();

        assertThat(notify.client()
            .sendEmail(StubNotify.TEMPLATE_ID, "applicant@example.com", Map.of(), "outbox-row-1")
            .getNotificationId()).isEqualTo(StubNotify.NOTIFICATION_ID);
    }
}
