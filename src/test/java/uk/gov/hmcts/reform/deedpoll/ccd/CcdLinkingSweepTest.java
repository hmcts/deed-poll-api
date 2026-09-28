package uk.gov.hmcts.reform.deedpoll.ccd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingService.Result;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The sweep's switch, bounds and stop rule, with the linking operation and the ledger mocked: the Docker-free
 * companion of the sweep scenarios in {@code CcdLinkingScenariosTest} and of {@code CcdLinkingSweepDisabledTest}.
 */
class CcdLinkingSweepTest {

    private CcdLinkingService linking;
    private CcdCreationAttempts attempts;

    @BeforeEach
    void setUp() {
        linking = mock(CcdLinkingService.class);
        attempts = mock(CcdCreationAttempts.class);
    }

    private CcdLinkingSweep sweep(boolean enabled) {
        return new CcdLinkingSweep(new CcdLinkingSweepProperties(enabled, 2, 5), linking, attempts);
    }

    @Test
    @DisplayName("switched off, it touches nothing: no selection, no link, no reconciliation")
    void offTouchesNothing() {
        sweep(false).sweep();

        verifyNoInteractions(linking, attempts);
    }

    @Test
    @DisplayName("switched on, it asks for max-records records, links each, then reads max-reconciliations attempts")
    void linksThenReconciles() {
        when(attempts.dueForLinking(2)).thenReturn(List.of("a", "b"));
        when(linking.link(anyString())).thenReturn(Result.LINKED);
        CcdCreationAttempt waiting = attempt();
        when(attempts.outstanding(5)).thenReturn(List.of(waiting));
        when(linking.reconcileOne(waiting)).thenReturn(Result.AWAITING_CONFIRMATION);

        sweep(true).sweep();

        var order = inOrder(attempts, linking);
        order.verify(attempts).dueForLinking(2);
        order.verify(linking).link("a");
        order.verify(linking).link("b");
        order.verify(attempts).outstanding(5);
        order.verify(linking).reconcileOne(waiting);
    }

    @Test
    @DisplayName("a result about one record -- in progress, unknown, needs repair -- does not stop the sweep")
    void oneRecordsTroubleDoesNotStopIt() {
        when(attempts.dueForLinking(2)).thenReturn(List.of("a", "b"));
        when(linking.link("a")).thenReturn(Result.NEEDS_REPAIR);
        when(linking.link("b")).thenReturn(Result.IN_PROGRESS);

        sweep(true).sweep();

        verify(linking).link("b");
        verify(attempts).outstanding(5);
    }

    @Test
    @DisplayName("RETRY_LATER stops the whole sweep: no further link and no reconciliation")
    void retryLaterStopsTheSweep() {
        when(attempts.dueForLinking(2)).thenReturn(List.of("a", "b"));
        when(linking.link("a")).thenReturn(Result.RETRY_LATER);

        sweep(true).sweep();

        verify(linking, never()).link("b");
        verify(attempts, never()).outstanding(anyInt());
    }

    @Test
    @DisplayName("an exception stops the sweep, and is logged rather than thrown into the list response")
    void anExceptionStopsTheSweepQuietly() {
        when(attempts.dueForLinking(2)).thenReturn(List.of("a", "b"));
        when(linking.link("a")).thenThrow(new IllegalStateException("CCD said something with a body in it"));

        assertThatCode(() -> sweep(true).sweep()).doesNotThrowAnyException();

        verify(linking, never()).link("b");
        verify(attempts, never()).outstanding(anyInt());
    }

    @Test
    @DisplayName("a selection the database refuses is logged too, and nothing is linked")
    void failedSelectionIsQuiet() {
        when(attempts.dueForLinking(2)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> sweep(true).sweep()).doesNotThrowAnyException();

        verify(linking, never()).link(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = Result.class, names = {"RETRY_LATER", "CCD_UNANSWERED"})
    @DisplayName("reconciliation stops at RETRY_LATER or CCD_UNANSWERED, as linking does")
    void reconciliationStopsToo(Result unwell) {
        when(attempts.dueForLinking(2)).thenReturn(List.of());
        CcdCreationAttempt first = attempt();
        CcdCreationAttempt second = attempt();
        when(attempts.outstanding(5)).thenReturn(List.of(first, second));
        when(linking.reconcileOne(first)).thenReturn(unwell);

        sweep(true).sweep();

        verify(linking, never()).reconcileOne(second);
    }

    @Test
    @DisplayName("reconciliation stops at an exception, and it is not thrown into the list response")
    void reconciliationStopsAtAnException() {
        when(attempts.dueForLinking(2)).thenReturn(List.of());
        CcdCreationAttempt first = attempt();
        CcdCreationAttempt second = attempt();
        when(attempts.outstanding(5)).thenReturn(List.of(first, second));
        when(linking.reconcileOne(first)).thenThrow(new IllegalStateException("database unavailable"));

        assertThatCode(() -> sweep(true).sweep()).doesNotThrowAnyException();

        verify(linking, never()).reconcileOne(second);
    }

    @Test
    @DisplayName("a submission CCD did not answer stops the sweep: no further link and no reconciliation")
    void unansweredStopsTheSweep() {
        when(attempts.dueForLinking(2)).thenReturn(List.of("first", "second"));
        when(linking.link("first")).thenReturn(Result.CCD_UNANSWERED);

        sweep(true).sweep();

        verify(linking, never()).link("second");
        verify(attempts, never()).outstanding(anyInt());
    }

    @Test
    @DisplayName("inside a transaction it refuses, before it selects anything: that is a wiring fault")
    void refusesInsideATransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> sweep(true).sweep())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        verifyNoInteractions(linking, attempts);
    }

    @ParameterizedTest
    @EnumSource(Result.class)
    @DisplayName("only RETRY_LATER and CCD_UNANSWERED say CCD is unwell (decision 4)")
    void stopRule(Result result) {
        assertThat(CcdLinkingSweep.stopsTheSweep(result))
            .isEqualTo(result == Result.RETRY_LATER || result == Result.CCD_UNANSWERED);
    }

    @Test
    @DisplayName("max-records and max-reconciliations below 1 refuse to start")
    void refusesBoundsBelowOne() {
        assertThatThrownBy(() -> new CcdLinkingSweepProperties(true, 0, 5))
            .hasMessageContaining("max-records");
        assertThatThrownBy(() -> new CcdLinkingSweepProperties(true, 2, 0))
            .hasMessageContaining("max-reconciliations");
    }

    @Test
    @DisplayName("the committed defaults: off, 2 records and 5 reconciliations, and no Feign call over 10 s")
    void committedDefaults() {
        new ApplicationContextRunner()
            .withUserConfiguration(SweepProperties.class)
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(context -> {
                CcdLinkingSweepProperties properties = context.getBean(CcdLinkingSweepProperties.class);
                assertThat(properties.enabled()).isFalse();
                assertThat(properties.maxRecords()).isEqualTo(2);
                assertThat(properties.maxReconciliations()).isEqualTo(5);

                // The sweep holds a list request for as long as its CCD calls take, so they are bounded.
                String feign = "spring.cloud.openfeign.client.config.default.";
                assertThat(context.getEnvironment().getProperty(feign + "connect-timeout", Integer.class))
                    .isNotNull().isLessThanOrEqualTo(10_000);
                assertThat(context.getEnvironment().getProperty(feign + "read-timeout", Integer.class))
                    .isNotNull().isLessThanOrEqualTo(10_000);
            });
    }

    @EnableConfigurationProperties(CcdLinkingSweepProperties.class)
    static class SweepProperties {
    }

    private static CcdCreationAttempt attempt() {
        CcdCreationAttempt attempt = new CcdCreationAttempt();
        attempt.setId(UUID.randomUUID());
        return attempt;
    }
}
