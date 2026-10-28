package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The processing window's timer (plan D2): it runs the sweep, never throws into the scheduler, logs a failure by
 * exception class only, refuses a caller transaction, and is switched on unless a deployment or a test says otherwise.
 */
@ExtendWith(OutputCaptureExtension.class)
class ReferralUnassignmentTest {

    private final ReferralService referrals = mock(ReferralService.class);
    private final ReferralUnassignment job = new ReferralUnassignment(referrals);

    @AfterEach
    void noTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    @DisplayName("runs the sweep")
    void runsTheSweep() {
        when(referrals.unassignOverdue()).thenReturn(2);

        job.run();

        verify(referrals).unassignOverdue();
    }

    @Test
    @DisplayName("never throws into the scheduler, and logs the exception class, never its message")
    void neverThrows(CapturedOutput output) {
        when(referrals.unassignOverdue()).thenThrow(
            new DataAccessResourceFailureException("connection to db-host refused for UPDATE deedpoll_referral"));

        assertThatCode(job::run).doesNotThrowAnyException();

        assertThat(output).contains("Processing-window sweep failed; the next run will try again",
                                    DataAccessResourceFailureException.class.getName());
        assertThat(output).doesNotContain("db-host", "UPDATE deedpoll_referral");
    }

    @Test
    @DisplayName("refuses to run inside a caller's transaction, before sweeping anything")
    void refusesACallerTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(referrals);
    }

    @Test
    @DisplayName("is scheduled at a fixed delay from deedpoll.referrals.unassign-interval, fifteen minutes by default")
    void isScheduledFromTheInterval() throws Exception {
        Scheduled scheduled = ReferralUnassignment.class.getMethod("run").getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString()).isEqualTo("${deedpoll.referrals.unassign-interval:PT15M}");
        assertThat(scheduled.initialDelayString()).isEqualTo(scheduled.fixedDelayString());
    }

    @Test
    @DisplayName("is a bean by default and with the property true, and absent when it is false")
    void isSwitchable() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ReferralService.class, () -> referrals)
            .withUserConfiguration(ReferralUnassignment.class);

        runner.run(context -> assertThat(context).hasSingleBean(ReferralUnassignment.class));
        runner.withPropertyValues("deedpoll.referrals.unassign-enabled=true",
                                  "deedpoll.referrals.unassign-interval=PT1H")
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ReferralUnassignment.class));
        runner.withPropertyValues("deedpoll.referrals.unassign-enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ReferralUnassignment.class));
    }

    @Test
    @DisplayName("application.yaml switches it on with a fifteen-minute interval")
    void applicationYamlDefaults() throws Exception {
        String yaml;
        try (InputStream in = getClass().getResourceAsStream("/application.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(Arrays.asList(yaml.split("\n")))
            .anySatisfy(line -> assertThat(line.trim())
                .isEqualTo("unassign-enabled: ${REFERRAL_UNASSIGN_ENABLED:true}"))
            .anySatisfy(line -> assertThat(line.trim())
                .isEqualTo("unassign-interval: ${REFERRAL_UNASSIGN_INTERVAL:PT15M}"));
    }
}
