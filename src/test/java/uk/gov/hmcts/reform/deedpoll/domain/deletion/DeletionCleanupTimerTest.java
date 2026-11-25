package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The deletion worker's timer exists only when switched on, and then only runs the worker. */
class DeletionCleanupTimerTest {

    private final DeletionCleanup cleanup = mock(DeletionCleanup.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(DeletionCleanup.class, () -> cleanup)
        .withUserConfiguration(DeletionCleanupTimer.class)
        .withPropertyValues("deedpoll.deletion.cleanup.interval=PT1H", "deedpoll.deletion.cleanup.batch-size=10",
            "deedpoll.deletion.cleanup.lease=PT5M", "deedpoll.deletion.cleanup.max-attempts=8",
            "deedpoll.deletion.cleanup.retry-base=PT1M", "deedpoll.deletion.cleanup.poll-interval=PT1H");

    @Test
    void absentByDefault() {
        runner.run(context -> assertThat(context).doesNotHaveBean(DeletionCleanupTimer.class));
    }

    @Test
    void absentWhenSwitchedOff() {
        runner.withPropertyValues("deedpoll.deletion.cleanup.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(DeletionCleanupTimer.class));
    }

    @Test
    void presentWhenSwitchedOnAndRunsTheWorker() {
        runner.withPropertyValues("deedpoll.deletion.cleanup.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(DeletionCleanupTimer.class);
            context.getBean(DeletionCleanupTimer.class).run();
            verify(cleanup).run();
        });
    }
}
