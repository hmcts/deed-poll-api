package uk.gov.hmcts.reform.deedpoll.ccd;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UnauthorizedError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.CasePaging;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;
import uk.gov.hmcts.reform.deedpoll.repository.CcdAttemptRow;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The repair service's rules, container-free, so Docker-free CI runs them: the admin gate, the status filter, which
 * refusal each state earns, a lost race answered from a fresh read, and log lines that name the attempt and the move
 * but never the admin. {@code CaseworkerCcdAttemptControllerTest} proves the same over HTTP and Postgres.
 */
class CcdAttemptRepairsTest {

    private static final UUID ATTEMPT = UUID.fromString("5e1d0c3a-0000-4000-8000-00000000a001");

    private static final String ADMIN_SUBJECT = "idam-admin-distinctive-subject";

    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", ADMIN_SUBJECT);

    private static final CurrentUser CASEWORKER =
        CurrentUser.currentUser(List.of(), "A Caseworker", "idam-caseworker");

    private static final CurrentUser JUDGE =
        CurrentUser.currentUser(List.of(Role.JUDICIAL), "A Judge", "idam-judge");

    private CcdCreationAttempts attempts;

    private CcdAttemptRepairs repairs;

    private ListAppender<ILoggingEvent> logs;

    private Logger logger;

    @BeforeEach
    void setUp() {
        attempts = mock(CcdCreationAttempts.class);
        repairs = new CcdAttemptRepairs(attempts);
        logs = new ListAppender<>();
        logger = (Logger) LoggerFactory.getLogger(CcdAttemptRepairs.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        logs.stop();
    }

    private static CcdAttemptRow row(CcdCreationAttemptStatus status, boolean submissionMayHaveReached) {
        return new CcdAttemptRow(ATTEMPT, "record-1", "KBDP2026-000001", status, 3, "CCD refused the submission (422)",
            LocalDateTime.of(2026, 9, 27, 12, 0), submissionMayHaveReached);
    }

    private void reads(CcdAttemptRow... rows) {
        var stubbing = when(attempts.row(ATTEMPT));
        for (CcdAttemptRow row : rows) {
            stubbing = stubbing.thenReturn(Optional.of(row));
        }
    }

    private List<String> logged() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Nested
    @DisplayName("the admin gate")
    class Gate {

        @Test
        @DisplayName("a caseworker and a judge are refused every operation, before anything is read")
        void refusesEveryoneElse() {
            for (CurrentUser caller : List.of(CASEWORKER, JUDGE)) {
                assertThatThrownBy(() -> repairs.overview(caller, null, CasePaging.FIRST))
                    .isInstanceOf(ForbiddenError.class);
                assertThatThrownBy(() -> repairs.reconcileAgain(ATTEMPT.toString(), caller))
                    .isInstanceOf(ForbiddenError.class);
                assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), caller))
                    .isInstanceOf(ForbiddenError.class);
            }
            verifyNoInteractions(attempts);
            assertThat(logs.list).isEmpty();
        }

        @Test
        @DisplayName("the gate comes before the status filter, so a refused caller learns nothing about it")
        void gateComesFirst() {
            assertThatThrownBy(() -> repairs.overview(CASEWORKER, "nonsense", CasePaging.FIRST))
                .isInstanceOf(ForbiddenError.class);
        }

        @Test
        @DisplayName("an admin with no subject cannot repair, because a repair must be attributed")
        void repairNeedsASubject() {
            CurrentUser anonymousAdmin = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "An Admin", null);

            assertThatThrownBy(() -> repairs.reconcileAgain(ATTEMPT.toString(), anonymousAdmin))
                .isInstanceOf(UnauthorizedError.class);
            assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), anonymousAdmin))
                .isInstanceOf(UnauthorizedError.class);
            verifyNoInteractions(attempts);
        }
    }

    @Nested
    @DisplayName("the list")
    class Overview {

        @Test
        @DisplayName("answers the counts and the page, with canRetryFromScratch in place of the flag")
        void answersCountsAndPage() {
            Map<CcdCreationAttemptStatus, Long> counts = new EnumMap<>(CcdCreationAttemptStatus.class);
            for (CcdCreationAttemptStatus status : CcdCreationAttemptStatus.values()) {
                counts.put(status, 0L);
            }
            counts.put(NeedsRepair, 2L);
            counts.put(Linked, 40L);
            when(attempts.countsByStatus()).thenReturn(counts);
            CcdAttemptRow mayRetry = row(NeedsRepair, false);
            CcdAttemptRow mayNot = row(NeedsRepair, true);
            when(attempts.unlinked(Optional.empty(), PageRequest.of(1, 1)))
                .thenReturn(new PageImpl<>(List.of(mayRetry), PageRequest.of(1, 1), 2));

            CcdAttemptOverview overview = repairs.overview(ADMIN, null, new CasePaging(2, 1));

            assertThat(overview.counts()).containsExactlyEntriesOf(counts);
            assertThat(overview.attempts()).extracting(CcdAttemptSummary::canRetryFromScratch).containsExactly(true);
            assertThat(overview.page()).isEqualTo(2);
            assertThat(overview.size()).isEqualTo(1);
            assertThat(overview.total()).isEqualTo(2);
            assertThat(overview.totalPages()).isEqualTo(2);
            assertThat(CcdAttemptSummary.of(mayNot).canRetryFromScratch()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(value = CcdCreationAttemptStatus.class, names = "Linked", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("narrows to any status an unlinked attempt can have")
        void narrowsToALiveStatus(CcdCreationAttemptStatus status) {
            when(attempts.countsByStatus()).thenReturn(Map.of());
            when(attempts.unlinked(any(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

            repairs.overview(ADMIN, status.name(), CasePaging.FIRST);

            verify(attempts).unlinked(Optional.of(status), PageRequest.of(0, CasePaging.DEFAULT_SIZE));
        }

        @ParameterizedTest
        @ValueSource(strings = {"Linked", "needsrepair", "Nonsense", "NeedsRepair,Pending"})
        @DisplayName("refuses Linked and anything that is not a status, naming what it accepts and not echoing")
        void refusesAnUnlistableStatus(String status) {
            assertThatThrownBy(() -> repairs.overview(ADMIN, status, CasePaging.FIRST))
                .isInstanceOfSatisfying(InvalidInputError.class, error -> assertThat(error.errors())
                    .singleElement()
                    .satisfies(field -> {
                        assertThat(field.field()).isEqualTo("status");
                        assertThat(field.message())
                            .isEqualTo("is one of Pending, Claimed, AwaitingConfirmation, NeedsRepair");
                    }));
            verifyNoInteractions(attempts);
        }

        @Test
        @DisplayName("an absent or blank status lists every status but Linked")
        void blankListsEverything() {
            assertThat(CcdAttemptRepairs.listable(null)).isEmpty();
            assertThat(CcdAttemptRepairs.listable(" ")).isEmpty();
            assertThat(CcdAttemptRepairs.listable(" NeedsRepair ")).contains(NeedsRepair);
        }
    }

    @Nested
    @DisplayName("reconcile again")
    class ReconcileAgain {

        @Test
        @DisplayName("moves an attempt that needs repair, attributed to the admin, and logs it without them")
        void movesAndLogs() {
            reads(row(NeedsRepair, true), row(AwaitingConfirmation, true));
            when(attempts.reconcileAgain(ATTEMPT, ADMIN_SUBJECT)).thenReturn(true);

            CcdAttemptSummary moved = repairs.reconcileAgain(ATTEMPT.toString(), ADMIN);

            assertThat(moved.status()).isEqualTo(AwaitingConfirmation);
            verify(attempts).reconcileAgain(ATTEMPT, ADMIN_SUBJECT);
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> AwaitingConfirmation made");
            assertNoIdentityLogged();
        }

        @Test
        @DisplayName("is always open, even to an attempt that may have reached CCD")
        void openWhateverWasSubmitted() {
            reads(row(NeedsRepair, true), row(AwaitingConfirmation, true));
            when(attempts.reconcileAgain(ATTEMPT, ADMIN_SUBJECT)).thenReturn(true);

            assertThat(repairs.reconcileAgain(ATTEMPT.toString(), ADMIN).status()).isEqualTo(AwaitingConfirmation);
        }

        @ParameterizedTest
        @EnumSource(value = CcdCreationAttemptStatus.class, names = "NeedsRepair", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("refuses an attempt in any other status with a 409, and writes nothing")
        void refusesAnotherStatus(CcdCreationAttemptStatus status) {
            reads(row(status, false));

            assertThatThrownBy(() -> repairs.reconcileAgain(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("This attempt is " + status + " rather than NeedsRepair");
            verify(attempts, never()).reconcileAgain(any(), anyString());
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> AwaitingConfirmation refused (409)");
        }

        @Test
        @DisplayName("a lost race is a 409 in the words the attempt now calls for")
        void lostRace() {
            reads(row(NeedsRepair, false), row(AwaitingConfirmation, false));
            when(attempts.reconcileAgain(ATTEMPT, ADMIN_SUBJECT)).thenReturn(false);

            assertThatThrownBy(() -> repairs.reconcileAgain(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("This attempt is AwaitingConfirmation rather than NeedsRepair");
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> AwaitingConfirmation refused (409)");
        }
    }

    @Nested
    @DisplayName("try again from scratch")
    class RetryFromScratch {

        @Test
        @DisplayName("moves an attempt nothing was submitted for, attributed, and logs it without the admin")
        void movesAndLogs() {
            reads(row(NeedsRepair, false), row(Pending, false));
            when(attempts.retryFromScratch(ATTEMPT, ADMIN_SUBJECT)).thenReturn(true);

            CcdAttemptSummary moved = repairs.retryFromScratch(ATTEMPT.toString(), ADMIN);

            assertThat(moved.status()).isEqualTo(Pending);
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> Pending made");
            assertNoIdentityLogged();
        }

        @Test
        @DisplayName("refuses an attempt that may have reached CCD with a 409 that says what to do, and writes nothing")
        void refusesWhatMayHaveBeenSubmitted() {
            reads(row(NeedsRepair, true));

            assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CcdAttemptRepairs.MAY_HAVE_REACHED)
                .hasMessageContaining("second case")
                .hasMessageContaining("reconciliation instead");
            verify(attempts, never()).retryFromScratch(any(), anyString());
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> Pending refused (409)");
            assertNoIdentityLogged();
        }

        @ParameterizedTest
        @EnumSource(value = CcdCreationAttemptStatus.class, names = "NeedsRepair", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("refuses an attempt in any other status with a 409, and writes nothing")
        void refusesAnotherStatus(CcdCreationAttemptStatus status) {
            reads(row(status, false));

            assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(ConflictError.class)
                .hasMessageContaining("rather than NeedsRepair");
            verify(attempts, never()).retryFromScratch(any(), anyString());
        }

        @Test
        @DisplayName("a statement that matched nothing on a row still needing repair is the submission refusal")
        void lostToTheStatementsOwnCondition() {
            reads(row(NeedsRepair, false), row(NeedsRepair, true));
            when(attempts.retryFromScratch(ATTEMPT, ADMIN_SUBJECT)).thenReturn(false);

            assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(ConflictError.class)
                .hasMessage(CcdAttemptRepairs.MAY_HAVE_REACHED);
        }
    }

    @Nested
    @DisplayName("an unknown attempt")
    class Unknown {

        @Test
        @DisplayName("an id that is not a UUID is a 404, reads nothing and logs nothing")
        void notAnId() {
            for (Consumer<String> repair : List.<Consumer<String>>of(
                id -> repairs.reconcileAgain(id, ADMIN), id -> repairs.retryFromScratch(id, ADMIN))) {
                assertThatThrownBy(() -> repair.accept("not-an-id\nforged log line"))
                    .isInstanceOf(NotFoundError.class)
                    .hasMessage(CcdAttemptRepairs.NOT_FOUND);
            }
            verifyNoInteractions(attempts);
            assertThat(logs.list).isEmpty();
        }

        @Test
        @DisplayName("an id nothing was stored under is a 404, logged as refused")
        void nothingStored() {
            when(attempts.row(ATTEMPT)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> repairs.reconcileAgain(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(NotFoundError.class);
            assertThatThrownBy(() -> repairs.retryFromScratch(ATTEMPT.toString(), ADMIN))
                .isInstanceOf(NotFoundError.class);
            assertThat(logged()).containsExactly(
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> AwaitingConfirmation refused (404)",
                "CCD creation attempt " + ATTEMPT + ": repair NeedsRepair -> Pending refused (404)");
        }
    }

    private void assertNoIdentityLogged() {
        assertThat(logged())
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message)
                .doesNotContain(ADMIN_SUBJECT)
                .doesNotContain(ADMIN.name()));
    }
}
