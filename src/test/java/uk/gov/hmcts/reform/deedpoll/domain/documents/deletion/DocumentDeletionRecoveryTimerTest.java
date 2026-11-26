package uk.gov.hmcts.reform.deedpoll.domain.documents.deletion;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * The document deletion sweep's timer is on unless switched off (plan 20261125-permanent-document-deletion, Task 4),
 * whatever application deletion's worker switch says, and runs only the sweep.
 */
class DocumentDeletionRecoveryTimerTest {

    private final DocumentDeletionRecovery recovery = mock(DocumentDeletionRecovery.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(DocumentDeletionRecovery.class, () -> recovery)
        .withUserConfiguration(DocumentDeletionRecoveryTimer.class)
        .withPropertyValues("deedpoll.documents.deletion.recovery.interval=PT1H");

    @Test
    void presentByDefaultAndRunsTheSweep() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DocumentDeletionRecoveryTimer.class);
            context.getBean(DocumentDeletionRecoveryTimer.class).run();
            verify(recovery).run();
        });
    }

    @Test
    void presentWhileApplicationDeletionIsOff() {
        runner.withPropertyValues("deedpoll.deletion.cleanup.enabled=false")
            .run(context -> assertThat(context).hasSingleBean(DocumentDeletionRecoveryTimer.class));
    }

    @Test
    void absentWhenSwitchedOff() {
        runner.withPropertyValues("deedpoll.documents.deletion.recovery.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DocumentDeletionRecoveryTimer.class));
    }
}
