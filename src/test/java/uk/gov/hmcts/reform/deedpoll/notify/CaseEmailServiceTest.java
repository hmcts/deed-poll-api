package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");

    private DeedPollRepository deedPolls;
    private ReferralRepository referrals;
    private NotificationRepository notifications;
    private DeedPollSupportingDocumentRepository documents;
    private NotificationClient notify;
    private CaseEmailService emails;

    @BeforeEach
    void setUp() {
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notifications = mock(NotificationRepository.class);
        documents = mock(DeedPollSupportingDocumentRepository.class);
        notify = mock(NotificationClient.class);
        // A provisioned template, so nothing here turns on the placeholder. The unprovisioned case is a Notify
        // refusal, which is asserted in its own test below.
        NotifyProperties properties = new NotifyProperties(
            "https://notify.invalid", "key", Map.of(EmailTemplate.APPLICATION_REJECTED.key(), "template-1"), "");
        emails = new CaseEmailService(
            deedPolls, referrals, notifications, documents, notify, properties, new ObjectMapper());
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

            assertThat(emails.available(CASE_ID))
                .allSatisfy(entry -> assertThat(entry.applicable()).as("%s", entry.template()).isTrue());
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

            assertThat(emails.available(CASE_ID))
                .allSatisfy(entry -> assertThat(entry.applicable()).as("%s", entry.template()).isTrue());

            // Once per template, both asking about the same group, which is the whole mechanism: two questions
            // with one answer rather than two rules that could disagree.
            verify(notifications, times(2)).findBlocking(
                CASE_ID, REFERRAL_ID, EmailExclusionGroup.REFERRAL_OUTCOME.name());
            verify(notifications, never()).findByDeedPollIdOrderByCreatedOnDesc(CASE_ID);
        }

        @Test
        @DisplayName("says a template is unprovisioned, so a tab can say so rather than offering a broken button")
        void reportsProvisioning() {
            // With no configured template ID -- which is every environment until notify-0 lands -- a send fails on
            // the drain and a preview is a 502. NotifyProperties.isProvisioned exists for this sentence.
            CaseEmailService unprovisioned = new CaseEmailService(
                deedPolls, referrals, notifications, documents, notify,
                new NotifyProperties("https://notify.invalid", "", Map.of(), ""), new ObjectMapper());
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

            assertThatThrownBy(() -> emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER))
                .isInstanceOf(IncompleteDataError.class)
                .satisfies(refusal -> assertThat(((IncompleteDataError) refusal).errors())
                    .extracting(FieldError::field)
                    .containsExactly("applicantEmail"));

            verify(notifications, never()).enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                                   anyString(), any(), anyString());
        }

        @Test
        @DisplayName("enqueues the resolved email against the case, the referral and the template's member name")
        void enqueuesTheResolvedEmail() {
            storedCase();
            refusedReferral();
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(1);

            SendReceipt receipt = emails.send(
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of("additional_notes", " Ring the office. "),
                CASEWORKER);

            ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(id.capture(), eq(CASE_ID), eq(REFERRAL_ID),
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
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(1);

            assertThat(emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER).recipient())
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
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(0);
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
                CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER);

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
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(0);
            blocking(EmailTemplate.APPLICATION_REJECTED, LocalDateTime.of(2026, 6, 3, 9, 30));

            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), CASEWORKER))
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

            assertThatThrownBy(() -> emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER))
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
                Map.of(ApplicationRejectedPersonalisation.REFERENCE_NUMBER, "2026/999999"), CASEWORKER))
                .isInstanceOf(InvalidInputError.class);

            verify(notifications, never()).enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                                   anyString(), any(), anyString());
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
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(1);

            emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER);

            ArgumentCaptor<String> personalisation = ArgumentCaptor.forClass(String.class);
            verify(notifications).enqueue(any(), anyString(), eq(REFERRAL_ID), anyString(), anyString(),
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
            when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                       anyString(), any(), anyString())).thenReturn(1);

            emails.send(CASE_ID, EmailTemplate.APPLICATION_REJECTED, Map.of(), CASEWORKER);

            // A send that called Notify from the operation would not be an outbox: a Notify outage would become a
            // failed caseworker request rather than a row that is tried again.
            verifyNoInteractions(notify);
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

            verify(notifications, never()).enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                                   anyString(), any(), anyString());
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
