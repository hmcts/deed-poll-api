package uk.gov.hmcts.reform.deedpoll.api.caseworker.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.auth.StubIdam;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.CreateReferralInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.DirectionInput;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralSchemas;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.notify.ApplicationRejectedPersonalisation;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailService;
import uk.gov.hmcts.reform.deedpoll.notify.EmailExclusionGroup;
import uk.gov.hmcts.reform.deedpoll.notify.EmailNotes;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.notify.MissingDocumentsPersonalisation;
import uk.gov.hmcts.reform.deedpoll.notify.StubNotify;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The three caseworker email endpoints, over HTTP, against a real database, a stub IDAM and a stub Notify.
 *
 * <p>The whole context rather than a {@code @WebMvcTest} slice, for the reason the other controller tests record:
 * a slice does not get {@code SecurityConfiguration}, so it would assert Spring Boot's defaults rather than this
 * service's, and the send has to really commit for an assertion to read the stored row back.
 *
 * <p><strong>No test here touches the real Notify.</strong> {@code notify.api-url} points at {@link StubNotify}
 * and the API key is invented, so the preview goes over real HTTP to a WireMock that answers Notify's own shapes.
 * That is the only Notify this repo permits: there is no deed poll Notify service and no key.
 *
 * <p><strong>The template is provisioned in this context and is not in any real one.</strong> The stub's template
 * ID is registered as {@code notify.templates.application-rejected}, which is what lets a preview render at all --
 * with the shipped {@code DEED_POLL_PLACEHOLDER} the answer is a 502, and
 * {@link #previewAnswers502WhenNotifyRefusesTheTemplate()} is that case stated through the stub rather than left
 * as a thing to discover in an environment.
 *
 * <p><strong>What is here is what only a booted service can show.</strong> The slot validation, the resolved
 * values and the failure taxonomy are pinned container-free in {@code EmailSlotsTest},
 * {@code ApplicationRejectedPersonalisationTest} and {@code NotificationFailureTest}, which run on the Jenkins
 * agent where this class is excluded. This class asserts the routes are wired to those rules, which status code
 * each answer becomes, that a preview writes nothing and that a send writes exactly one row and one case note.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CaseworkerEmailControllerTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference number without a Date Received to derive the year from. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    /** An id of the right shape that nothing was ever stored under. */
    private static final String UNKNOWN_ID = "11111111-1111-1111-1111-111111111111";

    private static final String APPLICANT_EMAIL = "applicant@example.com";

    /**
     * What the judge wrote, which is the text the judges_reason slot is pre-populated from.
     */
    private static final String DIRECTION = "Refused: the applicant has not shown a settled intention.";

    private static final String JUDGE_NAME = "District Judge Bell";

    /** The one email type this service has, as the URL and the configuration spell it. */
    private static final String REJECTED = EmailTemplate.APPLICATION_REJECTED.key();

    /** The email that asks the applicant to confirm, which is the one that starts a clock on the referral. */
    private static final String CONFIRMATION = EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key();

    /** The case-driven chase, whose entitlement is the case's own state rather than a judge's decision. */
    private static final String CHASE = EmailTemplate.CHASE_MISSING_DOCUMENTS.key();

    /**
     * Where ⑤ sits in the availability list, which is enum order.
     *
     * <p>Read off the enum rather than written as a number, for the reason {@code CaseEmailServiceTest} looks its
     * entry up by template: a fourth email declared before this one would otherwise make every assertion below
     * quietly describe a different template.
     */
    private static final int CHASE_INDEX = EmailTemplate.CHASE_MISSING_DOCUMENTS.ordinal();

    /**
     * The monitored inbox ④ is sent from, invented here as every Notify id in this class is.
     *
     * <p>Configured at all because the sender refuses ④ permanently without one: the email's words ask for a reply,
     * and Notify would drop a blank reply-to and send from no-reply, losing the answer the email exists to collect.
     */
    private static final String REPLY_TO_ID = "8a1c9b2e-0000-4000-8000-000000000042";

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final StubIdam IDAM = new StubIdam();

    private static final StubNotify NOTIFY = new StubNotify();

    static {
        // Started here rather than in @BeforeAll: @DynamicPropertySource is read while the context is being
        // built, which is before any @BeforeAll callback could have started either stub.
        IDAM.start();
        NOTIFY.start();
    }

    @AfterAll
    static void stopStubs() {
        IDAM.stop();
        NOTIFY.stop();
    }

    @DynamicPropertySource
    static void stubProperties(DynamicPropertyRegistry registry) {
        registry.add("idam.api.issuer-url", IDAM::issuerUrl);
        registry.add("idam.api.audiences", () -> StubIdam.AUDIENCE);
        registry.add("notify.api-url", NOTIFY::baseUrl);
        registry.add("notify.api-key", NOTIFY::apiKey);
        // The template that DEED_POLL_PLACEHOLDER stands in for until notify-0 provisions one. Registered so a
        // preview can render; the unprovisioned case is asserted by making the stub refuse instead.
        registry.add("notify.templates.application-rejected", () -> StubNotify.TEMPLATE_ID);
        // ④ as well, with the monitored inbox it needs: its own words ask the applicant to reply, so the sender
        // refuses it permanently without a reply-to. Registered so that the confirmation clock can be asserted
        // through a send that really reaches the stub rather than one that fails before it.
        registry.add("notify.templates.request-confirmation-to-proceed", () -> StubNotify.TEMPLATE_ID);
        // ⑤ too, which needs the same reply-to for the same reason: it asks the applicant to send the missing
        // documents by replying. Registered so the journey below can be driven all the way to a drained send.
        registry.add("notify.templates.chase-missing-documents", () -> StubNotify.TEMPLATE_ID);
        registry.add("notify.email-reply-to-id", () -> REPLY_TO_ID);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private ReferralRepository referrals;

    @Autowired
    private NotificationRepository notifications;

    /** Only to reach a refused referral, which needs the judicial transitions and they have no route here. */
    @Autowired
    private ReferralService referralService;

    /**
     * Only to assert that the outbox row and the confirmation stamp are one transaction.
     *
     * <p>Called directly, because over HTTP the transaction always commits and the claim is about what happens when
     * it does not -- see {@code SendEmail.ConfirmationClock.neitherSurvivesARollback}.
     */
    @Autowired
    private CaseEmailService emails;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DeedPollNoteRepository notes;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @BeforeEach
    void setUp() {
        clearTables();
        IDAM.stubDefaults();
        NOTIFY.stubDefaults();
    }

    @AfterEach
    void tearDown() {
        clearTables();
    }

    private void clearTables() {
        notifications.deleteAll();
        referrals.deleteAll();
        notes.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    @Nested
    @DisplayName("without a token")
    class Unauthenticated {

        @Test
        @DisplayName("refuses to say which emails apply")
        void refusesTheList() throws Exception {
            mockMvc.perform(get("/caseworker/cases/" + refusedCase() + "/emails"))
                .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("refuses a preview, and asks Notify nothing")
        void refusesAPreview() throws Exception {
            mockMvc.perform(post("/caseworker/cases/" + refusedCase() + "/emails/" + REJECTED + "/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized());

            // The refusal happens before the handler, so an unauthenticated caller cannot spend this service's
            // Notify rate limit either.
            assertThat(NOTIFY.previewRequests()).isEmpty();
        }

        @Test
        @DisplayName("refuses a send, and owes nothing")
        void refusesASend() throws Exception {
            mockMvc.perform(post("/caseworker/cases/" + refusedCase() + "/emails/" + REJECTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                .andExpect(status().isUnauthorized());

            // A 401 that still enqueued would be the worst of both answers: the applicant would be written to by
            // a caller this service refused to identify.
            assertThat(notifications.count()).isZero();
        }
    }

    @Nested
    @DisplayName("GET /caseworker/cases/{id}/emails")
    class ListEmails {

        @Test
        @DisplayName("says the refusal email applies once a Master has refused the application")
        void appliesAfterARefusal() throws Exception {
            authorised(get("/caseworker/cases/" + refusedCase() + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.length()").value(EmailTemplate.values().length))
                .andExpect(jsonPath("$.availability[0].template").value(REJECTED))
                .andExpect(jsonPath("$.availability[0].applicable").value(true))
                // No reason when it applies: the field exists to explain a refusal, not to be filled in anyway.
                .andExpect(jsonPath("$.availability[0].reason").doesNotExist())
                // Provisioned because this context points at the stub. Every real environment answers false until
                // notify-0 lands, which is what a tab should say instead of offering the button.
                .andExpect(jsonPath("$.availability[0].provisioned").value(true))
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.slotDefaults[0].template").value(REJECTED))
                .andExpect(jsonPath("$.slotDefaults[0].fields.judges_reason").value(DIRECTION))
                .andExpect(jsonPath("$.slotDefaults[0].fields.additional_notes").value(""));

            assertThat(NOTIFY.previewRequests()).isEmpty();
            assertThat(NOTIFY.emailRequests()).isEmpty();
        }

        @Test
        @DisplayName("publishes the slots a form has to render, with the caps the schema enforces")
        void publishesTheSlots() throws Exception {
            authorised(get("/caseworker/cases/" + refusedCase() + "/emails"))
                .andExpect(status().isOk())
                // This template's slots, not every slot this service has: since ⑤ there is one no referral
                // template takes, and a count off the enum would assert that the rejection email published it.
                .andExpect(jsonPath("$.availability[0].slots.length()")
                               .value(EmailTemplate.APPLICATION_REJECTED.slots().size()))
                .andExpect(jsonPath("$.availability[0].slots[0].name")
                               .value(EmailSlot.JUDGES_REASON.placeholder()))
                .andExpect(jsonPath("$.availability[0].slots[0].required").value(true))
                // ReferralSchemas' own cap, so a direction that was recordable is always sendable unedited.
                .andExpect(jsonPath("$.availability[0].slots[0].maxLength")
                               .value(ReferralSchemas.MAX_DIRECTIONS_LENGTH))
                .andExpect(jsonPath("$.availability[0].slots[1].name")
                               .value(EmailSlot.ADDITIONAL_NOTES.placeholder()))
                .andExpect(jsonPath("$.availability[0].slots[1].required").value(false))
                .andExpect(jsonPath("$.availability[0].slots[1].maxLength")
                               .value(ReferralSchemas.MAX_NOTES_LENGTH));
        }

        @Test
        @DisplayName("says why it does not apply to a case nobody has refused, rather than omitting it")
        void explainsWhyItDoesNotApply() throws Exception {
            // Listed and not applicable, which is the whole design of this response: a shorter list would leave a
            // caseworker asking somebody why the refusal letter is missing.
            authorised(get("/caseworker/cases/" + newCase() + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability.length()").value(EmailTemplate.values().length))
                .andExpect(jsonPath("$.availability[0].template").value(REJECTED))
                .andExpect(jsonPath("$.availability[0].applicable").value(false))
                .andExpect(jsonPath("$.availability[0].reason").value(Matchers.containsString("refused")))
                .andExpect(jsonPath("$.history").isEmpty())
                .andExpect(jsonPath("$.slotDefaults").isEmpty());
        }

        @Test
        @DisplayName("does not apply to a case whose referral was approved rather than refused")
        void doesNotApplyToAnApproval() throws Exception {
            // The other half of the pair: the referral exists, is Decided, and says the opposite. A predicate on
            // "has a decision" rather than on "was refused" would send a refusal letter to an approved applicant.
            String caseId = newCase();
            decide(caseId, raise(caseId), MasterDecision.Approved, "Approved. Publish in the Gazette.");

            authorised(get("/caseworker/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability[0].applicable").value(false));
        }

        @Test
        @DisplayName("returns availability, history and defaults without a Notify read")
        void returnsEverythingTheTabNeeds() throws Exception {
            String caseId = refusedCase();
            authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());
            assertThat(NOTIFY.emailRequests()).hasSize(1);

            authorised(get("/caseworker/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability[0].template").value(REJECTED))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].template").value(REJECTED))
                .andExpect(jsonPath("$.history[0].templateName").value("Application rejected"))
                .andExpect(jsonPath("$.history[0].recipient").value(APPLICANT_EMAIL))
                .andExpect(jsonPath("$.history[0].notifyState").value("Sent"))
                .andExpect(jsonPath("$.history[0].subject").value("Your application has been rejected"))
                .andExpect(jsonPath("$.history[0].body").value("Because the Master said so."))
                .andExpect(jsonPath("$.history[0].documents").isEmpty())
                .andExpect(jsonPath("$.slotDefaults[0].template").value(REJECTED))
                .andExpect(jsonPath("$.slotDefaults[0].fields.judges_reason").value(DIRECTION));

            assertThat(NOTIFY.emailRequests())
                .as("the GET reads its stored history rather than asking Notify")
                .hasSize(1);
            assertThat(NOTIFY.previewRequests()).isEmpty();
        }

        @Test
        @DisplayName("404s for a case that does not exist, rather than answering an empty list")
        void notFoundForAMissingCase() throws Exception {
            authorised(get("/caseworker/cases/" + UNKNOWN_ID + "/emails"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DeedPoll " + UNKNOWN_ID + " not found"));
        }
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/emails/{template}/preview")
    class PreviewEmail {

        @Test
        @DisplayName("returns Notify's own subject, HTML and template version")
        void returnsNotifysRendering() throws Exception {
            authorised(post(previewOf(refusedCase())), "{}")
                .andExpect(status().isOk())
                // Notify rendered this; nothing in the service composes it. A local renderer would be a second
                // copy of the content to drift and would lose the GOV.UK styling.
                .andExpect(jsonPath("$.subject").value("Your application has been rejected"))
                .andExpect(jsonPath("$.html").value("<p>Because the Master said so.</p>"))
                // The wording the caseworker read, which is what makes "what was this applicant told" answerable.
                .andExpect(jsonPath("$.templateVersion").value(StubNotify.TEMPLATE_VERSION));

            assertThat(NOTIFY.previewRequests()).hasSize(1);
        }

        @Test
        @DisplayName("sends Notify the resolved values and the pre-populated direction, and nothing else")
        void sendsTheResolvedPersonalisation() throws Exception {
            authorised(post(previewOf(refusedCase())), "{}").andExpect(status().isOk());

            Map<String, Object> personalisation = previewedPersonalisation();
            assertThat(personalisation)
                .containsEntry(ApplicationRejectedPersonalisation.OLD_FORENAME, "Alex")
                .containsEntry(ApplicationRejectedPersonalisation.OLD_SURNAME, "Okonjo")
                // A JSON boolean, because Notify's conditional reads one directly -- and true only for Online.
                .containsEntry(ApplicationRejectedPersonalisation.APPLIED_ONLINE, true)
                // Pre-populated from the referral, unaltered: the usual case is a caseworker sending the judge's
                // words as written, and they do not have to retype them to do it.
                .containsEntry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION)
                .containsKey(ApplicationRejectedPersonalisation.REFERENCE_NUMBER)
                .containsKey(ApplicationRejectedPersonalisation.ONLINE_APPLICATION_REFERENCE)
                // Present even though the caseworker added nothing: Notify refuses a send whose personalisation
                // omits a placeholder the template uses, so an absent key would be a permanent failure later.
                .containsEntry(EmailSlot.ADDITIONAL_NOTES.placeholder(), "")
                .hasSize(EmailTemplate.APPLICATION_REJECTED.personalisationKeys().size());
        }

        @Test
        @DisplayName("renders the caseworker's own text where they replaced the judge's")
        void rendersTheCaseworkersText() throws Exception {
            authorised(post(previewOf(refusedCase())),
                "{\"judges_reason\":\"Refused for the reasons given in court.\","
                    + "\"additional_notes\":\"Please call the office if anything is unclear.\"}")
                .andExpect(status().isOk());

            assertThat(previewedPersonalisation())
                .containsEntry(EmailSlot.JUDGES_REASON.placeholder(), "Refused for the reasons given in court.")
                .containsEntry(EmailSlot.ADDITIONAL_NOTES.placeholder(),
                               "Please call the office if anything is unclear.");
        }

        @Test
        @DisplayName("writes no outbox row, so previewing is not sending")
        void writesNoRow() throws Exception {
            authorised(post(previewOf(refusedCase())), "{}").andExpect(status().isOk());

            assertThat(notifications.count()).isZero();
        }

        @Test
        @DisplayName("400s an attempt to set a resolved value, naming the key")
        void refusesAResolvedValue() throws Exception {
            // Decision 12, over HTTP: a caseworker altering the reference an applicant is told is a correctness
            // and a fraud problem, so it is refused and named rather than stripped and silently ignored.
            authorised(post(previewOf(refusedCase())),
                "{\"judges_reason\":\"Refused.\",\"reference_number\":\"2026/999999\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field")
                               .value(ApplicationRejectedPersonalisation.REFERENCE_NUMBER));

            assertThat(NOTIFY.previewRequests()).isEmpty();
        }

        @Test
        @DisplayName("400s an unknown slot, and an over-long one, naming both at once")
        void refusesUnknownAndOverLongSlots() throws Exception {
            authorised(post(previewOf(refusedCase())),
                "{\"whatever\":\"x\",\"additional_notes\":\"" + "n".repeat(ReferralSchemas.MAX_NOTES_LENGTH + 1)
                    + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        }

        @Test
        @DisplayName("409s a case nobody has refused, because there is nothing to quote")
        void refusesACaseWithNoRefusal() throws Exception {
            // 409 and not 400: nothing about the payload is wrong, and the same request succeeds unchanged once a
            // Master has refused the application.
            authorised(post(previewOf(newCase())), "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(Matchers.containsString("refused")));
        }

        @Test
        @DisplayName("404s a case that does not exist, and an email type that does not either")
        void notFoundForAMissingCaseOrTemplate() throws Exception {
            authorised(post(previewOf(UNKNOWN_ID)), "{}")
                .andExpect(status().isNotFound());

            // A path nothing is served at, exactly as an unknown case id is -- and the answer names the keys that
            // would have worked without echoing the one that did not.
            authorised(post("/caseworker/cases/" + refusedCase() + "/emails/whatever/preview"), "{}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value(Matchers.containsString(REJECTED)));
        }
    }

    @Test
    @DisplayName("502s when Notify refuses the template, which is what an unprovisioned template ID does")
    void previewAnswers502WhenNotifyRefusesTheTemplate() throws Exception {
        // The contingency stated rather than discovered: until notify-0 creates the Notify service the template ID
        // is DEED_POLL_PLACEHOLDER, so Notify answers 400 -- there is no such template -- and the honest answer is
        // that the dependency refused. A 500 would send whoever is paged to read this service's logs.
        NOTIFY.stubPreviewStatus(400);

        authorised(post(previewOf(refusedCase())), "{}")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(Matchers.containsString("PERMANENT")))
            // Nothing the client library said reaches the caller: its message contains Notify's response.
            .andExpect(jsonPath("$.error").value(Matchers.not(
                Matchers.containsString(StubNotify.SERVICE_ID))));

        assertThat(notifications.count()).isZero();
    }

    @Test
    @DisplayName("502s a Notify outage too, with the verdict that tells it apart from a refusal")
    void previewAnswers502WhenNotifyIsUnwell() throws Exception {
        // The same status for a temporary failure as for a permanent one, deliberately: a preview has no retry to
        // model -- the caseworker's retry is pressing the button again -- so a 503 would tell them nothing they
        // could act on differently. The verdict in the message is what distinguishes the two for whoever reads it,
        // and the outbox is where a retry is actually modelled.
        //
        // Stated here rather than in CaseEmailServiceTest because only a real round trip can carry a status: the
        // client's status-carrying constructor is package-private, and its public one reports 400 whatever the
        // message says.
        NOTIFY.stubPreviewStatus(500);

        authorised(post(previewOf(refusedCase())), "{}")
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value(Matchers.containsString("TEMPORARY")));
    }

    @Nested
    @DisplayName("POST /caseworker/cases/{id}/emails/{template}")
    class SendEmail {

        @Test
        @DisplayName("enqueues and drains exactly one row, frozen with what was resolved")
        void enqueuesOneRow() throws Exception {
            String caseId = refusedCase();
            String referralId = referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId).getFirst().getId().toString();

            authorised(post(sendOf(caseId)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.recipient").value(APPLICANT_EMAIL))
                .andExpect(jsonPath("$.enqueued").value(true));

            assertThat(notifications.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getState()).isEqualTo(NotificationState.Sent);
                assertThat(row.getDeedPollId()).isEqualTo(caseId);
                assertThat(row.getReferralId()).hasToString(referralId);
                // The member name, never the Notify UUID: the UUID is configuration and differs per environment.
                assertThat(row.getTemplate()).isEqualTo(EmailTemplate.APPLICATION_REJECTED.name());
                assertThat(row.getRecipient()).isEqualTo(APPLICANT_EMAIL);
                // Enqueued without a preview; the version comes from Notify accepting the send.
                assertThat(row.getTemplateVersion()).isEqualTo(StubNotify.TEMPLATE_VERSION);
                assertThat(row.getAttempts()).isOne();
                assertThat(row.getProviderNotificationId()).isEqualTo(StubNotify.NOTIFICATION_ID);
                assertThat(row.getSubject()).isEqualTo("Your application has been rejected");
                assertThat(row.getBody()).isEqualTo("Because the Master said so.");
                // The caseworker, because they authored part of what the applicant reads.
                assertThat(row.getCreatedBy()).isEqualTo("A Caseworker");
                assertThat(row.getNextAttemptAt()).isNotNull();
            });
        }

        @Test
        @DisplayName("freezes the resolved personalisation on the row, jsonb and all")
        void freezesThePersonalisation() throws Exception {
            authorised(post(sendOf(refusedCase())), "{\"additional_notes\":\"Ring the office.\"}")
                .andExpect(status().isOk());

            Notification row = notifications.findAll().getFirst();
            assertThat(row.getPersonalisation())
                .containsEntry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION)
                .containsEntry(EmailSlot.ADDITIONAL_NOTES.placeholder(), "Ring the office.")
                .containsEntry(ApplicationRejectedPersonalisation.OLD_SURNAME, "Okonjo")
                .containsEntry(ApplicationRejectedPersonalisation.APPLIED_ONLINE, true);
            // Stored rather than rebuildable: part of this is text a caseworker wrote and may have edited, so
            // recomputing it later would answer "what would we send now" instead.
            assertThat(row.getPersonalisation())
                .hasSize(EmailTemplate.APPLICATION_REJECTED.personalisationKeys().size());
        }

        @Test
        @DisplayName("drains after enqueue commits, carrying the row id as Notify's reference")
        void drainsAfterEnqueueCommits() throws Exception {
            authorised(post(sendOf(refusedCase())), "{}").andExpect(status().isOk());

            Notification row = notifications.findAll().getFirst();
            assertThat(NOTIFY.emailRequests()).singleElement().satisfies(request -> {
                try {
                    assertThat(JSON.readTree(request.getBodyAsString()).path("reference").asText())
                        .isEqualTo(row.getId().toString());
                } catch (Exception exception) {
                    throw new AssertionError("StubNotify received unreadable JSON", exception);
                }
            });
            assertThat(NOTIFY.previewRequests()).isEmpty();
        }

        @Test
        @DisplayName("a 400 refusal fails the row terminally and does not retry it")
        void permanentRefusalFailsTheRow() throws Exception {
            NOTIFY.stubSendStatus(400);

            authorised(post(sendOf(refusedCase())), "{}").andExpect(status().isOk());

            assertThat(notifications.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getState()).isEqualTo(NotificationState.Failed);
                assertThat(row.getAttempts()).isOne();
                assertThat(row.getProviderNotificationId()).isNull();
                assertThat(row.getSubject()).isNull();
                assertThat(row.getBody()).isNull();
                assertThat(row.getFailureReason())
                    .isEqualTo("PERMANENT: GOV.UK Notify refused the send with status 400");
            });
            assertThat(NOTIFY.emailRequests()).hasSize(1);
        }

        @Test
        @DisplayName("a 500 leaves the row Pending, counted once and backed off")
        void temporaryFailureBacksTheRowOff() throws Exception {
            NOTIFY.stubSendStatus(500);

            authorised(post(sendOf(refusedCase())), "{}").andExpect(status().isOk());

            assertThat(notifications.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getState()).isEqualTo(NotificationState.Pending);
                assertThat(row.getAttempts()).isOne();
                assertThat(row.getProviderNotificationId()).isNull();
                assertThat(row.getFailureReason())
                    .isEqualTo("TEMPORARY: GOV.UK Notify refused the send with status 500");
                assertThat(row.getNextAttemptAt()).isAfter(row.getModifiedOn());
            });
            assertThat(NOTIFY.emailRequests()).hasSize(1);
        }

        @Test
        @DisplayName("a second send writes nothing and answers with the first row")
        void refusesASecondSendWithoutFailing() throws Exception {
            String caseId = refusedCase();
            String first = JSON.readTree(authorised(post(sendOf(caseId)), "{}")
                                             .andExpect(status().isOk())
                                             .andReturn()
                                             .getResponse()
                                             .getContentAsString())
                .get("id").asText();

            // A SECOND COMPOSE JOURNEY, which the default helper's fresh Idempotency-Key is: a caseworker who came
            // back to the tab later and sent again. Refused by the referral's exclusion group rather than by the
            // request id -- one referral gets one of these emails, whoever asks and however often -- and refused as
            // a success, because nothing about the request is wrong and a failure would invite a third attempt.
            authorised(post(sendOf(caseId)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.enqueued").value(false));

            assertThat(notifications.count()).isOne();
        }

        @Test
        @DisplayName("a repeated POST of one journey owes one email, over HTTP and with the header that says so")
        void repeatingOneJourneyOwesOneEmail() throws Exception {
            // The double-click and the retried POST, which are what the header exists to identify -- and the only
            // route by which they are distinguishable from the deliberate second send above. Over real HTTP,
            // because the key is a header: a route that bound it per request, or defaulted it, would pass every
            // service-level test and send two emails here.
            String caseId = refusedCase();
            UUID journey = UUID.randomUUID();
            String first = JSON.readTree(authorised(post(sendOf(caseId)), "{}", journey)
                                             .andExpect(status().isOk())
                                             .andExpect(jsonPath("$.enqueued").value(true))
                                             .andReturn()
                                             .getResponse()
                                             .getContentAsString())
                .get("id").asText();

            authorised(post(sendOf(caseId)), "{}", journey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.enqueued").value(false));

            assertThat(notifications.findAll()).singleElement().satisfies(row ->
                assertThat(row.getRequestId()).isEqualTo(journey));
            // Notify heard about it once, which is the fact the applicant experiences. The drain runs after each
            // send, so a second row would have been a second email rather than merely a second row.
            assertThat(NOTIFY.emailRequests()).hasSize(1);
        }

        @Test
        @DisplayName("409s a journey reused for different text, and leaves the first email owed")
        void refusesAJourneyReusedForDifferentText() throws Exception {
            // The other answer the header can give, over HTTP: the key says "this is the send I already made" and
            // the body says otherwise. 409 rather than 400, because the same body under a request of its own would
            // be accepted -- so there is no field to correct.
            String caseId = refusedCase();
            UUID journey = UUID.randomUUID();
            authorised(post(sendOf(caseId)), "{\"additional_notes\":\"Ring the office.\"}", journey)
                .andExpect(status().isOk());

            authorised(post(sendOf(caseId)), "{\"additional_notes\":\"Write to the office.\"}", journey)
                .andExpect(status().isConflict())
                // The refusal's own wording rather than the constant, which is package-private like every other
                // reason in that service -- and the exact sentence is pinned in CaseEmailServiceTest. What is
                // asserted here is that this refusal and not another one reached the caseworker, and that neither
                // email's text came with it.
                .andExpect(jsonPath("$.error").value(containsString("start composing the email again")))
                .andExpect(jsonPath("$.error").value(not(containsString("Write to the office"))));

            assertThat(notifications.findAll()).singleElement().satisfies(row ->
                assertThat(row.getPersonalisation())
                    .containsEntry(EmailSlot.ADDITIONAL_NOTES.placeholder(), "Ring the office."));
            assertThat(NOTIFY.emailRequests()).hasSize(1);
        }

        @Test
        @DisplayName("400s a send with no Idempotency-Key at all, and owes nothing")
        void refusesASendWithNoIdempotencyKey() throws Exception {
            // Over the real filter chain and the real advice, so the 400 is the running service's answer and not a
            // slice's. CaseworkerEmailIdempotencyHeaderTest is the Docker-free half of the same claim.
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(IDAM.accessToken());

            mockMvc.perform(post(sendOf(refusedCase())).headers(headers)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field")
                               .value(CaseworkerEmailController.IDEMPOTENCY_KEY));

            assertThat(notifications.count()).isZero();
            assertThat(NOTIFY.emailRequests()).isEmpty();
        }

        @Test
        @DisplayName("the request id is nowhere in the send's answer, because it is transport and not the email")
        void theRequestIdIsNotPublished() throws Exception {
            UUID journey = UUID.randomUUID();

            String body = authorised(post(sendOf(refusedCase())), "{}", journey)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

            // Stored on the row and absent from the response: a client that saw it echoed would be invited to
            // treat it as the email's identity, which the row id already is.
            assertThat(body).doesNotContain(journey.toString());
            assertThat(notifications.findAll()).singleElement().satisfies(row ->
                assertThat(row.getRequestId()).isEqualTo(journey));
        }

        @Test
        @DisplayName("422s a case with no applicant email address, naming the column, and owes nothing")
        void refusesABlankRecipient() throws Exception {
            // Decision 7. pcs-api skips a blank address because it sends automatically; here a caseworker has
            // chosen to send, and silence would leave them believing the applicant had been told something.
            String caseId = refusedCase();
            deedPollService.updateDeedPoll(caseId, DeedPollPatch.of(Map.of("applicantEmail", "  ")));

            authorised(post(sendOf(caseId)), "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("applicantEmail"));

            assertThat(notifications.count()).isZero();
        }

        @Test
        @DisplayName("409s a case nobody has refused, and 404s one that does not exist")
        void refusesTheCasesItCannotSendFor() throws Exception {
            authorised(post(sendOf(newCase())), "{}").andExpect(status().isConflict());
            authorised(post(sendOf(UNKNOWN_ID)), "{}").andExpect(status().isNotFound());

            assertThat(notifications.count()).isZero();
        }

        @Test
        @DisplayName("400s a slot payload the schema refuses, and owes nothing")
        void refusesABadPayload() throws Exception {
            authorised(post(sendOf(refusedCase())),
                "{\"judges_reason\":\"" + "j".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH + 1) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(EmailSlot.JUDGES_REASON.placeholder()));

            assertThat(notifications.count()).isZero();
        }

        @Test
        @DisplayName("400s in the service's own error shape for a body that is not JSON")
        void refusesAnUnreadableBody() throws Exception {
            authorised(post(sendOf(refusedCase())), "{ not json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Request body is missing or is not valid JSON"));
        }

        @Test
        @DisplayName("accepts a send with no body at all, because the slot is pre-populated")
        void acceptsNoBody() throws Exception {
            // The ordinary case: the judge's words are already in the slot, so a caseworker who changes nothing
            // sends nothing. A body declared mandatory would refuse exactly that.
            authorised(post(sendOf(refusedCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enqueued").value(true));

            assertThat(notifications.findAll()).singleElement().satisfies(row ->
                assertThat(row.getPersonalisation())
                    .containsEntry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION));
        }

        @Test
        @DisplayName("sends for a caller holding no roles, because there is no may-send permission")
        void sendsForACallerHoldingNoRoles() throws Exception {
            // The default stub token carries no role this service asks about, so this is the ordinary caseworker
            // path -- asserted rather than assumed, because the absence of a gate here is a decision: in the
            // process this replaces, anybody who works a case writes to the applicant.
            authorised(post(sendOf(refusedCase())), "{}").andExpect(status().isOk());

            assertThat(notifications.count()).isOne();
        }

        @Test
        @DisplayName("404s an email type that does not exist, and owes nothing")
        void notFoundForAnUnknownTemplate() throws Exception {
            authorised(post("/caseworker/cases/" + refusedCase() + "/emails/whatever"), "{}")
                .andExpect(status().isNotFound());

            assertThat(notifications.count()).isZero();
        }

        @Nested
        @DisplayName("the confirmation clock")
        class ConfirmationClock {

            @Test
            @DisplayName("commits the stamp with the outbox row, both carrying the same instant")
            void commitsTheStampWithTheRow() throws Exception {
                String caseId = confirmationCase();

                authorised(post(confirmationSendOf(caseId)), "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enqueued").value(true));

                Notification row = notifications.findAll().getFirst();
                assertThat(row.getTemplate())
                    .isEqualTo(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.name());
                assertThat(row.getState()).isEqualTo(NotificationState.Sent);
                // Committed, and with the row's own instant: the stamp is written in the transaction that owes the
                // email rather than when Notify accepts it, so a case's process state does not depend on a delivery
                // outcome that may still be retrying.
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isEqualTo(row.getCreatedOn());
                // And nothing else about the wait: the answer is outstanding, which is what a waiting case is.
                assertThat(storedReferral(caseId).getConfirmationOutcome()).isNull();
                // And the case's own read-model with it, in the same transaction: the case reaches the caseworker
                // waiting list with the email it is waiting on, rather than one drain later or not at all.
                assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince())
                    .isEqualTo(row.getCreatedOn());
            }

            @Test
            @DisplayName("a second send moves neither clock, so a double-click does not reset the wait")
            void secondSendMovesNeitherClock() throws Exception {
                // The unique index refuses the second row, and a stamp written regardless would restart the wait a
                // caseworker is being reminded of -- putting the case back at the bottom of its own list.
                String caseId = confirmationCase();
                authorised(post(confirmationSendOf(caseId)), "{}").andExpect(status().isOk());
                LocalDateTime firstAsk = storedReferral(caseId).getConfirmationRequestedAt();

                authorised(post(confirmationSendOf(caseId)), "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enqueued").value(false));

                assertThat(notifications.count()).isOne();
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isEqualTo(firstAsk);
                assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince())
                    .isEqualTo(firstAsk);
            }

            @Test
            @DisplayName("neither the row nor the stamp survives the other's rollback")
            void neitherSurvivesARollback() {
                // One transaction, asserted rather than assumed. Called on the service inside a transaction this
                // test rolls back, because over HTTP the transaction always commits -- and what decision 4 claims
                // is that there is no state in which an applicant is owed the email and the case is not recorded as
                // waiting, or the reverse.
                String caseId = confirmationCase();

                new TransactionTemplate(transactions).execute(status -> {
                    emails.send(caseId, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), UUID.randomUUID(),
                                caseworker());
                    status.setRollbackOnly();
                    return null;
                });

                assertThat(notifications.count()).isZero();
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isNull();
                // The case's read-model rolls back with them: a waiting list holding a case for an email that was
                // never owed would send a caseworker looking for a reply that was never asked for.
                assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isNull();
            }

            @Test
            @DisplayName("a refused send leaves no clock running, so no case waits for an email nobody owes")
            void refusedSendLeavesNoClockRunning() throws Exception {
                // The counterpart refusal: ③ has already been asked for on this referral, and the two say opposite
                // things about enrolment, so ④ is a 409. A stamp written before the insert was refused would put a
                // case in the waiting list for an email this service never owed anybody.
                String caseId = confirmationCase();
                authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());

                authorised(post(confirmationSendOf(caseId)), "{}").andExpect(status().isConflict());

                assertThat(notifications.count()).isOne();
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isNull();
                assertThat(deedPolls.findById(caseId).orElseThrow().getAwaitingConfirmationSince()).isNull();
            }

            @Test
            @DisplayName("keeps the clock running when Notify refuses the send terminally -- notify-1's flag 8")
            void keepsTheClockRunningWhenTheSendFailsTerminally() throws Exception {
                // Decision 4's named consequence, pinned rather than left as prose: the stamp belongs to the
                // enqueue, so a send that then fails permanently leaves the case waiting for a reply to an email
                // nobody received. That is the trade the outbox's design makes, and it is what notify-1's flag 8 is
                // about -- the two should be closed together.
                NOTIFY.stubSendStatus(400);
                String caseId = confirmationCase();

                authorised(post(confirmationSendOf(caseId)), "{}").andExpect(status().isOk());

                assertThat(notifications.findAll()).singleElement().satisfies(row ->
                    assertThat(row.getState()).isEqualTo(NotificationState.Failed));
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isNotNull();
            }
        }

        @Nested
        @DisplayName("the case note")
        class CaseNote {

            @Test
            @DisplayName("writes one note naming the email and pointing at the tab, as the caseworker")
            void writesOneNoteNamingTheEmailAndTheTab() throws Exception {
                // The whole point of the feature, over HTTP and committed: a caseworker reading the Notes grid --
                // the only timeline this domain has -- can see that the applicant was written to.
                String caseId = refusedCase();
                int before = notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).size();

                authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());

                assertThat(emailNotes(caseId)).singleElement().satisfies(note -> {
                    assertThat(note.getNote())
                        .isEqualTo(EmailNotes.sent(EmailTemplate.APPLICATION_REJECTED))
                        // Which email, and where the record of it is. Named in words rather than linked: admin
                        // renders a note through govukTable's { text: … }, so markup would show as characters.
                        .contains(EmailTemplate.APPLICATION_REJECTED.displayName())
                        .contains("Emails tab")
                        // Never the recipient, the content or the slots -- the tab holds those.
                        .doesNotContain(APPLICANT_EMAIL)
                        .doesNotContain(DIRECTION);
                    // The caseworker who sent it, not the service account: a person chose to send, and on this
                    // email they wrote part of what the applicant reads.
                    assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
                });
                // One note for one send, counted against the grid as a whole so that a note written twice by two
                // paths would fail here even if both wrote the same words.
                assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(before + 1);
            }

            @Test
            @DisplayName("writes one for the confirmation email too, naming that email")
            void writesOneForTheConfirmationEmail() throws Exception {
                // Both templates, because the composer is deliberately not per template and ④ is the one that also
                // starts a clock -- so it is the send with the most going on in its transaction.
                String caseId = confirmationCase();

                authorised(post(confirmationSendOf(caseId)), "{}").andExpect(status().isOk());

                assertThat(emailNotes(caseId)).singleElement().satisfies(note ->
                    assertThat(note.getNote())
                        .isEqualTo(EmailNotes.sent(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)));
            }

            @Test
            @DisplayName("writes no note for a refused send, so no timeline claims an email nobody owes")
            void writesNoNoteForARefusedSend() throws Exception {
                // A 409 that still noted would be the worst of both answers: the grid would say the applicant had
                // been written to and the Emails tab the note points at would have no row to show them.
                String caseId = newCase();
                int before = notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).size();

                authorised(post(sendOf(caseId)), "{}").andExpect(status().isConflict());

                assertThat(emailNotes(caseId)).isEmpty();
                assertThat(notes.findByDeedPollIdOrderByCreatedOnDesc(caseId)).hasSize(before);
            }

            @Test
            @DisplayName("writes no second note when the same email is sent again")
            void writesNoSecondNoteOnARepeatSend() throws Exception {
                // The idempotent repeat: no row is written, so no note is either. A caseworker double-clicking must
                // not leave a case reading as though the applicant had been written to twice.
                String caseId = refusedCase();
                authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());

                authorised(post(sendOf(caseId)), "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.enqueued").value(false));

                assertThat(notifications.count()).isOne();
                assertThat(emailNotes(caseId)).hasSize(1);
            }

            @Test
            @DisplayName("writes a second note when Notify refuses the send terminally, naming the reason's tab")
            void writesANoteWhenTheSendFailsTerminally() throws Exception {
                // The other half of the feature, end to end and committed: the enqueue's note said the email was
                // sent, and the drain's says it was not -- so a caseworker reading the grid finds out that nothing
                // arrived rather than waiting on a reply to an email nobody received. Three notes for what looks
                // like one email is the timeline being honest; see the plan's flag 1.
                NOTIFY.stubSendStatus(400);
                String caseId = refusedCase();

                authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());

                assertThat(notifications.findAll()).singleElement().satisfies(row ->
                    assertThat(row.getState()).isEqualTo(NotificationState.Failed));
                // Both of them: the send and its outcome, which is what makes the timeline honest rather than
                // merely non-empty. Matched by text rather than by position, because the two are written in
                // separate transactions and nothing here depends on how close together their stamps landed.
                assertThat(emailNotes(caseId))
                    .extracting(DeedPollNote::getNote)
                    .containsExactlyInAnyOrder(EmailNotes.sent(EmailTemplate.APPLICATION_REJECTED),
                                               EmailNotes.couldNotBeSent(EmailTemplate.APPLICATION_REJECTED));
                assertThat(emailNotes(caseId))
                    .filteredOn(note -> note.getNote()
                        .equals(EmailNotes.couldNotBeSent(EmailTemplate.APPLICATION_REJECTED)))
                    .singleElement()
                    .satisfies(note -> {
                        assertThat(note.getNote())
                            .contains(EmailTemplate.APPLICATION_REJECTED.displayName())
                            .contains("Emails tab")
                            // Never Notify's own words: unbounded external text, written inside the drain's batch
                            // transaction, where a note the validator refused would cost rows Notify had accepted.
                            .doesNotContain("400")
                            .doesNotContain(APPLICANT_EMAIL);
                        // The caseworker who sent it, read off the row rather than the drain's service account, so
                        // the send and its outcome read under one name.
                        assertThat(note.getCreatedBy()).isEqualTo("A Caseworker");
                    });
            }

            @Test
            @DisplayName("writes no failure note while the send is still being retried")
            void writesNoFailureNoteWhileTheSendIsStillOwed() throws Exception {
                // A 500 leaves the row Pending and backed off, so the email may well go out on the next drain. A
                // note now would say it failed, and notes are never revised.
                NOTIFY.stubSendStatus(500);
                String caseId = refusedCase();

                authorised(post(sendOf(caseId)), "{}").andExpect(status().isOk());

                assertThat(notifications.findAll()).singleElement().satisfies(row ->
                    assertThat(row.getState()).isEqualTo(NotificationState.Pending));
                assertThat(emailNotes(caseId)).singleElement().satisfies(note ->
                    assertThat(note.getNote()).isEqualTo(EmailNotes.sent(EmailTemplate.APPLICATION_REJECTED)));
            }

            @Test
            @DisplayName("neither the note nor the outbox row survives the other's rollback")
            void theNoteCommitsWithTheRow() {
                // Called on the service inside a transaction this test rolls back, because over HTTP the
                // transaction always commits -- and decision 4's claim is that there is no email owed without its
                // note and no note for a send that was rolled back.
                String caseId = confirmationCase();

                new TransactionTemplate(transactions).execute(status -> {
                    emails.send(caseId, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), UUID.randomUUID(),
                                caseworker());
                    status.setRollbackOnly();
                    return null;
                });

                assertThat(notifications.count()).isZero();
                assertThat(emailNotes(caseId)).isEmpty();
            }
        }
    }

    /**
     * The case-driven chase, end to end: what the tab is told, what Notify renders, what is sent, and what history
     * then says about it.
     *
     * <p><strong>The whole journey in one place, because every step of it is new.</strong> ⑤ is the first template
     * whose entitlement is the case's own derived state, the first whose rows carry no {@code referralId}, and the
     * first a caseworker may legitimately send twice -- so the thing worth proving is not any one of those in
     * isolation but that the four operations agree about the same case as it moves. The rules themselves are pinned
     * container-free in {@code CaseEmailServiceTest} and {@code CaseworkerEmailIdempotencyHeaderTest}; what only a
     * booted service can show is that the route, Postgres, the drain and the Notify client are wired to them.
     */
    @Nested
    @DisplayName("the chase-missing-documents journey")
    class ChaseMissingDocuments {

        /** What the caseworker types into the one slot. */
        private static final String REQUIRED =
            "Your birth certificate, and the consent of everyone with parental responsibility.";

        @Test
        @DisplayName("availability, preview, send, drain and history, for one case waiting for documents")
        void theWholeJourney() throws Exception {
            String caseId = chasedCase();

            // 1. Availability. Applicable and provisioned, with its one required slot and its cap -- which is what
            // a generic Email tab renders the form from, without knowing this template exists.
            authorised(get("/caseworker/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].template").value(CHASE))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].applicable").value(true))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].reason").doesNotExist())
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].provisioned").value(true))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].slots.length()").value(1))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].slots[0].name")
                               .value(EmailSlot.DOCUMENTS_REQUIRED.placeholder()))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].slots[0].required").value(true))
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].slots[0].maxLength")
                               .value(EmailSlot.MAX_EMAIL_PROSE_LENGTH))
                // The form opens empty, because nothing pre-populates this slot. A tab that asked for defaults
                // through the validator would be refused on exactly the cases this email is for.
                .andExpect(jsonPath("$.slotDefaults[0].template").value(CHASE))
                .andExpect(jsonPath("$.slotDefaults[0].fields.documents_required").value(""))
                .andExpect(jsonPath("$.history").isEmpty());

            // 2. Preview. Notify renders it, from the resolved case values and the caseworker's words -- and
            // nothing a judge wrote, because there is no referral in this journey at all.
            NOTIFY.stubPreview("We need more information about your application",
                               "<p>Please send your papers.</p>", StubNotify.TEMPLATE_VERSION);
            authorised(post(chasePreviewOf(caseId)), slots(REQUIRED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("We need more information about your application"))
                .andExpect(jsonPath("$.html").value("<p>Please send your papers.</p>"))
                .andExpect(jsonPath("$.templateVersion").value(StubNotify.TEMPLATE_VERSION));
            Map<String, Object> previewed = previewedPersonalisation();
            assertThat(previewed)
                .containsEntry(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED)
                .containsEntry(MissingDocumentsPersonalisation.OLD_FORENAME, "Alex")
                .containsEntry(MissingDocumentsPersonalisation.OLD_SURNAME, "Okonjo")
                .containsEntry(MissingDocumentsPersonalisation.ONLINE_APPLICATION_REFERENCE, "DP-2026-0042")
                .containsKey(MissingDocumentsPersonalisation.REFERENCE_NUMBER)
                .hasSize(EmailTemplate.CHASE_MISSING_DOCUMENTS.personalisationKeys().size());
            assertThat(notifications.count()).as("a preview owes nothing").isZero();

            // 3. Send, and 4. the drain that follows it in the same request.
            NOTIFY.stubSend(StubNotify.NOTIFICATION_ID, "We need more information", "Please send your papers.");
            UUID journey = UUID.randomUUID();
            String sent = JSON.readTree(authorised(post(chaseSendOf(caseId)), slots(REQUIRED), journey)
                                            .andExpect(status().isOk())
                                            .andExpect(jsonPath("$.recipient").value(APPLICANT_EMAIL))
                                            .andExpect(jsonPath("$.enqueued").value(true))
                                            .andReturn()
                                            .getResponse()
                                            .getContentAsString())
                .get("id").asText();

            assertThat(notifications.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getState()).isEqualTo(NotificationState.Sent);
                assertThat(row.getTemplate()).isEqualTo(EmailTemplate.CHASE_MISSING_DOCUMENTS.name());
                // The null V11 made the column nullable for, read back off real Postgres: a chase belongs to the
                // case and to no decision, so nothing about it sits in the referral exclusion index.
                assertThat(row.getReferralId()).isNull();
                assertThat(row.getExclusionGroup()).isEqualTo(EmailExclusionGroup.DOCUMENT_CHASE.name());
                assertThat(row.getRequestId()).isEqualTo(journey);
                assertThat(row.getRecipient()).isEqualTo(APPLICANT_EMAIL);
                assertThat(row.getPersonalisation())
                    .containsEntry(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED);
                assertThat(row.getSubject()).isEqualTo("We need more information");
                assertThat(row.getBody()).isEqualTo("Please send your papers.");
                assertThat(row.getCreatedBy()).isEqualTo("A Caseworker");
            });

            // The reply-to, on the wire. Its words ask the applicant to reply, so a send from Notify's no-reply
            // sender would lose the documents this email exists to collect -- and a blank one fails silently,
            // which is why it is asserted as the value the stub's configuration carries.
            assertThat(NOTIFY.emailRequests()).singleElement().satisfies(request ->
                assertThat(JSON.readTree(request.getBodyAsString()).path("email_reply_to_id").asText())
                    .isEqualTo(REPLY_TO_ID));

            // 5. History. One attempt, with the wording the applicant received and no request id anywhere in it.
            String history = authorised(get("/caseworker/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].id").value(sent))
                .andExpect(jsonPath("$.history[0].template").value(CHASE))
                .andExpect(jsonPath("$.history[0].templateName")
                               .value(EmailTemplate.CHASE_MISSING_DOCUMENTS.displayName()))
                .andExpect(jsonPath("$.history[0].createdBy").value("A Caseworker"))
                .andExpect(jsonPath("$.history[0].recipient").value(APPLICANT_EMAIL))
                .andExpect(jsonPath("$.history[0].notifyState").value("Sent"))
                .andExpect(jsonPath("$.history[0].subject").value("We need more information"))
                .andExpect(jsonPath("$.history[0].body").value("Please send your papers."))
                .andExpect(jsonPath("$.history[0].failureReason").doesNotExist())
                // Nothing is attached: this email asks for incoming material and sends none.
                .andExpect(jsonPath("$.history[0].documents").isEmpty())
                // And the email is still offered, because the case is still waiting: chasing again is legitimate.
                .andExpect(jsonPath("$.availability[" + CHASE_INDEX + "].applicable").value(true))
                .andReturn()
                .getResponse()
                .getContentAsString();
            assertThat(history)
                .as("the request id is transport and belongs in no correspondence")
                .doesNotContain(journey.toString());

            // One note on the case's only timeline, naming the email and nothing the caseworker typed.
            assertThat(emailNotes(caseId)).singleElement().satisfies(note -> {
                assertThat(note.getNote()).isEqualTo(EmailNotes.sent(EmailTemplate.CHASE_MISSING_DOCUMENTS));
                assertThat(note.getNote()).doesNotContain("birth certificate");
            });
        }

        @Test
        @DisplayName("a second chase is a second email, and history keeps both newest first")
        void chasingAgainKeepsBothAttempts() throws Exception {
            // Decision 5 over HTTP and real Postgres: the exclusion group must not make one chase per case, and the
            // second attempt must not overwrite the first -- the first is the only record that the applicant was
            // already asked, and a caseworker deciding whether to chase again is reading exactly that.
            String caseId = chasedCase();
            NOTIFY.stubSend(StubNotify.NOTIFICATION_ID, "We need more information", "Please send your papers.");
            String first = sentId(authorised(post(chaseSendOf(caseId)), slots(REQUIRED), UUID.randomUUID()));

            NOTIFY.stubSend(StubNotify.NOTIFICATION_ID, "A reminder about your application", "A second ask.");
            String second = sentId(authorised(
                post(chaseSendOf(caseId)),
                slots("Your birth certificate. We wrote to you on 10 September and have had no reply."),
                UUID.randomUUID()));

            assertThat(second).isNotEqualTo(first);
            assertThat(notifications.count()).isEqualTo(2);
            assertThat(NOTIFY.emailRequests()).hasSize(2);

            authorised(get("/caseworker/cases/" + caseId + "/emails"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(2))
                // Newest first, each with its own wording: a history that showed the latest rendering against both
                // rows would say the applicant had been asked the same thing twice.
                .andExpect(jsonPath("$.history[0].id").value(second))
                .andExpect(jsonPath("$.history[0].subject").value("A reminder about your application"))
                .andExpect(jsonPath("$.history[0].body").value("A second ask."))
                .andExpect(jsonPath("$.history[1].id").value(first))
                .andExpect(jsonPath("$.history[1].subject").value("We need more information"))
                .andExpect(jsonPath("$.history[1].body").value("Please send your papers."));

            // Two emails, two markers on the Notes grid.
            assertThat(emailNotes(caseId)).hasSize(2);
        }

        @Test
        @DisplayName("one compose journey chases once, however many times its POST arrives")
        void oneJourneyChasesOnce() throws Exception {
            // The case V15's index cannot help with at all: a chase's rows carry no referralId, so without V19 a
            // double-click here would be a second email to the applicant. Over HTTP because the key is a header.
            String caseId = chasedCase();
            UUID journey = UUID.randomUUID();
            String first = sentId(authorised(post(chaseSendOf(caseId)), slots(REQUIRED), journey));

            authorised(post(chaseSendOf(caseId)), slots(REQUIRED), journey)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.enqueued").value(false));

            assertThat(notifications.count()).isOne();
            assertThat(NOTIFY.emailRequests()).hasSize(1);
            assertThat(emailNotes(caseId)).hasSize(1);
        }

        @Test
        @DisplayName("409s a case that is not waiting for documents, and 400s a chase with nothing in the slot")
        void refusesWhatItCannotSend() throws Exception {
            // The two refusals a generic tab has to render, and they are different things: the first is about the
            // case and would succeed unchanged once it is waiting, the second is about the form.
            authorised(post(chaseSendOf(refusedCase())), slots(REQUIRED))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("awaited")))
                // Not the refusal email's sentence: a caseworker sent looking for a Master's decision would be
                // looking at the wrong tab for a field that has no referral behind it.
                .andExpect(jsonPath("$.error").value(not(containsString("Master"))));

            authorised(post(chaseSendOf(chasedCase())), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field")
                               .value(EmailSlot.DOCUMENTS_REQUIRED.placeholder()));

            assertThat(notifications.count()).isZero();
            assertThat(NOTIFY.emailRequests()).isEmpty();
        }

        /** The row id out of a send's answer. */
        private String sentId(ResultActions sent) throws Exception {
            return JSON.readTree(sent.andExpect(status().isOk())
                                     .andExpect(jsonPath("$.enqueued").value(true))
                                     .andReturn()
                                     .getResponse()
                                     .getContentAsString())
                .get("id").asText();
        }

        private String slots(String documentsRequired) throws Exception {
            return JSON.writeValueAsString(
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), documentsRequired));
        }
    }

    /**
     * The notes on a case that an email wrote, which are the notes whose text {@link EmailNotes} composes.
     *
     * <p>Filtered rather than counted, because the fixtures raise, claim and decide a referral and each of those
     * writes a note of its own -- so "the notes on this case" is never only the email's. Matched against the exact
     * strings the composer produces for a known template rather than by looking for words in prose: the note is
     * deliberately not a wire format, and a test that parsed it would be the thing decision 3 refuses to make
     * {@code deed-poll-admin} do.
     */
    private List<DeedPollNote> emailNotes(String caseId) {
        Set<String> written = Arrays.stream(EmailTemplate.values())
            .flatMap(template -> Stream.of(EmailNotes.sent(template), EmailNotes.couldNotBeSent(template)))
            .collect(Collectors.toSet());
        return notes.findByDeedPollIdOrderByCreatedOnDesc(caseId).stream()
            .filter(note -> written.contains(note.getNote()))
            .toList();
    }

    /** The personalisation Notify was asked to render, read off the stub's recorded request. */
    private Map<String, Object> previewedPersonalisation() throws Exception {
        assertThat(NOTIFY.previewRequests()).hasSize(1);
        String body = NOTIFY.previewRequests().getFirst().getBodyAsString();
        TypeReference<Map<String, Object>> asMap = new TypeReference<>() {
        };
        return JSON.convertValue(JSON.readTree(body).get("personalisation"), asMap);
    }

    private String previewOf(String caseId) {
        return "/caseworker/cases/" + caseId + "/emails/" + REJECTED + "/preview";
    }

    private String sendOf(String caseId) {
        return "/caseworker/cases/" + caseId + "/emails/" + REJECTED;
    }

    private String confirmationSendOf(String caseId) {
        return "/caseworker/cases/" + caseId + "/emails/" + CONFIRMATION;
    }

    private String chaseSendOf(String caseId) {
        return "/caseworker/cases/" + caseId + "/emails/" + CHASE;
    }

    private String chasePreviewOf(String caseId) {
        return chaseSendOf(caseId) + "/preview";
    }

    /**
     * A case the office is waiting on documents for, reached the way a caseworker reaches it.
     *
     * <p>Through the write path rather than by setting the column: {@code state} is derived by the cascade from
     * {@code awaitingFurtherDocsInfo}, and a fixture that wrote {@code AwaitingFurtherDocs} straight onto the row
     * would be asserting about a case this service could not have produced -- and would go on passing if the
     * cascade stopped deriving it.
     */
    private String chasedCase() {
        String caseId = newCase();
        deedPollService.updateDeedPoll(caseId, DeedPollPatch.of(Map.of("awaitingFurtherDocsInfo", RECEIVED)));
        assertThat(deedPolls.findById(caseId).orElseThrow().getState())
            .as("the cascade derives the state this email is offered in")
            .isEqualTo(DeedPollState.AwaitingFurtherDocs);
        return caseId;
    }

    /**
     * A case whose refused referral was raised for a non-publication request, which is the only shape ④ applies to.
     *
     * <p>{@link #refusedCase()} is refused for {@code Other}, so it offers ③ and not ④ -- which is the distinction
     * {@code CaseEmailServiceTest} pins and the reason this needs its own fixture rather than a flag on that one.
     */
    private String confirmationCase() {
        String caseId = newCase();
        decide(caseId, raise(caseId, ReferralReason.NonPublication), MasterDecision.Rejected, DIRECTION);
        return caseId;
    }

    /** The case's one referral, as it stands committed and read in a session of its own. */
    private Referral storedReferral(String caseId) {
        return referrals.findByDeedPollIdOrderByCreatedOnDesc(caseId).getFirst();
    }

    /** The caseworker the fixtures act as, matching the display name the stub token carries. */
    private static CurrentUser caseworker() {
        return CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");
    }

    /**
     * A stored case with everything the refusal email resolves from.
     *
     * <p>{@code postCounter} is {@code Online}, which is the one value that gets the "we will refund the account
     * you paid from" paragraph -- so the personalisation assertions are about a value that had to be read rather
     * than about a default.
     */
    private String newCase() {
        return deedPollService.createDeedPoll(DeedPollPatch.of(Map.of(
                "dateReceived", RECEIVED,
                "applicantEmail", APPLICANT_EMAIL,
                "oldForename", "Alex",
                "oldSurname", "Okonjo",
                "onlineApplicationReference", "DP-2026-0042",
                "postCounter", "Online")))
            .deedPoll()
            .getId();
    }

    /**
     * A case a Master has refused: referred, claimed and directed {@code Rejected} with a direction recorded.
     *
     * <p>Through the real transitions rather than by writing {@code Rejected} into the row, so the fixture is a
     * case this service could really have produced and {@code judges_reason} has something real to be
     * pre-populated from. Not through HTTP, because the two judicial transitions have no route on this surface --
     * which is why the fixture supplies {@code Role.JUDICIAL} directly.
     */
    private String refusedCase() {
        String caseId = newCase();
        decide(caseId, raise(caseId), MasterDecision.Rejected, DIRECTION);
        return caseId;
    }

    private String raise(String caseId) {
        return raise(caseId, ReferralReason.Other);
    }

    /** The same referral, raised for one named reason -- which is what decides whether ④ applies to the case. */
    private String raise(String caseId, ReferralReason reason) {
        Referral referral = referralService.createReferral(
            caseId,
            new CreateReferralInput(List.of(reason), Optional.of("Needs a Master"),
                                    Urgency.Normal, Optional.empty()),
            caseworker());
        return referral.getId().toString();
    }

    private void decide(String caseId, String referralId, MasterDecision decision, String directionsText) {
        CurrentUser judge = CurrentUser.currentUser(List.of(Role.JUDICIAL), JUDGE_NAME, "judge-1");
        referralService.claim(caseId, referralId, judge);
        referralService.decide(
            caseId, referralId, new DirectionInput(decision, Optional.ofNullable(directionsText), false), judge);
    }

    /** The request, carrying a valid forwarded access token. */
    private ResultActions authorised(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.headers(tokenAndANewJourney()));
    }

    /** The request, carrying a valid forwarded access token and a JSON body. */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mockMvc.perform(
            request.headers(tokenAndANewJourney()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * The same request as a named compose journey, for the tests about sending the same one twice.
     *
     * <p>The distinction the default helper cannot express, and it is decision 6's whole point: two POSTs of one
     * journey are a double-click or a retry and must write one row, while two journeys are two deliberate sends.
     * So a test that repeats a send says which it means by passing the key or not.
     */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body, UUID requestId)
        throws Exception {
        HttpHeaders headers = tokenAndANewJourney();
        headers.set(CaseworkerEmailController.IDEMPOTENCY_KEY, requestId.toString());
        return mockMvc.perform(
            request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * A valid forwarded access token, and a fresh {@code Idempotency-Key}.
     *
     * <p>The key is on every request rather than only on the sends: it is required by one route and ignored by
     * the others, and putting it here means a test that adds a send does not have to remember it. Fresh per
     * request, so the default is "a new compose journey" -- which is what an unrelated test means when it sends.
     */
    private static HttpHeaders tokenAndANewJourney() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        headers.set(CaseworkerEmailController.IDEMPOTENCY_KEY, UUID.randomUUID().toString());
        return headers;
    }
}
