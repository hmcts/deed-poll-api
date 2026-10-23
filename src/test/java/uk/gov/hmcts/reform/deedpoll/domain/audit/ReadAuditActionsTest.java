package uk.gov.hmcts.reform.deedpoll.domain.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The document reads {@link ReadAudit} records, without a database: which action each is, and that both fail closed.
 */
class ReadAuditActionsTest {

    private static final CurrentUser READER =
        CurrentUser.currentUser(List.of(Role.CASEWORKER), "Ada Caseworker", "caseworker-1");

    private final AuditTrail trail = mock(AuditTrail.class);
    private final ReadAudit audit = new ReadAudit(trail);

    @Test
    @DisplayName("a preview is its own read action, on the document, in the case, as the reader")
    void previewIsItsOwnAction() {
        audit.documentPreviewed("case-1", "document-1", READER);

        ArgumentCaptor<AuditEntry> recorded = ArgumentCaptor.forClass(AuditEntry.class);
        verify(trail).recordRead(recorded.capture());
        assertThat(recorded.getValue().action()).isEqualTo(AuditAction.DOCUMENT_PREVIEWED);
        assertThat(recorded.getValue().action().isRead()).isTrue();
        assertThat(recorded.getValue().entityType()).isEqualTo(AuditEntities.DOCUMENT);
        assertThat(recorded.getValue().entityId()).isEqualTo("document-1");
        assertThat(recorded.getValue().caseId()).isEqualTo("case-1");
        assertThat(recorded.getValue().actor()).isEqualTo(AuditActor.of(READER));
    }

    @Test
    @DisplayName("a preview and a download are told apart in the trail")
    void previewIsNotADownload() {
        assertThat(AuditAction.DOCUMENT_PREVIEWED).isNotEqualTo(AuditAction.DOCUMENT_DOWNLOADED);
        assertThat(AuditAction.DOCUMENT_PREVIEWED.name()).hasSizeLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("a preview that cannot be recorded propagates the failure, as a download does")
    void previewFailsClosed() {
        IllegalStateException failure = new IllegalStateException("no connection");
        doThrow(failure).when(trail).recordRead(any());

        assertThatThrownBy(() -> audit.documentPreviewed("case-1", "document-1", READER)).isSameAs(failure);
        assertThatThrownBy(() -> audit.documentDownloaded("case-1", "document-1", READER)).isSameAs(failure);
    }
}
