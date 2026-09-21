package uk.gov.hmcts.reform.deedpoll.notify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseEmailsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CorrespondenceDocumentResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.EmailHistoryResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.EmailPreviewResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.EmailSlotDefaultsResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.EmailTypeResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SentEmailResponse;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.service.notify.NotificationClient;

import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The notification boundary's disclosure and scoping rules, stated where Docker-free CI runs them.
 *
 * <p>The controller test proves the same route over HTTP and Postgres, but CNP excludes that class because it
 * needs Docker. These rules are the ones that must not disappear with it: only the template's declared keys
 * leave for Notify, the applicant is always the recipient, another case's referral cannot make an email
 * applicable, and the API key reaches neither logs nor a response type.
 */
class NotificationSecurityTest {

    private static final String CASE_ID = "case-1";

    private static final String OTHER_CASE_ID = "case-2";

    private static final String APPLICANT = "applicant@example.com";

    private static final String REPRESENTATIVE = "representative@example.com";

    private static final String DIRECTION = "Refused: no settled intention shown.";

    private static final UUID REFERRAL_ID = UUID.fromString("1af7b81c-5576-459c-9ba8-0c78bbf8bcb6");

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final JavaClasses MAIN_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("uk.gov.hmcts.reform.deedpoll");

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
        emails = new CaseEmailService(
            deedPolls,
            referrals,
            notifications,
            documents,
            notify,
            new NotifyProperties(
                "https://notify.invalid",
                "not-a-real-key",
                Map.of(EmailTemplate.APPLICATION_REJECTED.key(), "template-1",
                       EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key(), "template-2"),
                "reply-to-1"),
            JSON);
    }

    @Test
    @DisplayName("sends exactly the declared personalisation keys to the applicant, never the representative")
    void sendsOnlyTheDeclaredKeysToTheApplicant() throws Exception {
        storedCase(CASE_ID);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(refusedReferral()));
        when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                                   any(), anyString())).thenReturn(1);

        emails.send(
            CASE_ID,
            EmailTemplate.APPLICATION_REJECTED,
            Map.of(EmailSlot.ADDITIONAL_NOTES.placeholder(), "Please contact the court."),
            CASEWORKER);

        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        var personalisation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).enqueue(
            any(),
            eq(CASE_ID),
            eq(REFERRAL_ID),
            eq(EmailTemplate.APPLICATION_REJECTED.name()),
            eq(EmailTemplate.APPLICATION_REJECTED.exclusionGroup().name()),
            recipient.capture(),
            personalisation.capture(),
            any(LocalDateTime.class),
            eq(CASEWORKER.name()));

        Map<String, Object> sent = JSON.readValue(personalisation.getValue(), new TypeReference<>() {
        });
        assertThat(sent.keySet())
            .containsExactlyInAnyOrderElementsOf(EmailTemplate.APPLICATION_REJECTED.personalisationKeys());
        assertThat(recipient.getValue()).isEqualTo(APPLICANT).isNotEqualTo(REPRESENTATIVE);
    }

    @Test
    @DisplayName("the confirmation email sends its own keys and nothing else, to the applicant")
    void theConfirmationEmailSendsOnlyItsOwnKeysToTheApplicant() throws Exception {
        storedCase(CASE_ID);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID))
            .thenReturn(List.of(refusedNonPublicationReferral()));
        when(notifications.enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(), anyString(),
                                   any(), anyString())).thenReturn(1);

        emails.send(CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), CASEWORKER);

        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        var personalisation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).enqueue(
            any(),
            eq(CASE_ID),
            eq(REFERRAL_ID),
            eq(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.name()),
            eq(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.exclusionGroup().name()),
            recipient.capture(),
            personalisation.capture(),
            any(LocalDateTime.class),
            eq(CASEWORKER.name()));

        Map<String, Object> sent = JSON.readValue(personalisation.getValue(), new TypeReference<>() {
        });
        assertThat(sent.keySet())
            .containsExactlyInAnyOrderElementsOf(
                EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.personalisationKeys());
        // And exactly one key per component of the record that resolved them, so a component added without a key
        // -- which would never reach the applicant -- fails here rather than rendering as a blank line.
        assertThat(sent).hasSize(RequestConfirmationToProceedPersonalisation.class.getRecordComponents().length);

        // The applicant, never the representative: this email asks the person whose name is changing whether they
        // still want it enrolled, and a representative's address is not theirs to answer from.
        assertThat(recipient.getValue()).isEqualTo(APPLICANT).isNotEqualTo(REPRESENTATIVE);

        // The judge's words leave once, through the editable slot, and the referral's own field is not a second
        // copy of them under another name -- decision 4, asserted where the payload is rather than in the record.
        assertThat(sent).containsEntry(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION);
        // Every key is a snake_case Notify placeholder, so this reads as written rather than needing a fold.
        assertThat(sent.keySet()).noneMatch(key -> key.contains("direction"));
        assertThat(sent.entrySet())
            .filteredOn(entry -> DIRECTION.equals(entry.getValue()))
            .extracting(Map.Entry::getKey)
            .containsExactly(EmailSlot.JUDGES_REASON.placeholder());
    }

    @Test
    @DisplayName("another case's refusal cannot make availability, preview or send cross the case boundary")
    void anotherCasesRefusalIsUnreachable() {
        storedCase(CASE_ID);
        storedCase(OTHER_CASE_ID);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of());
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID))
            .thenReturn(List.of(refusedReferral()));

        // Every template, not merely the first: another case's refusal must be invisible to all of them, and a
        // per-template rule is exactly where one of them could start reading outside its own case.
        assertThat(emails.available(CASE_ID))
            .hasSize(EmailTemplate.values().length)
            .allSatisfy(availability ->
                            assertThat(availability.applicable()).as("%s", availability.template()).isFalse());
        // Both templates, for the same reason the availability loop covers both: each reaches its referral through
        // its own rule, so each is a place a read could start naming a case the caller did not ask about.
        for (EmailTemplate template : EmailTemplate.values()) {
            assertThatThrownBy(() -> emails.preview(CASE_ID, template, Map.of()))
                .as("%s cannot be previewed from another case's refusal", template)
                .isInstanceOf(ConflictError.class);
            assertThatThrownBy(() -> emails.send(CASE_ID, template, Map.of(), CASEWORKER))
                .as("%s cannot be sent from another case's refusal", template)
                .isInstanceOf(ConflictError.class);
        }

        verify(referrals, never()).findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID);
        verify(notifications, never()).enqueue(any(), anyString(), any(), anyString(), anyString(), anyString(),
                                               anyString(), any(), anyString());
    }

    @Test
    @DisplayName("correspondence history cannot read another case's notification or document")
    void correspondenceIsCaseScoped() {
        storedCase(CASE_ID);
        storedCase(OTHER_CASE_ID);
        Notification onCase = notification();
        Notification elsewhere = notification();
        elsewhere.setId(UUID.randomUUID());
        elsewhere.setDeedPollId(OTHER_CASE_ID);
        when(notifications.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(onCase));
        when(notifications.findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID)).thenReturn(List.of(elsewhere));
        when(documents.findByDeedPollIdOrderByCreatedOnAsc(CASE_ID)).thenReturn(List.of());

        assertThat(emails.correspondence(CASE_ID))
            .extracting(CaseCorrespondence::id)
            .containsExactly(onCase.getId())
            .doesNotContain(elsewhere.getId());

        verify(notifications).findByDeedPollIdOrderByCreatedOnDesc(CASE_ID);
        verify(notifications, never()).findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID);
        verify(documents).findByDeedPollIdOrderByCreatedOnAsc(CASE_ID);
        verify(documents, never()).findByDeedPollIdOrderByCreatedOnAsc(OTHER_CASE_ID);

        DeedPollSupportingDocument otherCasesDocument = new DeedPollSupportingDocument();
        otherCasesDocument.setId("other-case-document");
        otherCasesDocument.setName("Private evidence");
        DeedPollSupportingDocument onCasesDocument = new DeedPollSupportingDocument();
        onCasesDocument.setId("on-case-document");
        onCasesDocument.setName("Enrolled deed poll");
        onCase.setPersonalisation(Map.of(
            "deed_document", onCasesDocument.getId(),
            "private_document", otherCasesDocument.getId()));

        assertThat(CorrespondenceDocument.from(
            onCase,
            List.of("deed_document", "private_document"),
            Map.of(onCasesDocument.getId(), onCasesDocument)))
            .as("only the case-scoped document map may satisfy a frozen reference")
            .containsExactly(new CorrespondenceDocument(onCasesDocument.getId(), onCasesDocument.getName()))
            .doesNotContain(new CorrespondenceDocument(
                otherCasesDocument.getId(), otherCasesDocument.getName()));
    }

    @Test
    @DisplayName("the Notify API key appears in neither sender logs nor an email response type")
    void apiKeyIsNotPublishedOrLogged() {
        StubNotify stub = new StubNotify();
        stub.start();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger(NotifyEmailSender.class);
        logs.start();
        logger.addAppender(logs);
        try {
            NotifyEmailSender sender = new NotifyEmailSender(stub.client(), stub.properties());
            sender.send(notification());
            stub.stubSendStatus(500);
            sender.send(notification());

            assertThat(logs.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(message -> message.contains(stub.apiKey())
                    || message.contains("Because the Master said so."));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            stub.stop();
        }

        assertThat(Stream.of(
                           CaseEmailsResponse.class,
                           CorrespondenceDocumentResponse.class,
                           EmailHistoryResponse.class,
                           EmailSlotDefaultsResponse.class,
                           EmailTypeResponse.class,
                           EmailPreviewResponse.class,
                           SentEmailResponse.class)
                       .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                       .map(RecordComponent::getName))
            .noneMatch(name -> name.equalsIgnoreCase("apiKey")
                || name.equalsIgnoreCase("secret")
                || name.equalsIgnoreCase("credentials"));

        assertThat(Arrays.stream(EmailHistoryResponse.class.getRecordComponents())
                       .map(RecordComponent::getName))
            .doesNotContain("personalisation", "delivered", "opened", "downloaded");
    }

    @Test
    @DisplayName("nothing outside the notify package calls NotificationClient directly")
    void notifyClientHasOneBoundary() {
        noClasses()
            .that().resideOutsideOfPackage("uk.gov.hmcts.reform.deedpoll.notify..")
            .should().dependOnClassesThat().haveFullyQualifiedName(NotificationClient.class.getName())
            .check(MAIN_CLASSES);
    }

    private DeedPoll storedCase(String id) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId(id);
        deedPoll.setApplicantEmail(APPLICANT);
        deedPoll.setRepresentativeEmail(REPRESENTATIVE);
        deedPoll.setDisplayReference("DP2026-000123");
        deedPoll.setOnlineApplicationReference("DP-2026-0042");
        deedPoll.setOldForename("Alex");
        deedPoll.setOldSurname("Okonjo");
        deedPoll.setPostCounter(PostCounter.Online);
        when(deedPolls.findById(id)).thenReturn(Optional.of(deedPoll));
        return deedPoll;
    }

    private static Referral refusedReferral() {
        Referral referral = new Referral();
        referral.setId(REFERRAL_ID);
        referral.setDecision(MasterDecision.Rejected);
        referral.setDirectionsText(DIRECTION);
        return referral;
    }

    /** The refusal the confirmation email is sent off: raised for a non-publication request, and decided. */
    private static Referral refusedNonPublicationReferral() {
        Referral referral = refusedReferral();
        referral.setReasons(List.of(ReferralReason.NonPublication.name()));
        referral.setDecisionDate(LocalDateTime.of(2026, 9, 18, 14, 30));
        return referral;
    }

    private static Notification notification() {
        Notification notification = new Notification();
        notification.setId(UUID.fromString("8f5da720-60ee-434a-abf3-d4b0e53aac17"));
        notification.setDeedPollId(CASE_ID);
        notification.setTemplate(EmailTemplate.APPLICATION_REJECTED.name());
        notification.setRecipient(APPLICANT);
        notification.setPersonalisation(Map.of(EmailSlot.JUDGES_REASON.placeholder(), DIRECTION));
        notification.setAttempts(1);
        notification.setCreatedBy(CASEWORKER.name());
        return notification;
    }
}
