package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.reform.authorisation.generators.AuthTokenGenerator;
import uk.gov.hmcts.reform.ccd.document.am.feign.CaseDocumentClientApi;
import uk.gov.hmcts.reform.deedpoll.auth.IdamAuthentication;
import uk.gov.hmcts.reform.deedpoll.ccd.SystemUserTokens;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.SendEmailResponse;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "deedpoll.documents.store=cdam", "case_document_am.url=http://localhost:4455",
    "notify.templates.forms-for-completion-adult=adult-template",
    "notify.email-reply-to-id=reply-to", "notify.document-retention-weeks=17"
})
@ActiveProfiles("test")
class CorrespondenceReadTest extends AbstractPostgresContainerTest {
    @Autowired private DeedPollService cases;
    @Autowired private DeedPollRepository caseRows;
    @Autowired private DeedPollSupportingDocumentRepository documents;
    @Autowired private NotificationRepository notifications;
    @Autowired private NotificationOutbox outbox;
    @Autowired private DocumentService staff;
    @Autowired private CorrespondenceDocumentReader reader;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeedPollSupportingDocumentTypeRepository types;
    private String typeId;
    @MockitoBean private CaseDocumentClientApi cdam;
    @MockitoBean private SystemUserTokens tokens;
    @MockitoBean private AuthTokenGenerator s2s;
    @MockitoBean private NotificationClient notify;
    private String caseId;
    private DeedPollSupportingDocument document;
    private Notification row;

    @BeforeEach
    void fixture() throws Exception {
        caseId = cases.createDeedPoll(DeedPollPatch.of(Map.of(
            "dateReceived", "2026-07-01T00:00:00.000Z", "type", "Adult"))).deedPoll().getId();
        document = new DeedPollSupportingDocument();
        document.setDeedPoll(caseRows.findById(caseId).orElseThrow());
        document.setName("Frozen deed");
        var type = new DeedPollSupportingDocumentType();
        type.setName("Test " + UUID.randomUUID());
        type = types.saveAndFlush(type);
        typeId = type.getId();
        document.setDocumentType(type);
        document.setDirection(DocumentDirection.Outgoing);
        document.setOriginalName("original.docx");
        document.setStorageState(DocumentStorageState.Attached);
        document.setCdamDocumentId(UUID.randomUUID());
        document.setStorageKey(document.getCdamDocumentId().toString());
        document = documents.saveAndFlush(document);
        row = new Notification();
        row.setDeedPollId(caseId);
        row.setRequestId(UUID.randomUUID());
        row.setTemplate(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.name());
        row.setExclusionGroup(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.exclusionGroup().name());
        row.setRecipient("frozen@example.test");
        row.setPersonalisation(Map.of("deed_document", document.getId(), "additional_notes", "frozen"));
        row.setState(NotificationState.Pending);
        row.setCreatedBy("Original worker");
        // Ahead of any Pending row an earlier class left in the shared database, so drain(1) takes this one.
        row.setNextAttemptAt(LocalDateTime.of(2000, 1, 1, 0, 0));
        row = notifications.saveAndFlush(row);
        when(tokens.accessToken()).thenReturn("Bearer system");
        when(s2s.generate()).thenReturn("Bearer s2s");
        when(cdam.getDocumentBinary(anyString(), anyString(), any()))
            .thenReturn(ResponseEntity.ok(new ByteArrayResource(new byte[]{1, 2, 3})));
        var response = mock(SendEmailResponse.class);
        when(response.getNotificationId()).thenReturn(UUID.randomUUID());
        when(notify.sendEmail(anyString(), anyString(), anyMap(), anyString(), anyString())).thenReturn(response);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        caseRows.deleteById(caseId);
        if (typeId != null) {
            types.deleteById(typeId);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void drainUsesSystemAuthorityAndPreservesFrozenContent(boolean signedIn) throws Exception {
        if (signedIn) {
            signIn();
        }
        assertThat(outbox.drain(1)).isEqualTo(1);
        verify(cdam).getDocumentBinary("Bearer system", "Bearer s2s", document.getCdamDocumentId());
        verify(notify).sendEmail(eq("adult-template"), eq("frozen@example.test"), anyMap(),
            eq(row.getId().toString()), eq("reply-to"));
        assertThat(notifications.findById(row.getId()).orElseThrow().getPersonalisation())
            .isEqualTo(row.getPersonalisation());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preSendFailureCommitsRetryWithoutNotifyOrRollbackPoisoning(boolean tokenFailure) {
        if (tokenFailure) {
            when(tokens.accessToken()).thenThrow(new IllegalStateException("token unavailable"));
        } else {
            when(cdam.getDocumentBinary(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("read unavailable"));
        }
        assertThat(outbox.drain(1)).isZero();
        Notification stored = notifications.findById(row.getId()).orElseThrow();
        assertThat(stored.getAttempts()).isEqualTo(1);
        assertThat(stored.getState()).isEqualTo(NotificationState.Pending);
        assertThat(stored.getFailureVerdict()).isEqualTo(NotificationFailureVerdict.TEMPORARY);
        verifyNoInteractions(notify);
        if (tokenFailure) {
            verifyNoInteractions(cdam);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStorageState.class, mode = EnumSource.Mode.EXCLUDE, names = "Attached")
    void nonAttachedStatesNeverReachCdam(DocumentStorageState state) {
        document.setStorageState(state);
        if (state == DocumentStorageState.Uploaded || state == DocumentStorageState.AttachUncertain) {
            document.setCdamHashToken("transient-test-token");
        }
        documents.saveAndFlush(document);
        assertThat(outbox.drain(1)).isZero();
        verifyNoInteractions(cdam, tokens, notify);
    }

    @Test
    void unknownStoredTemplateRefusesServiceReadAsNotFound() {
        row.setTemplate("REMOVED_TEMPLATE");
        row = notifications.saveAndFlush(row);
        assertThatThrownBy(() -> reader.forNotification(row, "deed_document"))
            .isInstanceOf(NotFoundError.class);
        verifyNoInteractions(cdam);
    }

    @Test
    void frozenReferenceToAnotherCaseNeverReachesCdam() {
        String other = cases.createDeedPoll(DeedPollPatch.of(Map.of(
            "dateReceived", "2026-07-01T00:00:00.000Z", "type", "Adult"))).deedPoll().getId();
        try {
            jdbc.update("UPDATE deedpoll_supportingdocument SET \"deedPollId\" = ? WHERE id = ?",
                other, document.getId());
            assertThat(outbox.drain(1)).isZero();
            verifyNoInteractions(cdam, tokens, notify);
        } finally {
            caseRows.deleteById(other);
        }
    }

    @Test
    void staffReadUsesStaffAuthority() throws Exception {
        signIn();
        try (var stream = staff.downloadDocument(caseId, document.getId()).stream()) {
            assertThat(stream.readAllBytes()).containsExactly(1, 2, 3);
        }
        verify(cdam).getDocumentBinary("Bearer unrelated-staff", "Bearer s2s", document.getCdamDocumentId());
        verifyNoInteractions(tokens);
    }

    @Test
    void staleSnapshotAndUndeclaredKeyAreRefusedBeforeStorage() {
        assertThatThrownBy(() -> reader.forNotification(row, "additional_notes"))
            .isInstanceOf(NotFoundError.class);
        row.setPersonalisation(Map.of("deed_document", UUID.randomUUID().toString()));
        assertThatThrownBy(() -> reader.forNotification(row, "deed_document"))
            .isInstanceOf(NotFoundError.class);
        verifyNoInteractions(cdam, tokens);
    }

    private static void signIn() {
        IdamAuthentication authentication = mock(IdamAuthentication.class);
        when(authentication.getCredentials()).thenReturn(Jwt.withTokenValue("unrelated-staff")
            .header("alg", "none").claim("sub", "unrelated").build());
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
