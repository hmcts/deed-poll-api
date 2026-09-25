package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.IncompleteDataError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.TemplatePreview;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The rules the three email operations enforce, stated without a container.
 *
 * <p><strong>Container-free on purpose, and this is where the load-bearing rules live.</strong> CI excludes the
 * {@code requires-docker} classes, so {@code CaseworkerEmailControllerTest} -- which is the same rules over real
 * HTTP against real Postgres -- does not run there. A rule stated only in that class is a rule CI does not check,
 * and three of these are the kind that must not regress unnoticed: a blank recipient refused rather than skipped,
 * a refusal letter sent only where a Master actually refused, and a Notify failure that does not become a 500.
 *
 * <p>The repositories and the Notify client are mocked, which is the point rather than a compromise: what is
 * under test is which answer this service gives for which state, and the statements themselves are asserted
 * against real Postgres in {@code NotificationRepositoryTest}.
 */
class CaseEmailServiceTest {

    private static final String CASE_ID = "case-1";

    private static final String RECIPIENT = "applicant@example.com";

    private static final String DIRECTION = "Refused: no settled intention shown.";

    private static final UUID REFERRAL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * One compose journey's {@code Idempotency-Key}, shared by every test here that sends once.
     *
     * <p>A constant rather than a fresh UUID per call, because a test that needed two <em>different</em> journeys
     * would then be indistinguishable from one that did not care -- and the difference between one journey and
     * two is the whole of decision 6. The tests about repeats say which they mean.
     */
    private static final UUID REQUEST_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NotificationRepository notifications;
    private DeedPollSupportingDocumentRepository documents;
    private NotificationClient notify;

    /**
     * Mocked because what this class asserts about it is that the send asks it to bring the case's waiting-list
     * column into step -- the recompute itself has no database here to run against, and
     * {@code DeedPollRepositoryTest} is where the rule meets a real one.
     */
    private ReferralService referralService;

    /**
     * Mocked so that "a send writes exactly one note, and a refused send writes none" is assertable without a
     * database -- the note's own text is pinned in {@code EmailNotesTest} and the row it becomes in
     * {@code CaseworkerEmailControllerTest}.
     */
    private NoteService caseNotes;

    private CaseEmailService emails;

    @BeforeEach
    void setUp() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notifications = mock(NotificationRepository.class);
        documents = mock(DeedPollSupportingDocumentRepository.class);
        notify = mock(NotificationClient.class);
        caseNotes = mock(NoteService.class);
        // A provisioned template, so nothing here turns on the placeholder. The unprovisioned case is a Notify
        // refusal, which is asserted in its own test below.
        NotifyProperties properties = new NotifyProperties(
            "https://notify.invalid", "key", Map.of(EmailTemplate.APPLICATION_REJECTED.key(), "template-1"), "");
        referralService = mock(ReferralService.class);
        // The clock starting, which is what the statement answers on an unanswered referral. Stubbed here rather
        // than per test so that the one test about the other answer -- 0, the applicant answered mid-send -- is the
        // only one saying so.
        when(referrals.startConfirmationClockByIdAndDeedPollId(any(), anyString(), any(), anyString())).thenReturn(1);
        emails = new CaseEmailService(
            deedPolls, referrals, notifications, documents, notify, properties, referralService, caseNotes,
            new ObjectMapper());
    }

    @Nested
    @DisplayName("which emails apply")
    class Availability {

        @Test
        @DisplayName("reports every template, with the reason on the ones that do not apply")
        void reportsEveryTemplate() {
            storedCase();
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            List<EmailAvailability> available = emails.available(CASE_ID);

            // Every template, not only the applicable ones: a shorter list would leave a caseworker asking
            // somebody why the refusal letter is missing.
            assertThat(available).hasSize(EmailTemplate.values().length);
            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED)).satisfies(entry -> {
                assertThat(entry.applicable()).isFalse();
                assertThat(entry.reason()).isEqualTo(CaseEmailService.NO_REFUSAL);
                assertThat(entry.provisioned()).isTrue();
            });
            // Its own sentence, in its own words: this case is waiting on a judge, which is a different thing
            // to fix from a case whose refusal was about something other than publication.
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).satisfies(entry -> {
                assertThat(entry.applicable()).isFalse();
                assertThat(entry.reason()).isEqualTo(CaseEmailService.NO_REFUSAL_TO_CONFIRM);
            });
            // And the case-driven one, whose sentence is about the case rather than about a referral: the fixture
            // is a case in no state at all, so there is nothing recorded as awaited.
            assertThat(entryFor(available, EmailTemplate.CHASE_MISSING_DOCUMENTS)).satisfies(entry -> {
                assertThat(entry.applicable()).isFalse();
                assertThat(entry.reason()).isEqualTo(CaseEmailService.NOT_AWAITING_FURTHER_DOCS);
            });
        }

        @Test
        @DisplayName("applies once a referral has been refused, and carries no reason then")
        void appliesAfterARefusal() {
            storedCase();
            refusedReferral();

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.APPLICATION_REJECTED))
                .satisfies(entry -> {
                    assertThat(entry.applicable()).isTrue();
                    assertThat(entry.reason()).isNull();
                });
        }

        @Test
        @DisplayName("a refusal on other reasons does not offer the confirmation email")
        void refusalOnOtherReasonsIsNotAboutNonPublication() {
            // The rejection email applies to any refusal; the confirmation email only to one about a
            // non-publication request, because that is the only refusal it can coherently ask about.
            storedCase();
            refusedReferralFor(ReferralReason.DeclarantRequirementsNotMet);

            List<EmailAvailability> available = emails.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED).applicable()).isTrue();
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED))
                .satisfies(entry -> {
                    assertThat(entry.applicable()).isFalse();
                    // The second sentence, not the first: something was refused here, so a caseworker sent to
                    // look for a decision would find one and be none the wiser.
                    assertThat(entry.reason())
                        .isEqualTo(CaseEmailService.REFUSAL_NOT_ABOUT_NON_PUBLICATION)
                        .isNotEqualTo(CaseEmailService.NO_REFUSAL_TO_CONFIRM);
                });
        }

        @Test
        @DisplayName("a refused non-publication referral offers both emails, because the caseworker chooses")
        void nonPublicationRefusalOffersBoth() {
            // Decision 1: MasterDecision is only Approved | Rejected, and a rejection means opposite things for
            // the two templates. A multi-reason referral gets one decision and one direction covering all of
            // them, which cannot be decomposed -- so both are offered and the caseworker picks.
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication, ReferralReason.DeclarantRequirementsNotMet);

            List<EmailAvailability> available = emails.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED).applicable()).isTrue();
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED).applicable()).isTrue();
            // And the chase is untouched by any of it: it is case-driven, so a refusal neither offers nor
            // withholds it. This case is not waiting for documents, and that is the only question asked of it.
            assertThat(entryFor(available, EmailTemplate.CHASE_MISSING_DOCUMENTS).applicable()).isFalse();
        }

        @Test
        @DisplayName("stops offering the confirmation email once the applicant has answered it")
        void answeredConfirmationIsNotOfferedAgain() {
            // The failure this refusal exists to prevent, and it is reachable rather than theoretical: the V15
            // index stops a second copy only while the first outbox row occupies the exclusion group, and a row
            // that failed PERMANENTly does not -- which is the state of every one of these emails while the
            // template is unprovisioned. Asked again, the send would re-stamp confirmationRequestedAt while the
            // recompute behind awaitingConfirmationSince skips a referral that has an outcome, so the case would
            // be waiting for a reply and absent from the list of cases waiting for one, and recordConfirmation
            // would refuse the new answer for ever.
            storedCase();
            Referral answered = refusedReferralFor(ReferralReason.NonPublication);
            answered.setConfirmationRequestedAt(LocalDateTime.parse("2026-05-03T09:15:00"));
            answered.setConfirmationOutcome(ConfirmationOutcome.Confirmed);

            List<EmailAvailability> available = emails.available(CASE_ID);

            // The rejection email is untouched: it asks the applicant for nothing, so an answer they have already
            // given says nothing about whether they may still be told a decision.
            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED).applicable()).isTrue();
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED))
                .satisfies(entry -> {
                    assertThat(entry.applicable()).isFalse();
                    // The third sentence, and not either of the first two: something was refused and it was about
                    // publication, so a caseworker told otherwise would go looking for the wrong thing.
                    assertThat(entry.reason())
                        .isEqualTo(CaseEmailService.CONFIRMATION_ALREADY_ANSWERED)
                        .isNotEqualTo(CaseEmailService.REFUSAL_NOT_ABOUT_NON_PUBLICATION)
                        .isNotEqualTo(CaseEmailService.NO_REFUSAL_TO_CONFIRM);
                    // What they said is not repeated here: the referrals tab and the timeline both name it, and
                    // an availability response is answering a different question.
                    assertThat(entry.reason()).doesNotContain(ConfirmationOutcome.Confirmed.name());
                });
        }

        @Test
        @DisplayName("still offers the confirmation email while the applicant has been asked but not answered")
        void askedButUnansweredIsStillOffered() {
            // The boundary: it is the outcome that closes the exchange, not the asking. A request date alone is a
            // case still waiting, and a caseworker whose first send failed terminally has to be able to ask again.
            storedCase();
            Referral asked = refusedReferralFor(ReferralReason.NonPublication);
            asked.setConfirmationRequestedAt(LocalDateTime.parse("2026-05-03T09:15:00"));

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)
                           .applicable()).isTrue();
        }

        @Test
        @DisplayName("does not apply to an approved referral, so the predicate is the decision and not its presence")
        void doesNotApplyToAnApproval() {
            // The distinction that matters most in this file: a predicate on "has a decision" would send a refusal
            // letter to an applicant whose application was approved.
            storedCase();
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID))
                .thenReturn(List.of(referral(MasterDecision.Approved, "Approved. Publish in the Gazette.")));

            assertThat(emails.available(CASE_ID))
                .allSatisfy(entry -> assertThat(entry.applicable()).as("%s", entry.template()).isFalse());
        }

        @Test
        @DisplayName("reads the newest refusal and then asks what it was about, not the newest non-publication one")
        void readsTheNewestRefusalRatherThanTheNewestNonPublicationOne() {
            // Correction 1, and the failure it prevents: searching for the newest refused non-publication
            // referral would revive an outcome the rejection email already treats as superseded, and would let
            // the two emails speak for different referrals -- which is what makes them mutually exclusive.
            storedCase();
            Referral superseded = referral(MasterDecision.Rejected, DIRECTION);
            superseded.setReasons(List.of(ReferralReason.NonPublication.name()));
            Referral newest = referral(MasterDecision.Rejected, "Refused: the declarant is not eligible.");
            newest.setReasons(List.of(ReferralReason.DeclarantRequirementsNotMet.name()));
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID))
                .thenReturn(List.of(newest, superseded));

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)
                           .applicable()).isFalse();
        }

        @Test
        @DisplayName("a referral with no reasons recorded is not a non-publication refusal")
        void referralWithNoReasonsIsNotANonPublicationRefusal() {
            // Every migrated referral is in this state -- the power app recorded no reasons -- so this is the
            // commonest shape in the data rather than an edge case.
            storedCase();
            refusedReferralFor();

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)
                           .applicable()).isFalse();
        }

        @Test
        @DisplayName("the reason is matched as an enum member name, never as free text in the notes")
        void theReasonIsMatchedAsAMemberName() {
            storedCase();
            Referral referral = referral(MasterDecision.Rejected, DIRECTION);
            referral.setReasons(List.of("Non publication"));
            referral.setNotes("The applicant asks for non publication in the Gazette.");
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(referral));

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)
                           .applicable()).isFalse();
        }

        @Test
        @DisplayName("hides the confirmation email once the rejection has been asked for on that referral")
        void hidesTheCounterpartOnceTheRejectionIsRequested() {
            // Decision 3, reported rather than left to the index: the two emails say opposite things about
            // enrolment, so the tab greys the second one out instead of letting a caseworker compose an email
            // that will be refused on submit.
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);
            blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            List<EmailAvailability> available = emails.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED))
                .satisfies(entry -> {
                    assertThat(entry.applicable()).isFalse();
                    // Named and dated, because "you cannot send this" without saying what was sent instead
                    // leaves the caseworker to go and read the history themselves.
                    assertThat(entry.reason())
                        .contains(EmailTemplate.APPLICATION_REJECTED.displayName())
                        .contains("3 June 2026")
                        .contains("already requested");
                });
            // Its own row does not hide it: a second send of the same email is the idempotent no-op the send
            // path answers with the original row, which is not the same thing as a contradiction.
            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED).applicable()).isTrue();
        }

        @Test
        @DisplayName("hides the rejection email once the confirmation has been asked for, which is the same rule")
        void hidesTheCounterpartInTheOtherDirection() {
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);
            blocking(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, LocalDateTime.of(2026, 6, 4, 16, 0));

            List<EmailAvailability> available = emails.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED)).satisfies(entry -> {
                assertThat(entry.applicable()).isFalse();
                assertThat(entry.reason())
                    .contains(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.displayName())
                    .contains("4 June 2026");
            });
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED).applicable()).isTrue();
        }

        @Test
        @DisplayName("asks the question the index answers -- by exclusion group, for the referral it named")
        void asksTheIndexsOwnQuestion() {
            // The service does not keep its own definition of "already sent": it asks the one statement that
            // repeats the index's predicate, keyed on the group rather than on the template. So a permanently
            // refused row -- which does not occupy the index, and which notify-1 deliberately allows a
            // replacement for -- hides nothing, and that answer comes from Postgres rather than from here.
            // NotificationRepositoryTest proves which rows the predicate returns.
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);

            List<EmailAvailability> available = emails.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED).applicable()).isTrue();
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED).applicable()).isTrue();

            // Once per referral-driven template, both asking about the same group, which is the whole mechanism:
            // two questions with one answer rather than two rules that could disagree.
            verify(notifications, times(2)).findBlocking(
                CASE_ID, REFERRAL_ID, EmailExclusionGroup.REFERRAL_OUTCOME.name());
            // And exactly twice in total: the chase carries no referral, so there is no row the partial index
            // could hold against it and no question to ask. A third call with a null referral would be a query
            // whose answer is always empty, dressed up as a rule.
            verify(notifications, times(2)).findBlocking(anyString(), any(), anyString());
            verify(notifications, never()).findByDeedPollIdOrderByCreatedOnDesc(CASE_ID);
        }

        @Test
        @DisplayName("says a template is unprovisioned, so a tab can say so rather than offering a broken button")
        void reportsProvisioning() {
            // With no configured template ID -- which is every environment until notify-0 lands -- a send fails on
            // the drain and a preview is a 502. NotifyProperties.isProvisioned exists for this sentence.
            CaseEmailService unprovisioned = new CaseEmailService(
                deedPolls, referrals, notifications, documents, notify,
                new NotifyProperties("https://notify.invalid", "", Map.of(), ""), referralService, caseNotes,
                new ObjectMapper());
            storedCase();
            refusedReferral();

            assertThat(entryFor(unprovisioned.available(CASE_ID), EmailTemplate.APPLICATION_REJECTED))
                .satisfies(entry -> {
                    assertThat(entry.applicable()).isTrue();
                    assertThat(entry.provisioned()).isFalse();
                });
        }

        @Test
        @DisplayName("says only the email that invites a reply is unprovisioned when there is no reply-to")
        void reportsProvisioningPerTemplate() {
            // Decisions 9 and 10 as the Email tab sees them: both templates have a real UUID and neither has an
            // inbox to reply to, so the email whose words ask for a reply cannot be sent and the one that asks for
            // nothing can. One flag for the service would have disabled both -- and an applicant would then be told
            // their application was refused by nothing at all, over a setting that email never needed.
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            CaseEmailService noReplyTo = new CaseEmailService(
                deedPolls, referrals, notifications, documents, notify,
                new NotifyProperties("https://notify.invalid", "key", Map.of(
                    EmailTemplate.APPLICATION_REJECTED.key(), uuid,
                    EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key(), uuid), ""),
                referralService,
                caseNotes,
                new ObjectMapper());
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);

            List<EmailAvailability> available = noReplyTo.available(CASE_ID);

            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED)).satisfies(entry -> {
                // Applicable and unprovisioned: the case is in the right state and the environment is not ready,
                // which are separate axes and neither implies the other.
                assertThat(entry.applicable()).isTrue();
                assertThat(entry.provisioned()).isFalse();
            });
            assertThat(entryFor(available, EmailTemplate.APPLICATION_REJECTED)).satisfies(entry -> {
                assertThat(entry.applicable()).isTrue();
                assertThat(entry.provisioned()).isTrue();
            });
        }

        @Nested
        @DisplayName("case correspondence")
        class Correspondence {

            @Test
            @DisplayName("returns sent, pending and failed attempts newest first with content only where captured")
            void returnsEveryAttemptNewestFirst() {
                storedCase();
                Notification sent = notification(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    LocalDateTime.of(2026, 9, 20, 10, 0),
                    NotificationState.Sent);
                sent.setSubject("Your application has been rejected");
                sent.setBody("Dear Alex,\n\nYour application was refused.");
                Notification pending = notification(
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    LocalDateTime.of(2026, 9, 20, 9, 0),
                    NotificationState.Pending);
                Notification failed = notification(
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    LocalDateTime.of(2026, 9, 20, 8, 0),
                    NotificationState.Failed);
                failed.setFailureVerdict(NotificationFailureVerdict.PERMANENT);
                failed.setFailureReason("PERMANENT: GOV.UK Notify refused the send with status 400");
                when(notifications.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID))
                    .thenReturn(List.of(sent, pending, failed));
                when(documents.findByDeedPollIdOrderByCreatedOnAsc(CASE_ID)).thenReturn(List.of());

                List<CaseCorrespondence> history = emails.correspondence(CASE_ID);

                assertThat(history).extracting(CaseCorrespondence::id)
                    .containsExactly(sent.getId(), pending.getId(), failed.getId());
                assertThat(history.getFirst()).satisfies(entry -> {
                    assertThat(entry.template()).isEqualTo("application-rejected");
                    assertThat(entry.templateName()).isEqualTo("Application rejected");
                    assertThat(entry.createdOn()).isEqualTo(sent.getCreatedOn());
                    assertThat(entry.createdBy()).isEqualTo("A Caseworker");
                    assertThat(entry.recipient()).isEqualTo(RECIPIENT);
                    assertThat(entry.state()).isEqualTo(NotificationState.Sent);
                    assertThat(entry.failureReason()).isNull();
                    assertThat(entry.subject()).isEqualTo("Your application has been rejected");
                    assertThat(entry.body()).isEqualTo("Dear Alex,\n\nYour application was refused.");
                    assertThat(entry.documents()).isEmpty();
                });
                assertThat(history.get(1)).satisfies(entry -> {
                    assertThat(entry.state()).isEqualTo(NotificationState.Pending);
                    assertThat(entry.subject()).isNull();
                    assertThat(entry.body()).isNull();
                });
                assertThat(history.get(2)).satisfies(entry -> {
                    assertThat(entry.state()).isEqualTo(NotificationState.Failed);
                    assertThat(entry.failureReason()).startsWith("PERMANENT:");
                    assertThat(entry.subject()).isNull();
                    assertThat(entry.body()).isNull();
                });
                verify(notifications).findByDeedPollIdOrderByCreatedOnDesc(CASE_ID);
                verifyNoInteractions(notify);
            }

            @Nested
            @DisplayName("email tab overview")
            class Overview {

                @Test
                @DisplayName("returns availability, history and pre-populated defaults without calling Notify")
                void returnsEverythingWithoutNotify() {
                    storedCase();
                    refusedReferral();
                    Notification sent = notification(
                        UUID.randomUUID(), LocalDateTime.of(2026, 9, 20, 10, 0), NotificationState.Sent);
                    when(notifications.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(sent));
                    when(documents.findByDeedPollIdOrderByCreatedOnAsc(CASE_ID)).thenReturn(List.of());

                    CaseEmailOverview overview = emails.overview(CASE_ID);

                    assertThat(entryFor(overview.availability(), EmailTemplate.APPLICATION_REJECTED)
                                   .applicable()).isTrue();
                    assertThat(overview.history()).singleElement()
                        .satisfies(entry -> assertThat(entry.id()).isEqualTo(sent.getId()));
                    assertThat(overview.slotDefaults()).singleElement().satisfies(defaults -> {
                        assertThat(defaults.template()).isEqualTo(EmailTemplate.APPLICATION_REJECTED);
                        assertThat(defaults.fields()).containsExactly(
                            Map.entry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION),
                            Map.entry(EmailSlot.ADDITIONAL_NOTES.placeholder(), ""));
                    });
                    verifyNoInteractions(notify);
                }
            }

            @Test
            @DisplayName("returns an empty list for a case with no correspondence")
            void returnsAnEmptyHistory() {
                storedCase();
                when(notifications.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

                assertThat(emails.correspondence(CASE_ID)).isEmpty();

                verify(documents, never()).findByDeedPollIdOrderByCreatedOnAsc(anyString());
                verifyNoInteractions(notify);
            }

            @Test
            @DisplayName("the history model cannot expose raw personalisation")
            void doesNotExposeRawPersonalisation() {
                assertThat(CaseCorrespondence.class.getRecordComponents())
                    .extracting(component -> component.getName())
                    .doesNotContain("personalisation");
            }

            @Test
            @DisplayName("resolves only declared document references into names and ids")
            void resolvesDeclaredDocuments() {
                Notification notification = notification(
                    UUID.randomUUID(), LocalDateTime.of(2026, 9, 20, 10, 0), NotificationState.Sent);
                notification.setPersonalisation(Map.of(
                    "deed_document", "document-1",
                    "caseworker_text", "document-2"));
                DeedPollSupportingDocument carried = new DeedPollSupportingDocument();
                carried.setId("document-1");
                carried.setName("Enrolled deed poll");
                DeedPollSupportingDocument notCarried = new DeedPollSupportingDocument();
                notCarried.setId("document-2");
                notCarried.setName("Application evidence");

                assertThat(CorrespondenceDocument.from(
                    notification,
                    List.of("deed_document"),
                    Map.of(carried.getId(), carried, notCarried.getId(), notCarried)))
                    .containsExactly(new CorrespondenceDocument("document-1", "Enrolled deed poll"));
            }
        }

        @Test
        @DisplayName("404s a case that does not exist rather than answering an empty list")
        void refusesAMissingCase() {
            when(deedPolls.findById(CASE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> emails.available(CASE_ID))
                .isInstanceOf(NotFoundError.class)
                .hasMessageContaining(CASE_ID);
        }
    }

    @Nested
    @DisplayName("sending")
    class Sending {

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("422s a blank recipient naming the column, and enqueues nothing")
        void refusesABlankRecipient(String applicantEmail) {
            // Decision 7, and the one rule in this feature that is a deliberate difference from pcs-api: pcs sends
            // automatically so skipping is its only option, whereas here a caseworker has chosen to send and
            // silence would leave them believing an applicant had been told something.
            DeedPoll deedPoll = storedCase();
            deedPoll.setApplicantEmail(applicantEmail);
            refusedReferral();

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER))
                .isInstanceOf(IncompleteDataError.class)
                .satisfies(refusal -> assertThat(((IncompleteDataError) refusal).errors())
                    .extracting(FieldError::field)
                    .containsExactly("applicantEmail"));

            neverEnqueues();
        }

        @Test
        @DisplayName("enqueues the resolved email against the case, the referral and the template's member name")
        void enqueuesTheResolvedEmail() {
            storedCase();
            refusedReferral();
            enqueueAnswers(1);

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of("additional_notes", " Ring the office. "),
                REQUEST_ID, CASEWORKER);

            ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(id.capture(), eq(CASE_ID), eq(REQUEST_ID), eq(REFERRAL_ID),
                                          // The member name and never the Notify UUID, which is configuration and
                                          // differs per environment.
                                          eq(EmailTemplate.APPLICATION_REJECTED.name()),
                                          // Bound explicitly rather than left to the column's default, so a later
                                          // template in a group of its own is written as itself.
                                          eq(EmailExclusionGroup.REFERRAL_OUTCOME.name()), eq(RECIPIENT),
                                          personalisation.capture(), any(LocalDateTime.class), eq("A Caseworker"));

            assertThat(receipt.id()).isEqualTo(id.getValue());
            assertThat(receipt.recipient()).isEqualTo(RECIPIENT);
            assertThat(receipt.enqueued()).isTrue();
            // Resolved from the case, the caseworker's slot trimmed, and the judge's words pre-populated.
            assertThat(personalisation.getValue())
                .contains("\"old_surname\":\"Okonjo\"")
                .contains("\"applied_online\":true")
                .contains("Ring the office.")
                .contains(DIRECTION);
        }

        @Test
        @DisplayName("trims the stored recipient, so a pasted address with a space is not sent to as it stands")
        void trimsTheRecipient() {
            storedCase().setApplicantEmail("  " + RECIPIENT + " ");
            refusedReferral();
            enqueueAnswers(1);

            assertThat(emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER)
                           .recipient())
                .isEqualTo(RECIPIENT);
        }

        @Test
        @DisplayName("answers with the existing row when the unique index refused the insert")
        void answersWithTheExistingRow() {
            // A caseworker double-clicking, or two replicas racing. The refusal is the mechanism working, so the
            // request succeeds and names the row that is already owed -- telling them it had failed would invite a
            // third attempt.
            storedCase();
            refusedReferral();
            enqueueAnswers(0);
            Notification existing = new Notification();
            UUID existingId = UUID.fromString("33333333-3333-3333-3333-333333333333");
            existing.setId(existingId);
            existing.setRecipient(RECIPIENT);
            existing.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
            // The blocking row is read by referral and exclusion group, because since V15 that is what the index
            // is keyed on -- and the template is read off the row to tell a second click from the other email.
            when(notifications.findBlocking(
                CASE_ID, REFERRAL_ID, EmailExclusionGroup.REFERRAL_OUTCOME.name()))
                .thenReturn(Optional.of(existing));

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER);

            assertThat(receipt.id()).isEqualTo(existingId);
            assertThat(receipt.enqueued()).isFalse();
        }

        @Test
        @DisplayName("409s the email that contradicts one already asked for, naming it and when")
        void refusesTheCounterpartEmail() {
            // The index refused the insert, and the refusal has to say which of the two things happened: a second
            // click answers 200 with the first row, while the other email of the group is a 409. A
            // DataIntegrityViolationException reaching the caseworker would be the same fact as a 500.
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);
            enqueueAnswers(0);
            blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(EmailTemplate.APPLICATION_REJECTED.displayName())
                .hasMessageContaining("3 June 2026")
                // Requested and recorded, not delivered: the blocking row may be Pending, Sent, or a Failed row
                // whose outcome nobody knows.
                .hasMessageContaining("already requested")
                .hasMessageNotContaining("delivered");
        }

        @Test
        @DisplayName("409s a case no Master has refused, and never reaches the payload")
        void refusesACaseWithNoRefusal() {
            storedCase();
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CaseEmailService.NO_REFUSAL);
        }

        @Test
        @DisplayName("400s a request that tries to set a resolved value, and enqueues nothing")
        void refusesAResolvedValue() {
            storedCase();
            refusedReferral();

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED,
                Map.of(ApplicationRejectedPersonalisation.REFERENCE_NUMBER, "2026/999999"), REQUEST_ID, CASEWORKER))
                .isInstanceOf(InvalidInputError.class);

            neverEnqueues();
        }

        @Test
        @DisplayName("quotes the most recent refusal where a case has been referred twice")
        void quotesTheMostRecentRefusal() {
            // The finder is newest-first, and an older refusal has been superseded: quoting it would tell the
            // applicant about a decision that no longer stands.
            storedCase();
            Referral latest = referral(MasterDecision.Rejected, "Refused again, for the reasons given in court.");
            Referral earlier = referral(MasterDecision.Rejected, DIRECTION);
            earlier.setId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(latest, earlier));
            enqueueAnswers(1);

            emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER);

            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(any(), anyString(), any(), eq(REFERRAL_ID), anyString(), anyString(),
                                          anyString(), personalisation.capture(), any(), anyString());
            assertThat(personalisation.getValue())
                .contains("Refused again, for the reasons given in court.")
                .doesNotContain(DIRECTION);
        }

        @Test
        @DisplayName("sends nothing itself: the row is owed and the drain is what talks to Notify")
        void doesNotCallNotify() {
            storedCase();
            refusedReferral();
            enqueueAnswers(1);

            emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER);

            // A send that called Notify from the operation would not be an outbox: a Notify outage would become a
            // failed caseworker request rather than a row that is tried again.
            verifyNoInteractions(notify);
        }

        @Nested
        @DisplayName("the confirmation clock")
        class ConfirmationClock {

            @Test
            @DisplayName("stamps the referral with the outbox row's own instant when the confirmation email is owed")
            void stampsTheReferral() {
                storedCase();
                refusedReferralFor(ReferralReason.NonPublication);
                enqueueAnswers(1);

                emails.send(CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER);

                // Read before the verification below rather than inside it, because it is a verification itself.
                LocalDateTime enqueuedAt = enqueuedAt();
                // One statement over the three columns the send is entitled to move, on the referral named by id
                // and scoped to the case the outbox row was written for. The same instant the row was written
                // with, not a second reading of the clock: two calls could straddle a tick and leave the row and
                // the referral disagreeing about when the applicant was asked. The caseworker goes into both
                // audit columns, because the row did change and moving modifiedOn alone would attribute their
                // send to the judge who last wrote it.
                verify(referrals)
                    .startConfirmationClockByIdAndDeedPollId(REFERRAL_ID, CASE_ID, enqueuedAt, "A Caseworker");
                // And never through the referral the email was composed from: enqueue clears the persistence
                // context, so that entity is detached and a save on it would be a merge writing every column back
                // from the pre-enqueue snapshot -- discarding a judge's touch, a caseworker's withdrawal or the
                // applicant's answer if any of them committed in between.
                verify(referrals, never()).save(any());
                // And the case's read-model of the same fact, recomputed in this transaction for the case the
                // outbox row names. Asserted here because it is the half that cannot be seen on the referral:
                // without it the email goes out and the case never appears in the waiting list the feature
                // exists to provide, and the only other proof of it is container-backed and excluded from CI.
                verify(referralService).refreshAwaitingConfirmation(CASE_ID);
            }

            @Test
            @DisplayName("refuses the send when the answer arrived while ④ was being sent, in the same words")
            void refusesTheSendWhenTheAnswerArrivedMidSend() {
                // The statement's own WHERE clause refusing it, which is the only thing that can: the send was
                // allowed because the referral was unanswered when it was composed, and the answer committed
                // between that check and this write. A re-stamp would put the request date back after the answer
                // and restart a wait the applicant has already ended.
                storedCase();
                refusedReferralFor(ReferralReason.NonPublication);
                enqueueAnswers(1);
                when(referrals.startConfirmationClockByIdAndDeedPollId(any(), anyString(), any(), anyString()))
                    .thenReturn(0);

                // The same 409 a caseworker who clicked a second later would have been given by awaitingAnAnswer:
                // asking an applicant who has already replied to reply again is the thing that rule forbids, and
                // which side of their answer the click landed on is not a difference they can see. Raising it takes
                // the outbox row with it, which is why the email never goes.
                assertThatThrownBy(() -> emails.send(
                    CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage(CaseEmailService.CONFIRMATION_ALREADY_ANSWERED);

                // And no recompute: the transaction is rolling back, and the answer's own transaction has already
                // taken the case out of the waiting list.
                verifyNoInteractions(referralService);
            }

            @Test
            @DisplayName("starts no clock for the rejection email, which asks the applicant for nothing")
            void startsNoClockForTheRejectionEmail() {
                storedCase();
                Referral referral = refusedReferral();
                enqueueAnswers(1);

                emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER);

                // ③ tells the applicant a decision and starts a refund. A case recorded as waiting for a reply to
                // it would sit in the waiting list for ever, because no reply is coming.
                assertThat(referral.getConfirmationRequestedAt()).isNull();
                verify(referrals, never()).startConfirmationClockByIdAndDeedPollId(
                    any(), anyString(), any(), anyString());
                // Not the case's column either: a recompute hoisted out of ④'s arm would put every rejected case
                // into the waiting list, which the referral's own null stamp cannot show.
                verifyNoInteractions(referralService);
            }

            @Test
            @DisplayName("does not move a clock already running when a second send writes no row")
            void doesNotMoveTheClockOnASecondSend() {
                storedCase();
                Referral referral = refusedReferralFor(ReferralReason.NonPublication);
                LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
                referral.setConfirmationRequestedAt(asked);
                enqueueAnswers(0);
                Notification existing =
                    blocking(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, LocalDateTime.of(2026, 5, 3, 9, 30));

                assertThat(emails
                               .send(CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(),
                                     REQUEST_ID, CASEWORKER)
                               .id())
                    .isEqualTo(existing.getId());

                // A caseworker double-clicking must not reset the wait they are being reminded of: no row was
                // written, so no clock starts.
                assertThat(referral.getConfirmationRequestedAt()).isEqualTo(asked);
                verify(referrals, never()).startConfirmationClockByIdAndDeedPollId(
                    any(), anyString(), any(), anyString());
                // And the case's column is left as it stands: a recompute here would be harmless today and wrong
                // in principle, since nothing about the wait changed.
                verifyNoInteractions(referralService);
            }

            @Test
            @DisplayName("starts no clock when the send was refused, so no case waits for an email nobody owes")
            void startsNoClockWhenTheSendWasRefused() {
                // The recipient refusal, which happens before the row is written. A stamp before that check would
                // put a case in the waiting list for an email this service never owed anybody.
                storedCase().setApplicantEmail("  ");
                Referral referral = refusedReferralFor(ReferralReason.NonPublication);

                assertThatThrownBy(() -> emails.send(
                    CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER))
                    .isInstanceOf(IncompleteDataError.class);

                assertThat(referral.getConfirmationRequestedAt()).isNull();
                verify(referrals, never()).startConfirmationClockByIdAndDeedPollId(
                    any(), anyString(), any(), anyString());
                // Nor the case's column: a stamp before the recipient check would put a case in the waiting list
                // for an email this service never owed anybody.
                verifyNoInteractions(referralService);
            }

            @Test
            @DisplayName("409s a re-ask once the answer is recorded, rather than re-stamping a clock nothing reads")
            void refusesASecondAskOnceAnswered() {
                // The reachable sequence: the confirmation email is sent, the drain fails it PERMANENTly -- which
                // frees the exclusion group, so the unique index is no longer in the way -- the caseworker chases
                // the applicant by phone and records what they said, and then sends the email again.
                storedCase();
                Referral answered = refusedReferralFor(ReferralReason.NonPublication);
                LocalDateTime asked = LocalDateTime.of(2026, 5, 3, 9, 30);
                answered.setConfirmationRequestedAt(asked);
                answered.setConfirmationOutcome(ConfirmationOutcome.Withdrawn);
                answered.setConfirmationReceivedAt(LocalDateTime.of(2026, 5, 20, 11, 0));

                assertThatThrownBy(() -> emails.send(
                    CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage(CaseEmailService.CONFIRMATION_ALREADY_ANSWERED);

                // Refused before the outbox, so there is no email and no new request date. A send that got through
                // would leave the row asked later than it was answered, and -- because the case's column counts
                // only referrals with no outcome -- a case waiting for a reply that the waiting list cannot show.
                neverEnqueues();
                assertThat(answered.getConfirmationRequestedAt()).isEqualTo(asked);
                assertThat(answered.getConfirmationOutcome()).isEqualTo(ConfirmationOutcome.Withdrawn);
                verify(referrals, never()).startConfirmationClockByIdAndDeedPollId(
                    any(), anyString(), any(), anyString());
                verifyNoInteractions(referralService);
            }

            /** The instant the outbox row was written with, read off the statement the service called. */
            private LocalDateTime enqueuedAt() {
                ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
                verify(notifications).enqueue(any(), anyString(), any(), any(), anyString(), anyString(),
                                              anyString(), anyString(), now.capture(), anyString());
                return now.getValue();
            }
        }

        @Nested
        @DisplayName("the case note")
        class CaseNote {

            @ParameterizedTest
            @EnumSource(EmailTemplate.class)
            @DisplayName("writes one note per template, naming the email and the tab, as the caseworker")
            void writesOneNotePerEmail(EmailTemplate template) {
                // Every template rather than one, because the composer is deliberately not per template: a member
                // added to the enum must reach the timeline without anybody remembering to extend this. The
                // fixture satisfies all three at once -- a non-publication refusal for the two referral-driven
                // ones, and a case awaiting documents for the case-driven one.
                storedCase().setState(DeedPollState.AwaitingFurtherDocs);
                refusedReferralFor(ReferralReason.NonPublication);
                enqueueAnswers(1);

                emails.send(CASE_ID, template, minimumSlots(template), REQUEST_ID, CASEWORKER);

                // Scoped to the case the outbox row was written for, and authored by the person who chose to
                // send -- on ③ and ④ they wrote part of what the applicant reads, so the service account would be
                // wrong as well as unhelpful.
                verify(caseNotes).createNote(
                    CASE_ID, new CreateNoteInput(EmailNotes.sent(template)), CASEWORKER.noteAuthor());
                // Once, not once per something: an applicant owed one email gets one line on the timeline.
                verify(caseNotes, times(1)).createNote(anyString(), any(), any());
            }

            @Test
            @DisplayName("writes no second note when the unique index refused the insert")
            void writesNoNoteOnASecondSend() {
                // The idempotent repeat, which answers with the original row. A note written anyway would leave a
                // case reading as though the applicant had been written to twice by a caseworker who clicked once
                // and then clicked again -- and notes are never revised.
                storedCase();
                refusedReferral();
                enqueueAnswers(0);
                blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

                assertThat(emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID,
                                       CASEWORKER).enqueued())
                    .isFalse();

                verifyNoInteractions(caseNotes);
            }

            @Test
            @DisplayName("writes no note for a send refused before the outbox row")
            void writesNoNoteForASendRefusedBeforeTheRow() {
                // A note claiming an email was sent is worse than no note at all: the Emails tab has no row to
                // contradict it, the pointer sends the reader to a tab that says nothing, and notes are never
                // revised. The blank recipient rather than the missing refusal, because it is the refusal that
                // happens latest of the pre-enqueue three.
                storedCase().setApplicantEmail("  ");
                refusedReferral();

                assertThatThrownBy(() -> emails.send(
                    CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), REQUEST_ID, CASEWORKER))
                    .isInstanceOf(IncompleteDataError.class);

                verifyNoInteractions(caseNotes);
            }

            @Test
            @DisplayName("writes no note for a send refused after the row, because the note is in its transaction")
            void writesNoNoteForASendRefusedAfterTheRow() {
                // The one refusal that happens after a successful enqueue: the applicant answered while ④ was being
                // sent, and the clock's statement refuses it. The row rolls back, so the note must not survive --
                // which it cannot, because it is written in the same transaction and after the statement that
                // throws.
                storedCase();
                refusedReferralFor(ReferralReason.NonPublication);
                enqueueAnswers(1);
                when(referrals.startConfirmationClockByIdAndDeedPollId(any(), anyString(), any(), anyString()))
                    .thenReturn(0);

                assertThatThrownBy(() -> emails.send(
                    CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER))
                    .isInstanceOf(ConflictError.class)
                    .hasMessage(CaseEmailService.CONFIRMATION_ALREADY_ANSWERED);

                verifyNoInteractions(caseNotes);
            }

            @Test
            @DisplayName("says nothing about the recipient, the content or the slots the caseworker typed")
            void repeatsNoneOfTheContent() {
                // The note is the marker and the Emails tab is the record. A note repeating any of this would put a
                // second copy of personal data on a timeline nothing re-renders and nobody revises.
                storedCase();
                refusedReferral();
                enqueueAnswers(1);

                emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED,
                            Map.of("additional_notes", "Ring the office on 0300 123 4567."), REQUEST_ID, CASEWORKER);

                ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
                verify(caseNotes).createNote(anyString(), note.capture(), any());
                assertThat(note.getValue().note())
                    .doesNotContain(RECIPIENT)
                    .doesNotContain("0300 123 4567")
                    .doesNotContain("Okonjo")
                    .doesNotContain(DIRECTION);
            }

        }
    }

    @Nested
    @DisplayName("previewing")
    class Previewing {

        @Test
        @DisplayName("502s when Notify will not render it, naming the verdict and not the client's message")
        void reportsANotifyRefusal() throws Exception {
            // What an unprovisioned template ID earns: Notify answers 400, because there is no such template. A 502
            // rather than a 500, because what happened is that a dependency answered -- a 500 would send whoever is
            // paged to read this service's logs instead.
            //
            // PERMANENT is the only verdict this test can produce, and not because 400 was chosen: the client's
            // status-carrying constructor is package-private and its public one reports 400 for every message,
            // whatever the text says. So a rate limit or an outage reaching the same 502 is asserted where a status
            // can actually be produced -- over HTTP through StubNotify in CaseworkerEmailControllerTest.
            storedCase();
            refusedReferral();
            when(notify.generateTemplatePreview(anyString(), any()))
                .thenThrow(new NotificationClientException("Status code: 400 {template not found} key=secret"));

            assertThatThrownBy(() -> emails.preview(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of()))
                .isInstanceOf(UpstreamFailureError.class)
                .hasMessageContaining(EmailTemplate.APPLICATION_REJECTED.key())
                // The taxonomy's verdict, so the difference between "wrong request" and "Notify is unwell" is
                // legible to whoever reads the answer.
                .hasMessageContaining(NotificationFailure.PERMANENT.name())
                // And nothing the client said: its message contains the dependency's response.
                .hasMessageNotContaining("secret");
        }

        @Test
        @DisplayName("writes no outbox row, whether Notify answered or refused")
        void writesNoRow() throws Exception {
            storedCase();
            refusedReferral();
            when(notify.generateTemplatePreview(anyString(), any()))
                .thenThrow(new NotificationClientException("Notify refused it"));

            assertThatThrownBy(() -> emails.preview(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of()))
                .isInstanceOf(UpstreamFailureError.class);

            neverEnqueues();
        }

        @Test
        @DisplayName("passes Notify the resolved personalisation and returns what it rendered")
        void returnsNotifysRendering() throws Exception {
            storedCase();
            refusedReferral();
            // Built before the stubbing below, not inside it: Mockito reads a `when` inside a `when` as one
            // unfinished stubbing and fails the test rather than the assertion.
            TemplatePreview rendered = rendered();
            when(notify.generateTemplatePreview(eq("template-1"), any())).thenReturn(rendered);

            EmailPreview preview = emails.preview(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of());

            // Notify's own rendering, passed through: nothing here composes a subject or a body, because the
            // structure and the styling live in the template and a second copy would drift.
            assertThat(preview.subject()).isEqualTo("Your application has been rejected");
            assertThat(preview.html()).isEqualTo("<p>Because the Master said so.</p>");
            assertThat(preview.templateVersion()).isEqualTo(7);

            ArgumentCaptor<Map<String, Object>> sent = personalisationCaptor();
            verify(notify).generateTemplatePreview(eq("template-1"), sent.capture());
            assertThat(sent.getValue())
                .containsEntry(ApplicationRejectedPersonalisation.OLD_SURNAME, "Okonjo")
                .containsEntry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION);
        }

        @Test
        @DisplayName("does not check the recipient, because a preview is not a send")
        void doesNotRequireARecipient() throws Exception {
            // A caseworker may read what would go out before anyone has chased the applicant for an address. The
            // 422 belongs to the send, which is the operation that would otherwise owe an email to nobody -- and
            // moving the check earlier would stop them finding out what the letter says.
            storedCase().setApplicantEmail(null);
            refusedReferral();
            TemplatePreview rendered = rendered();
            when(notify.generateTemplatePreview(anyString(), any())).thenReturn(rendered);

            assertThat(emails.preview(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of()).subject())
                .isEqualTo("Your application has been rejected");
        }

        @Test
        @DisplayName("409s a case no Master has refused, before asking Notify anything")
        void refusesACaseWithNoRefusal() {
            storedCase();
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            assertThatThrownBy(() -> emails.preview(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of()))
                .isInstanceOf(ConflictError.class);

            // Notify's rate limit is a shared allowance, so a request that was going to be refused anyway does not
            // spend any of it.
            verifyNoInteractions(notify);
        }
    }

    /**
     * The case-driven chase: what makes it applicable, and what it composes without a referral.
     *
     * <p>Container-free like the rest of this class, and here that matters more than usual: the rule under test is
     * that applicability comes from one derived column and from nothing else, and the only other place it is
     * proved is a controller test CI does not run.
     */
    @Nested
    @DisplayName("chasing missing documents")
    class ChasingMissingDocuments {

        /** What a caseworker types into the one slot: applicant-facing prose naming what is outstanding. */
        private static final String REQUIRED =
            "Your birth certificate, and the consent of everyone with parental responsibility.";

        @ParameterizedTest
        @EnumSource(DeedPollState.class)
        @DisplayName("applies in AwaitingFurtherDocs and in no other state, with its own sentence when it does not")
        void appliesOnlyWhileAwaitingFurtherDocuments(DeedPollState state) {
            // Decision 1 across every state rather than against one: the condition is a single equality, so the
            // way it breaks is by being widened -- a predicate on "has an awaitingFurtherDocsInfo date" would go
            // on offering the chase after the documents arrived and the case moved on to ReadyForMaster.
            storedCase().setState(state);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            EmailAvailability entry =
                entryFor(emails.available(CASE_ID), EmailTemplate.CHASE_MISSING_DOCUMENTS);

            if (state == DeedPollState.AwaitingFurtherDocs) {
                assertThat(entry.applicable()).isTrue();
                assertThat(entry.reason()).isNull();
            } else {
                assertThat(entry.applicable()).isFalse();
                // Its own sentence, and not one about a referral: a caseworker sent to look for a refusal would
                // find the wrong tab. It names the field that derives the state, because the state is not settable.
                assertThat(entry.reason())
                    .isEqualTo(CaseEmailService.NOT_AWAITING_FURTHER_DOCS)
                    .isNotEqualTo(CaseEmailService.NO_REFUSAL)
                    .contains("awaited");
            }
        }

        @Test
        @DisplayName("a case with no state derived yet is not chased")
        void caseWithNoStateIsNotChased() {
            // Unreachable through the write path -- the cascade always derives one -- but a migrated row is not
            // this service's to vouch for, and "we do not know what this case is waiting for" must not become a
            // demand sent to an applicant.
            storedCase().setState(null);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            assertThat(entryFor(emails.available(CASE_ID), EmailTemplate.CHASE_MISSING_DOCUMENTS).applicable())
                .isFalse();
        }

        @Test
        @DisplayName("a refusal neither offers nor withholds it, because it is not about a referral")
        void refusalIsIrrelevantToTheChase() {
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            refusedReferralFor(ReferralReason.NonPublication);
            blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            // The rejection email has taken the referral's slot in its group, which hides ④ -- and says nothing
            // at all about ⑤. Sharing REFERRAL_OUTCOME would have made a chase impossible on any case whose
            // referral had been decided, which is most of them.
            List<EmailAvailability> available = emails.available(CASE_ID);
            assertThat(entryFor(available, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED).applicable()).isFalse();
            assertThat(entryFor(available, EmailTemplate.CHASE_MISSING_DOCUMENTS).applicable()).isTrue();
        }

        @Test
        @DisplayName("is unprovisioned without a reply-to, because its words ask the applicant to answer")
        void needsTheReplyTo() {
            String uuid = "2b0e6b6e-0000-4000-8000-0000000000aa";
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            assertThat(entryFor(chaseService(uuid, "").available(CASE_ID), EmailTemplate.CHASE_MISSING_DOCUMENTS))
                .satisfies(entry -> {
                    // Applicable and unprovisioned: the case is waiting for documents and the environment cannot
                    // send an email that asks for them from an address anybody reads.
                    assertThat(entry.applicable()).isTrue();
                    assertThat(entry.provisioned()).isFalse();
                });
            assertThat(entryFor(chaseService(uuid, "reply-to-1").available(CASE_ID),
                               EmailTemplate.CHASE_MISSING_DOCUMENTS).provisioned()).isTrue();
        }

        @Test
        @DisplayName("opens a form with an empty documents_required and asks Notify nothing")
        void publishesAnEmptyDefault() {
            // The slot nothing pre-populates, which is why defaults are not composed through the validator: an
            // empty payload is a refusal for this template, and a tab that asked for defaults through the send
            // path would answer 400 on exactly the cases the chase is for.
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());
            when(notifications.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());

            CaseEmailOverview overview = emails.overview(CASE_ID);

            assertThat(overview.slotDefaults()).singleElement().satisfies(defaults -> {
                assertThat(defaults.template()).isEqualTo(EmailTemplate.CHASE_MISSING_DOCUMENTS);
                assertThat(defaults.fields())
                    .containsExactly(Map.entry(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), ""));
            });
            verifyNoInteractions(notify);
        }

        @Test
        @DisplayName("enqueues with no referral, its own group, the applicant, and the caseworker's words")
        void enqueuesWithoutAReferral() {
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), "  " + REQUIRED + "  "), REQUEST_ID, CASEWORKER);

            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(
                any(), eq(CASE_ID), eq(REQUEST_ID),
                // Null, and not a referral that happens to exist: V11 made the column nullable for this, and a
                // borrowed id would key a chase to a refusal it has nothing to do with.
                isNull(),
                eq(EmailTemplate.CHASE_MISSING_DOCUMENTS.name()),
                eq(EmailExclusionGroup.DOCUMENT_CHASE.name()),
                eq(RECIPIENT), personalisation.capture(), any(LocalDateTime.class), eq("A Caseworker"));

            assertThat(receipt.enqueued()).isTrue();
            assertThat(receipt.recipient()).isEqualTo(RECIPIENT);
            // Trimmed, and resolved from the case beside it. No refund flag, no decision date, no direction.
            assertThat(personalisation.getValue())
                .contains("\"documents_required\":\"" + REQUIRED + "\"")
                .contains("\"reference_number\":\"DP2026-000123\"")
                .doesNotContain("applied_online")
                .doesNotContain("decision_date")
                .doesNotContain(DIRECTION);
        }

        @Test
        @DisplayName("reads no referral at all when it is sent, which is decision 1 as behaviour")
        void readsNoReferral() {
            // The rule that is easiest to lose: a compose that read the newest refusal "just in case" would make
            // a chase quietly depend on the referral table, and the first case with a superseded refusal would
            // start behaving differently from one without.
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);

            emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                        Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), REQUEST_ID, CASEWORKER);

            verifyNoInteractions(referrals);
            // And no confirmation clock, on the referral or on the case: this email waits for documents, which
            // awaitingFurtherDocsInfo already records, not for the answer to one referral's question.
            verifyNoInteractions(referralService);
        }

        @Test
        @DisplayName("writes one note naming the email, and repeats nothing the caseworker typed")
        void writesOneNote() {
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);

            emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                        Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), REQUEST_ID, CASEWORKER);

            verify(caseNotes).createNote(
                CASE_ID,
                new CreateNoteInput(EmailNotes.sent(EmailTemplate.CHASE_MISSING_DOCUMENTS)),
                CASEWORKER.noteAuthor());
            ArgumentCaptor<CreateNoteInput> note = ArgumentCaptor.forClass(CreateNoteInput.class);
            verify(caseNotes).createNote(anyString(), note.capture(), any());
            // The marker, not the record: the documents asked for are personal data and the Emails tab holds them.
            assertThat(note.getValue().note()).doesNotContain("birth certificate").doesNotContain(RECIPIENT);
        }

        @Test
        @DisplayName("a second chase of the same case is a second email, because a reminder is legitimate")
        void chasesAgain() {
            // Decision 5. The exclusion group must not turn a reminder into a conflict: a case can sit in
            // AwaitingFurtherDocs after the first chase, and the caseworker is the one who decides to ask again.
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);
            Map<String, Object> slots = Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED);

            assertThat(emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots, REQUEST_ID, CASEWORKER)
                           .enqueued())
                .isTrue();
            assertThat(emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots, REQUEST_ID, CASEWORKER)
                           .enqueued())
                .isTrue();

            // Two rows, and nothing read off the index between them: what separates a deliberate repeat from a
            // double-click is the request id notify-6's Task 2 adds, not this group.
            verify(notifications, times(2)).enqueue(any(), anyString(), any(), isNull(), anyString(), anyString(),
                                                    anyString(), anyString(), any(), anyString());
            verify(notifications, never()).findBlocking(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("409s a case that is not waiting for documents, and enqueues nothing")
        void refusesACaseThatIsNotWaiting() {
            storedCase().setState(DeedPollState.ReadyForMaster);

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CaseEmailService.NOT_AWAITING_FURTHER_DOCS);

            neverEnqueues();
            verifyNoInteractions(caseNotes);
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "   "})
        @DisplayName("400s a blank documents_required, because nothing pre-populates it")
        void refusesABlankSlot(String supplied) {
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            Map<String, Object> slots = new HashMap<>();
            slots.put(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), supplied);

            assertThatThrownBy(() ->
                emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots, REQUEST_ID, CASEWORKER))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(refusal -> {
                    assertThat(((InvalidInputError) refusal).errors())
                        .extracting(FieldError::field)
                        .containsExactly(EmailSlot.DOCUMENTS_REQUIRED.placeholder());
                    // The slot's own sentence: there is no direction to shorten and no referral to look at, so
                    // being told about a judge would send the caseworker somewhere useless.
                    assertThat(((InvalidInputError) refusal).errors().getFirst().message())
                        .contains("only a caseworker can say")
                        .doesNotContain("judge");
                });

            neverEnqueues();
        }

        @Test
        @DisplayName("takes 4,000 characters and refuses 4,001, which is the cap being inclusive")
        void refusesAnOverLongSlot() {
            // The cap is this package's own constant rather than ReferralSchemas', so the boundary is asserted
            // against the number an email declares -- a change to what a judge may record must not move it.
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);

            assertThat(emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(),
                       "x".repeat(EmailSlot.MAX_EMAIL_PROSE_LENGTH)),
                REQUEST_ID, CASEWORKER).enqueued()).isTrue();

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(),
                       "x".repeat(EmailSlot.MAX_EMAIL_PROSE_LENGTH + 1)),
                REQUEST_ID, CASEWORKER))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("400s an unknown key and a resolved one, and never overrides a case value")
        void refusesEverythingButItsOwnSlot() {
            storedCase().setState(DeedPollState.AwaitingFurtherDocs);
            enqueueAnswers(1);

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED,
                       MissingDocumentsPersonalisation.REFERENCE_NUMBER, "DP2026-999999",
                       "sign_off", "Kind regards"),
                REQUEST_ID, CASEWORKER))
                .isInstanceOf(InvalidInputError.class)
                .satisfies(refusal -> assertThat(((InvalidInputError) refusal).errors())
                    .extracting(FieldError::field)
                    .containsExactlyInAnyOrder(MissingDocumentsPersonalisation.REFERENCE_NUMBER, "sign_off"));

            // Refused rather than stripped, so the reference the applicant is told is the case's own and a
            // caseworker who tried to change it is told they cannot -- not given a success and a different email.
            emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                        Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), REQUEST_ID, CASEWORKER);
            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(any(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                                          personalisation.capture(), any(), anyString());
            assertThat(personalisation.getValue())
                .contains("\"reference_number\":\"DP2026-000123\"")
                .doesNotContain("DP2026-999999");
        }

        @Test
        @DisplayName("422s a case with no applicant email, as every send does")
        void refusesABlankRecipient() {
            DeedPoll deedPoll = storedCase();
            deedPoll.setState(DeedPollState.AwaitingFurtherDocs);
            deedPoll.setApplicantEmail("   ");

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), REQUIRED), REQUEST_ID, CASEWORKER))
                .isInstanceOf(IncompleteDataError.class);
        }

        private CaseEmailService chaseService(String templateId, String replyToId) {
            return new CaseEmailService(
                deedPolls, referrals, notifications, documents, notify,
                new NotifyProperties("https://notify.invalid", "key",
                                     Map.of(EmailTemplate.CHASE_MISSING_DOCUMENTS.key(), templateId), replyToId),
                referralService, caseNotes, new ObjectMapper());
        }

    }

    /**
     * What a zero-row enqueue is resolved to, which since V19 has three answers rather than two.
     *
     * <p><strong>Container-free deliberately, and this is where these rules have to live.</strong> The two unique
     * indexes are proved against real Postgres in {@code NotificationRepositoryTest}; what is asserted here is the
     * <em>order</em> the service reads them in and the answer it gives for each, which is the part CI runs. A
     * resolution that asked the exclusion group first would report "the rejection email was already requested" to a
     * caseworker who had merely retried their own POST, and no database test would notice.
     *
     * <p>The chase is the fixture for most of it because its personalisation is all strings, so an expected map can
     * be written out rather than captured -- and because a case-driven template is the one whose rows cannot collide
     * on the referral key at all, which is what makes the third answer reachable.
     */
    @Nested
    @DisplayName("resolving a refused insert")
    class ResolvingARefusedInsert {

        private static final String REQUIRED = "Your birth certificate.";

        /** A second compose journey: a different key for what may be a perfectly legitimate second send. */
        private static final UUID ANOTHER_REQUEST_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");

        @Test
        @DisplayName("answers with the row this request already wrote, and writes no second note or clock")
        void theSameRequestAnswersWithItsOwnRow() {
            // A double-click, a client retrying a POST whose response was lost, or two replicas racing on one
            // journey. All three are the same fact from here: this request has already been recorded, so the
            // caseworker is shown the row rather than a failure.
            chasedCase();
            enqueueAnswers(0);
            Notification existing = alreadySentUnderThisRequest(REQUIRED);

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID, CASEWORKER);

            assertThat(receipt.id()).isEqualTo(existing.getId());
            assertThat(receipt.enqueued()).isFalse();
            assertThat(receipt.recipient()).isEqualTo(RECIPIENT);
            // One email owed, so one marker on the timeline: a second note would leave the case reading as though
            // the applicant had been written to twice.
            verifyNoInteractions(caseNotes);
            // And the referral key is not even asked about: this request id answered the question, and a chase's
            // rows cannot occupy that index anyway.
            verify(notifications, never()).findBlocking(anyString(), any(), anyString());
        }

        @Test
        @DisplayName("compares the frozen personalisation as a map, so key order is not what decides it")
        void keyOrderIsNotPartOfTheComparison() {
            // jsonb does not preserve insertion order, so the row read back may hand its entries over in any
            // order at all. A comparison of the serialised text would therefore refuse a caseworker's own retry
            // as a reused key -- a 409 on the one path that exists to answer 200.
            chasedCase();
            enqueueAnswers(0);
            Notification existing = alreadySentUnderThisRequest(REQUIRED);
            Map<String, Object> shuffled = new LinkedHashMap<>();
            existing.getPersonalisation().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> shuffled.put(entry.getKey(), entry.getValue()));
            existing.setPersonalisation(shuffled);

            assertThat(emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID,
                                   CASEWORKER).enqueued())
                .isFalse();
        }

        @Test
        @DisplayName("409s a request id reused for text the caseworker has since edited, and sends nothing")
        void theSameJourneyWithEditedTextIsRefused() {
            // The failure the 409 exists for: taking the key's word would answer with the email they first wrote
            // and discard the edit, and taking the payload's word would write the second row the key exists to
            // prevent. Neither is safe, so the caseworker is told to start the email again.
            chasedCase();
            enqueueAnswers(0);
            alreadySentUnderThisRequest("Your birth certificate.");

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                slots("Your birth certificate, and your marriage certificate."), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CaseEmailService.REQUEST_ALREADY_USED)
                // Neither email's content, and not the key either: one is the applicant's and the other is
                // transport.
                .hasMessageNotContaining("marriage")
                .hasMessageNotContaining(REQUEST_ID.toString());

            verifyNoInteractions(caseNotes);
        }

        @Test
        @DisplayName("409s a request id reused for a different template on the same case")
        void theSameJourneyForADifferentEmailIsRefused() {
            chasedCase();
            refusedReferral();
            enqueueAnswers(0);
            Notification existing = alreadySentUnderThisRequest(REQUIRED);
            existing.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CaseEmailService.REQUEST_ALREADY_USED);
        }

        @Test
        @DisplayName("409s a request id reused after the applicant's address changed, because the row is frozen")
        void theSameJourneyToADifferentRecipientIsRefused() {
            // The recipient is frozen on the row and re-resolved on every send, so a case whose applicantEmail was
            // corrected between two POSTs of one journey is a different email to a different person. Answering
            // with the first row would tell the caseworker the corrected address had been written to.
            DeedPoll deedPoll = chasedCase();
            enqueueAnswers(0);
            alreadySentUnderThisRequest(REQUIRED).setRecipient("old.address@example.com");
            deedPoll.setApplicantEmail(RECIPIENT);

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CaseEmailService.REQUEST_ALREADY_USED);
        }

        @Test
        @DisplayName("a request id never seen falls through to the referral key, which answers in its own words")
        void anUnseenRequestFallsThroughToTheExclusionGroup() {
            // The distinction this whole resolution exists to draw. Both conflicts arrive as zero rows, and the
            // two answers are different sentences about different things: one says "start the email again" and
            // the other says "this referral has already had the email that contradicts this one".
            storedCase();
            refusedReferralFor(ReferralReason.NonPublication);
            enqueueAnswers(0);
            when(notifications.findByDeedPollIdAndRequestId(CASE_ID, ANOTHER_REQUEST_ID))
                .thenReturn(Optional.empty());
            blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), ANOTHER_REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining(EmailTemplate.APPLICATION_REJECTED.displayName())
                .hasMessageContaining("already requested")
                .satisfies(refusal -> assertThat(refusal.getMessage())
                    .isNotEqualTo(CaseEmailService.REQUEST_ALREADY_USED));
        }

        @Test
        @DisplayName("a second journey on the same referral is still the one email that referral gets")
        void secondJourneyOnOneReferralIsStillOneEmail() {
            // V15's rule survives V19 intact: a new compose journey does not buy a second copy of an email the
            // referral has already had. The receipt names the row that is already owed, as it did before.
            storedCase();
            refusedReferral();
            enqueueAnswers(0);
            Notification existing = blocking(
                EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), ANOTHER_REQUEST_ID, CASEWORKER);

            assertThat(receipt.id()).isEqualTo(existing.getId());
            assertThat(receipt.enqueued()).isFalse();
        }

        @Test
        @DisplayName("a chase refused with no readable row is a fault, and never a silent success")
        void refusalNoRowCanExplainIsAFault() {
            // Unreachable today: a chase's rows carry no referralId, so V19's key is the only one that can refuse
            // one -- and its row would then be readable. Reaching here means a constraint this service does not
            // know about refused the insert, and the safe answer is to raise: the transaction rolls back, so
            // nothing is owed and nothing was sent. Answering 200 with an unwritten row's id would tell a
            // caseworker an applicant had been emailed.
            chasedCase();
            enqueueAnswers(0);
            when(notifications.findByDeedPollIdAndRequestId(CASE_ID, REQUEST_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID, CASEWORKER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refused by a unique index");

            // And the referral key was never asked about with a null referral: that query's answer is always
            // empty, and asking it would read as though the database had an opinion about a repeat chase.
            verify(notifications, never()).findBlocking(anyString(), any(), anyString());
            verifyNoInteractions(caseNotes);
        }

        @Test
        @DisplayName("passes the caller's own request id to the statement, rather than one of its own")
        void bindsTheCallersRequestId() {
            // The one thing that makes every rule above reachable. An id generated here instead would make every
            // retry a new send, which is the behaviour V19 replaced -- and nothing else in this class would fail.
            chasedCase();
            enqueueAnswers(1);

            emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots(REQUIRED), REQUEST_ID, CASEWORKER);

            ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
            verify(notifications).enqueue(id.capture(), anyString(), eq(REQUEST_ID), isNull(), anyString(),
                                          anyString(), anyString(), anyString(), any(), anyString());
            // And it is not the row id either: the row is this service's identifier for the email and the request
            // id is the caller's for the journey, and conflating them would make a retry look like a new row.
            assertThat(id.getValue()).isNotEqualTo(REQUEST_ID);
        }

        private Map<String, Object> slots(String documentsRequired) {
            return Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), documentsRequired);
        }

        /** A case waiting for documents, which is the one state the chase is offered in. */
        private DeedPoll chasedCase() {
            DeedPoll deedPoll = storedCase();
            deedPoll.setState(DeedPollState.AwaitingFurtherDocs);
            return deedPoll;
        }

        /**
         * The row this case already holds for {@link #REQUEST_ID}: the chase, to the applicant, with exactly the
         * personalisation the resolver would produce for {@code documentsRequired}.
         *
         * <p>Written out rather than captured from a first send, so a resolver that started sending a different
         * set of keys fails here -- the comparison is only worth anything if the expected side is independent.
         */
        private Notification alreadySentUnderThisRequest(String documentsRequired) {
            Notification existing = new Notification();
            existing.setId(UUID.fromString("88888888-8888-8888-8888-888888888888"));
            existing.setDeedPollId(CASE_ID);
            existing.setRequestId(REQUEST_ID);
            existing.setTemplate(EmailTemplate.CHASE_MISSING_DOCUMENTS.name());
            existing.setExclusionGroup(EmailExclusionGroup.DOCUMENT_CHASE.name());
            existing.setRecipient(RECIPIENT);
            Map<String, Object> frozen = new LinkedHashMap<>();
            frozen.put(MissingDocumentsPersonalisation.REFERENCE_NUMBER, "DP2026-000123");
            frozen.put(MissingDocumentsPersonalisation.ONLINE_APPLICATION_REFERENCE, "DP-2026-0042");
            frozen.put(MissingDocumentsPersonalisation.OLD_FORENAME, "Alex");
            frozen.put(MissingDocumentsPersonalisation.OLD_SURNAME, "Okonjo");
            frozen.put(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), documentsRequired);
            existing.setPersonalisation(frozen);
            existing.setCreatedBy("A Caseworker");
            when(notifications.findByDeedPollIdAndRequestId(CASE_ID, REQUEST_ID))
                .thenReturn(Optional.of(existing));
            return existing;
        }
    }

    /**
     * The smallest payload each template accepts, which is nothing at all unless it has a required slot that
     * pre-populates from nothing.
     *
     * <p>A switch expression so a fourth template with an un-pre-populatable slot fails to compile here rather
     * than failing whichever every-template test reaches it first.
     */
    private static Map<String, Object> minimumSlots(EmailTemplate template) {
        return switch (template) {
            case APPLICATION_REJECTED, REQUEST_CONFIRMATION_TO_PROCEED -> Map.of();
            case CHASE_MISSING_DOCUMENTS -> Map.of(
                EmailSlot.DOCUMENTS_REQUIRED.placeholder(), "Your birth certificate.");
        };
    }

    /**
     * What the enqueue statement answers, for a test that does not care which of its ten arguments is which.
     *
     * <p>One helper rather than the matcher list repeated at every stub, because the list changes whenever the
     * statement gains a column -- V19's request id most recently -- and a copy of it per test is a copy to get
     * wrong. The tests that <em>are</em> about an argument name it explicitly instead.
     *
     * @param rows 1 for an insert that wrote the row, 0 for one a unique index refused
     */
    private void enqueueAnswers(int rows) {
        when(notifications.enqueue(any(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                                   anyString(), any(), anyString())).thenReturn(rows);
    }

    /** That nothing was enqueued at all, which is the assertion every refusal before the insert shares. */
    private void neverEnqueues() {
        verify(notifications, never()).enqueue(any(), anyString(), any(), any(), anyString(), anyString(),
                                              anyString(), anyString(), any(), anyString());
    }

    /** A stored case carrying everything the refusal email resolves from. */
    private DeedPoll storedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId(CASE_ID);
        deedPoll.setApplicantEmail(RECIPIENT);
        deedPoll.setDisplayReference("DP2026-000123");
        deedPoll.setOnlineApplicationReference("DP-2026-0042");
        deedPoll.setOldForename("Alex");
        deedPoll.setOldSurname("Okonjo");
        deedPoll.setPostCounter(PostCounter.Online);
        when(deedPolls.findById(CASE_ID)).thenReturn(Optional.of(deedPoll));
        return deedPoll;
    }

    private static Notification notification(UUID id, LocalDateTime createdOn, NotificationState state) {
        Notification notification = new Notification();
        notification.setId(id);
        notification.setDeedPollId(CASE_ID);
        notification.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
        notification.setRecipient(RECIPIENT);
        notification.setPersonalisation(Map.of("reference_number", "DP2026-000123"));
        notification.setState(state);
        notification.setCreatedBy("A Caseworker");
        notification.setCreatedOn(createdOn);
        return notification;
    }

    /**
     * A row already occupying this referral's slot in the shared exclusion group.
     *
     * <p>Stubbed on {@code findBlocking}, which is the statement that repeats the unique index's predicate --
     * so what "already occupying" means is decided in one place and proved against Postgres in
     * {@code NotificationRepositoryTest}, not restated here.
     */
    private Notification blocking(EmailTemplate template, LocalDateTime createdOn) {
        Notification row = notification(
            UUID.fromString("55555555-5555-5555-5555-555555555555"), createdOn, NotificationState.Pending);
        row.setTemplate(template.name());
        row.setExclusionGroup(template.exclusionGroup().name());
        when(notifications.findBlocking(CASE_ID, REFERRAL_ID, template.exclusionGroup().name()))
            .thenReturn(Optional.of(row));
        return row;
    }

    /** The case's one refused referral, carrying the direction the slot is pre-populated from. */
    private Referral refusedReferral() {
        Referral referral = referral(MasterDecision.Rejected, DIRECTION);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(referral));
        return referral;
    }

    /** The same refusal, raised for the given reasons -- none of them, for a migrated referral. */
    private Referral refusedReferralFor(ReferralReason... reasons) {
        Referral referral = refusedReferral();
        referral.setReasons(Arrays.stream(reasons).map(ReferralReason::name).toList());
        return referral;
    }

    /**
     * One template's row out of an availability answer.
     *
     * <p>By template rather than by position, because the list is every template in enum order and a test that
     * read {@code get(0)} would quietly start asserting about a different email the next time one is added.
     */
    private static EmailAvailability entryFor(List<EmailAvailability> available, EmailTemplate template) {
        return available.stream()
            .filter(entry -> entry.template() == template)
            .findFirst()
            .orElseThrow(() -> new AssertionError("No availability reported for " + template.key()));
    }

    /**
     * A rendered template, mocked rather than constructed.
     *
     * <p>{@code TemplatePreview}'s only public constructors take a JSON string or a {@code JSONObject}, so
     * building one would mean writing Notify's own response shape here and depending on the client's JSON library
     * to parse it. What is under test is that the three values are read and passed through, which a mock states
     * directly -- the real response shape is parsed by the real client in
     * {@code CaseworkerEmailControllerTest}, against {@code StubNotify}'s Notify-shaped body.
     */
    private static TemplatePreview rendered() {
        TemplatePreview preview = mock(TemplatePreview.class);
        when(preview.getSubject()).thenReturn(Optional.of("Your application has been rejected"));
        when(preview.getHtml()).thenReturn(Optional.of("<p>Because the Master said so.</p>"));
        when(preview.getVersion()).thenReturn(7);
        return preview;
    }

    /**
     * A captor for the personalisation map, whose generics a raw {@code forClass} cannot express.
     */
    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Map<String, Object>> personalisationCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private Referral referral(MasterDecision decision, String directionsText) {
        Referral referral = new Referral();
        referral.setId(REFERRAL_ID);
        referral.setDecision(decision);
        referral.setDirectionsText(directionsText);
        return referral;
    }
}
