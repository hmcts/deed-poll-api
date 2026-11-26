package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * {@link DocumentDeletionRecovery#run} on a timer, every {@code deedpoll.documents.deletion.recovery.interval}.
 *
 * <p><strong>On by default</strong> ({@code DOCUMENT_DELETION_RECOVERY_ENABLED}, true): it only resumes requests a
 * member of staff admitted, so leaving it on starts nothing. It does not read application deletion's
 * {@code APPLICATION_DELETION_WORKER_ENABLED}. Switched off, an interrupted request stays {@code InProgress}, fenced
 * and recoverable, and is taken up by the first run after it is switched on again.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "deedpoll.documents.deletion.recovery.enabled", havingValue = "true",
    matchIfMissing = true)
public class DocumentDeletionRecoveryTimer {

    private final DocumentDeletionRecovery recovery;

    public DocumentDeletionRecoveryTimer(DocumentDeletionRecovery recovery) {
        this.recovery = recovery;
    }

    @Scheduled(fixedDelayString = "${deedpoll.documents.deletion.recovery.interval}",
        initialDelayString = "${deedpoll.documents.deletion.recovery.interval}")
    public void run() {
        recovery.run();
    }
}
