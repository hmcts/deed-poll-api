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
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.notify.ApplicationRejectedPersonalisation;
import uk.gov.hmcts.reform.deedpoll.notify.CaseEmailService;
import uk.gov.hmcts.reform.deedpoll.notify.EmailSlot;
import uk.gov.hmcts.reform.deedpoll.notify.EmailTemplate;
import uk.gov.hmcts.reform.deedpoll.notify.StubNotify;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollNoteRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
 * each answer becomes, that a preview writes nothing and that a send writes exactly one row.
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
                .andExpect(jsonPath("$.availability[0].slots.length()").value(EmailSlot.values().length))
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

            // A caseworker double-clicking, or two replicas racing, must not tell an applicant twice that their
            // application was refused -- and must not be shown a failure for a request that was merely redundant.
            authorised(post(sendOf(caseId)), "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.enqueued").value(false));

            assertThat(notifications.count()).isOne();
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
                    emails.send(caseId, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), caseworker());
                    status.setRollbackOnly();
                    return null;
                });

                assertThat(notifications.count()).isZero();
                assertThat(storedReferral(caseId).getConfirmationRequestedAt()).isNull();
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
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers));
    }

    /** The request, carrying a valid forwarded access token and a JSON body. */
    private ResultActions authorised(MockHttpServletRequestBuilder request, String body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(IDAM.accessToken());
        return mockMvc.perform(request.headers(headers).contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
