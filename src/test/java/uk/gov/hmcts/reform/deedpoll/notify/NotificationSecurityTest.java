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
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedDocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
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
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

    /**
     * What a caseworker types into the chase's one slot: applicant-facing prose about a real person's papers.
     *
     * <p>Distinctive on purpose. It is asserted <em>absent</em> from every log line, and a phrase that also
     * appeared in a template name or an error sentence would make that assertion pass for the wrong reason.
     */
    private static final String DOCUMENTS_REQUIRED =
        "Your birth certificate, and the consent of everyone with parental responsibility.";

    private static final UUID REFERRAL_ID = UUID.fromString("1af7b81c-5576-459c-9ba8-0c78bbf8bcb6");

    /**
     * One compose journey's {@code Idempotency-Key}: transport, and so never part of what leaves this service.
     */
    private static final UUID REQUEST_ID = UUID.fromString("6c9a1d0e-0000-4000-8000-0000000000fe");

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "caseworker-1");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Imported per rule and then dropped: a whole-codebase graph in a static field stays reachable for the
     * whole test JVM, and several of them exhaust its heap.
     */
    private static JavaClasses mainClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll");
    }

    private DeedPollRepository deedPolls;

    private ReferralRepository referrals;

    private NotificationRepository notifications;

    private DeedPollSupportingDocumentRepository documents;

    private NotificationClient notify;

    /**
     * Mocked, because the deed's bytes are not what this class is about: what it asserts is that the id frozen into
     * an outbox row is this case's document and that nothing else about the deed leaves the service.
     */
    private DeedDocumentService deeds;

    private CaseEmailService emails;

    @BeforeEach
    void setUp() {
        deeds = mock(DeedDocumentService.class);
        deedPolls = mock(DeedPollRepository.class);
        referrals = mock(ReferralRepository.class);
        notifications = mock(NotificationRepository.class);
        documents = mock(DeedPollSupportingDocumentRepository.class);
        notify = mock(NotificationClient.class);
        // The confirmation clock starting, which is what the statement answers on a referral nobody has answered.
        // Left at the mock's default 0 it would say the applicant answered mid-send, and ④ is refused for that.
        when(referrals.startConfirmationClockByIdAndDeedPollId(any(), anyString(), any(), anyString()))
            .thenReturn(1);
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
                       EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED.key(), "template-2",
                       EmailTemplate.CHASE_MISSING_DOCUMENTS.key(), "template-3",
                       EmailTemplate.FORMS_FOR_COMPLETION_ADULT.key(), "template-4",
                       EmailTemplate.FORMS_FOR_COMPLETION_MINOR.key(), "template-5"),
                "reply-to-1",
                StubNotify.RETENTION_WEEKS),
            mock(ReferralService.class),
            // Mocked: this class is about what leaves the service, and a note is written through the one path
            // that scopes it to its parent case. What the note may say is pinned in EmailNotesTest and
            // CaseEmailServiceTest.
            mock(NoteService.class),
            // Mocked for the same reason: a forms send stores a deed, and what this class asserts is what leaves
            // the service rather than what reaches the disk. DeedDocumentServiceTest is where the storing itself
            // meets a database.
            deeds,
            JSON, mock(org.springframework.transaction.PlatformTransactionManager.class),
            mock(uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedPreparations.class), Optional.empty());
    }

    @Test
    @DisplayName("sends exactly the declared personalisation keys to the applicant, never the representative")
    void sendsOnlyTheDeclaredKeysToTheApplicant() throws Exception {
        storedCase(CASE_ID);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(CASE_ID)).thenReturn(List.of(refusedReferral()));
        enqueueAnswers(1);

        emails.send(
            CASE_ID,
            EmailTemplate.APPLICATION_REJECTED,
            Map.of(EmailSlot.ADDITIONAL_NOTES.placeholder(), "Please contact the court."),
            REQUEST_ID, CASEWORKER);

        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        var personalisation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).enqueue(
            any(),
            eq(CASE_ID),
            eq(REQUEST_ID),
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
        enqueueAnswers(1);

        emails.send(CASE_ID, EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED, Map.of(), REQUEST_ID, CASEWORKER);

        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        var personalisation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).enqueue(
            any(),
            eq(CASE_ID),
            eq(REQUEST_ID),
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
        //
        // **Plus one**, and only one: `applied_on_paper` is derived from `appliedOnline` rather than being a
        // component of its own, because Notify's conditional has no `else` and two mutually exclusive paragraphs
        // need two flags. A negation cannot disagree with its source where a second field could. Asserted as
        // `+ 1` rather than a literal so the rule stays legible if the record grows.
        assertThat(sent)
            .hasSize(RequestConfirmationToProceedPersonalisation.class.getRecordComponents().length + 1)
            .containsKey(RequestConfirmationToProceedPersonalisation.APPLIED_ON_PAPER);

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
    @DisplayName("the chase sends exactly its own five keys, to the applicant, with no referral on the row")
    void theChaseSendsOnlyItsOwnKeysToTheApplicant() throws Exception {
        // The case-driven template, asserted here for the reason the other two are: the declared-keys rule is the
        // one that decides what leaves this service for GOV.UK Notify, and this template is the first whose
        // entitlement comes from the case rather than from a referral -- so it is also the first that could have
        // reached for a referral's fields while resolving.
        storedCase(CASE_ID).setState(DeedPollState.AwaitingFurtherDocs);
        enqueueAnswers(1);

        emails.send(
            CASE_ID,
            EmailTemplate.CHASE_MISSING_DOCUMENTS,
            Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), DOCUMENTS_REQUIRED),
            REQUEST_ID, CASEWORKER);

        var recipient = org.mockito.ArgumentCaptor.forClass(String.class);
        var personalisation = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(notifications).enqueue(
            any(),
            eq(CASE_ID),
            eq(REQUEST_ID),
            // No referral at all: a chase's row stores the column's own null, so nothing about it is keyed to a
            // decision it has nothing to do with.
            isNull(),
            eq(EmailTemplate.CHASE_MISSING_DOCUMENTS.name()),
            eq(EmailTemplate.CHASE_MISSING_DOCUMENTS.exclusionGroup().name()),
            recipient.capture(),
            personalisation.capture(),
            any(LocalDateTime.class),
            eq(CASEWORKER.name()));

        Map<String, Object> sent = JSON.readValue(personalisation.getValue(), new TypeReference<>() {
        });
        assertThat(sent.keySet())
            .containsExactlyInAnyOrderElementsOf(EmailTemplate.CHASE_MISSING_DOCUMENTS.personalisationKeys());
        // Exactly one key per component of the record that resolved them -- the four case values and the slot,
        // which this record carries as a component of its own. A component added without a key would never reach
        // the applicant; a key added without one would reach Notify with nothing behind it.
        assertThat(sent)
            .hasSize(MissingDocumentsPersonalisation.class.getRecordComponents().length)
            .containsEntry(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), DOCUMENTS_REQUIRED);
        // Nothing a judge wrote and nothing about a decision: this email is not about a referral, and a resolver
        // that quietly carried one would put a refusal in front of an applicant who has not been refused.
        assertThat(sent.values()).doesNotContain(DIRECTION);
        assertThat(sent.keySet()).noneMatch(key -> key.contains("judge") || key.contains("decision"));

        // The applicant, never the representative: the documents are the applicant's to send.
        assertThat(recipient.getValue()).isEqualTo(APPLICANT).isNotEqualTo(REPRESENTATIVE);
    }

    @Test
    @DisplayName("another case's request id is unreachable, so a chosen key cannot answer with a stranger's email")
    void anotherCasesRequestIdIsUnreachable() {
        // The disclosure the {@code Idempotency-Key} header makes possible and V19's index forecloses. The key is
        // a UUID a caller chooses, so a caller who learned or guessed one used on another case must not be
        // answered with that case's receipt -- which is what an unscoped lookup on the request id alone would do.
        storedCase(CASE_ID).setState(DeedPollState.AwaitingFurtherDocs);
        storedCase(OTHER_CASE_ID);
        Notification elsewhere = notification();
        elsewhere.setDeedPollId(OTHER_CASE_ID);
        elsewhere.setRequestId(REQUEST_ID);
        when(notifications.findByDeedPollIdAndRequestId(OTHER_CASE_ID, REQUEST_ID))
            .thenReturn(Optional.of(elsewhere));
        when(notifications.findByDeedPollIdAndRequestId(CASE_ID, REQUEST_ID)).thenReturn(Optional.empty());
        // Zero rows, which is the only path that reads a row back at all.
        enqueueAnswers(0);

        // A fault rather than the other case's receipt: nothing on this case explains the refusal, so the
        // transaction rolls back and nothing is owed. Answering with `elsewhere` would have published another
        // case's notification id to a caller who supplied only a UUID of their own choosing.
        assertThatThrownBy(() -> emails.send(
            CASE_ID,
            EmailTemplate.CHASE_MISSING_DOCUMENTS,
            Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), DOCUMENTS_REQUIRED),
            REQUEST_ID, CASEWORKER))
            .isInstanceOf(IllegalStateException.class)
            .satisfies(fault -> assertThat(fault.getMessage()).doesNotContain(OTHER_CASE_ID));

        verify(notifications).findByDeedPollIdAndRequestId(CASE_ID, REQUEST_ID);
        verify(notifications, never()).findByDeedPollIdAndRequestId(eq(OTHER_CASE_ID), any());
    }

    @Test
    @DisplayName("neither the caseworker's slot text nor Notify's rendering reaches a log line")
    void slotTextAndRenderedContentAreNotLogged() {
        // Both halves of what an email contains, against both halves of the code that handles one: the service
        // that resolves and refuses, and the sender that talks to Notify. The prose a caseworker writes about an
        // applicant's papers is personal data whose home is the row and the Emails tab; a log line carrying it
        // would copy it into an aggregator nobody has scoped to a case.
        StubNotify stub = new StubNotify();
        stub.start();
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        Logger logger = (Logger) LoggerFactory.getLogger("uk.gov.hmcts.reform.deedpoll.notify");
        logs.start();
        logger.addAppender(logs);
        try {
            storedCase(CASE_ID).setState(DeedPollState.AwaitingFurtherDocs);
            Map<String, Object> slots = Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), DOCUMENTS_REQUIRED);

            enqueueAnswers(1);
            emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots, REQUEST_ID, CASEWORKER);

            // The refusal that has both texts in its hands at once, which is the log line most likely to explain
            // itself too well: one key, two different emails.
            enqueueAnswers(0);
            Notification existing = notification();
            existing.setRequestId(REQUEST_ID);
            existing.setTemplate(EmailTemplate.CHASE_MISSING_DOCUMENTS.name());
            existing.setPersonalisation(Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), "Your passport."));
            when(notifications.findByDeedPollIdAndRequestId(CASE_ID, REQUEST_ID))
                .thenReturn(Optional.of(existing));
            assertThatThrownBy(() -> emails.send(
                CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS, slots, REQUEST_ID, CASEWORKER))
                .isInstanceOf(ConflictError.class);

            // And the drain's own sender, against a Notify that accepts the send and answers with the rendered
            // wording -- so the rendering exists to be logged and is asserted not to be.
            Notification owed = notification();
            owed.setTemplate(EmailTemplate.CHASE_MISSING_DOCUMENTS.name());
            owed.setPersonalisation(slots);
            deliver(new NotifyEmailSender(
                stub.client(),
                new NotifyProperties(
                    stub.baseUrl(), stub.apiKey(),
                    Map.of(EmailTemplate.CHASE_MISSING_DOCUMENTS.key(), StubNotify.TEMPLATE_ID),
                    StubNotify.REPLY_TO_ID,
                    StubNotify.RETENTION_WEEKS),
                uploads(stub)), owed);

            assertThat(logs.list)
                .isNotEmpty()
                .extracting(ILoggingEvent::getFormattedMessage)
                .allSatisfy(message -> assertThat(message)
                    .doesNotContain(DOCUMENTS_REQUIRED)
                    .doesNotContain("birth certificate")
                    .doesNotContain("Your passport.")
                    // Notify's own rendering of the email, which the stub answers every send with.
                    .doesNotContain("Because the Master said so.")
                    .doesNotContain(APPLICANT)
                    // And the key itself, which identifies a compose journey and belongs in no log either.
                    .doesNotContain(REQUEST_ID.toString()));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
            stub.stop();
        }
    }

    @Test
    @DisplayName("requestId is on the row and in no published type, because it is transport and not correspondence")
    void theRequestIdIsPublishedNowhere() {
        // Transport idempotency, deliberately invisible: a client that saw its own key echoed back would be
        // invited to treat it as the email's identity, and a caseworker reading correspondence history has no use
        // for it at all. Asserted over the whole API surface rather than over the three types that carry an
        // email, so a DTO this test does not know about cannot publish one either.
        assertThat(Stream.of(CaseCorrespondence.class, EmailHistoryResponse.class, SentEmailResponse.class)
                       .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                       .map(RecordComponent::getName))
            .doesNotContain("requestId");

        noFields()
            .that().areDeclaredInClassesThat().resideInAPackage("uk.gov.hmcts.reform.deedpoll.api..")
            .should().haveName("requestId")
            .allowEmptyShould(true)
            .check(mainClasses());

        // The counterpart, so the rule above is not green because nothing stores one: the column exists, on the
        // entity, which is the only place it is meant to.
        assertThat(Arrays.stream(Notification.class.getDeclaredFields()).map(java.lang.reflect.Field::getName))
            .contains("requestId");
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
        assertThat(emails.overview(CASE_ID).availability())
            .hasSize(EmailTemplate.values().length)
            .allSatisfy(availability ->
                            assertThat(availability.applicable()).as("%s", availability.template()).isFalse());
        // Every template, for the same reason the availability loop covers all of them: each reaches its
        // entitlement through its own rule, so each is a place a read could start naming a case the caller did not
        // ask about. The two referral-driven ones are refused *because* the refusal belongs elsewhere; the chase is
        // refused for a reason of its own -- this case is in no state to be chased -- so its arm of this loop says
        // nothing about isolation, and theChaseResolvesNothingFromAnotherCase is what does.
        for (EmailTemplate template : EmailTemplate.values()) {
            assertThatThrownBy(() -> emails.preview(CASE_ID, template, Map.of()))
                .as("%s is refused for this case rather than composed from another case's refusal", template)
                .isInstanceOf(ConflictError.class);
            assertThatThrownBy(() -> emails.send(CASE_ID, template, Map.of(), REQUEST_ID, CASEWORKER))
                .as("%s is refused for this case rather than sent from another case's refusal", template)
                .isInstanceOf(ConflictError.class);
        }

        verify(referrals, never()).findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID);
        neverEnqueues();
    }

    @Test
    @DisplayName("a chase resolves nothing from another case, whose refusal is the only one recorded anywhere")
    void theChaseResolvesNothingFromAnotherCase() {
        // The case-driven template's isolation claim, which is not the referral-driven one: a chase is not refused
        // because a refusal belongs elsewhere -- it never asks about a refusal at all. So it is asserted where it
        // is *applicable*, which is the only place the claim can fail: the row is keyed on no referral, the
        // recipient is this case's applicant, and the other case's referrals are never read.
        storedCase(CASE_ID).setState(DeedPollState.AwaitingFurtherDocs);
        storedCase(OTHER_CASE_ID);
        when(referrals.findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID))
            .thenReturn(List.of(refusedReferral()));
        enqueueAnswers(1);

        emails.send(CASE_ID, EmailTemplate.CHASE_MISSING_DOCUMENTS,
                    Map.of(EmailSlot.DOCUMENTS_REQUIRED.placeholder(), DOCUMENTS_REQUIRED),
                    REQUEST_ID, CASEWORKER);

        verify(notifications).enqueue(any(), eq(CASE_ID), eq(REQUEST_ID), isNull(), anyString(), anyString(),
                                      eq(APPLICANT), anyString(), any(), anyString());
        verify(referrals, never()).findByDeedPollIdOrderByCreatedOnDesc(OTHER_CASE_ID);
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
            NotifyEmailSender sender = new NotifyEmailSender(stub.client(), stub.properties(), uploads(stub));
            deliver(sender, notification());
            stub.stubSendStatus(500);
            deliver(sender, notification());

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
            .check(mainClasses());
    }

    /** What the enqueue statement answers, with every one of its ten arguments matched loosely. */
    private void enqueueAnswers(int rows) {
        when(notifications.enqueue(any(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                                   anyString(), any(), anyString())).thenReturn(rows);
    }

    /** That nothing was enqueued at all. */
    private void neverEnqueues() {
        verify(notifications, never()).enqueue(any(), anyString(), any(), any(), anyString(), anyString(),
                                              anyString(), anyString(), any(), anyString());
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

    /**
     * A document resolver for a sender under test, with no documents behind it.
     *
     * <p>Every row this class sends is for a template that carries none, so the resolver never reads: what it
     * supplies is the retention period, without which a template that <em>did</em> carry a document would be
     * refused for a reason this class is not about.
     */
    private static DocumentUploads uploads(StubNotify stub) {
        return new DocumentUploads(mock(CorrespondenceDocumentReader.class), stub.properties());
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

    private static SendOutcome deliver(NotificationSender sender, Notification row) {
        PreparedNotification prepared = sender.prepare(row);
        return prepared instanceof PreparedNotification.Refused refused ? refused.outcome()
            : ((PreparedNotification.Ready) prepared).delivery().send();
    }

}
