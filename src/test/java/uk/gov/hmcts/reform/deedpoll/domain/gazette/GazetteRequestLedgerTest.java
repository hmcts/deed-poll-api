package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.ForbiddenError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestLedger.Claim;
import uk.gov.hmcts.reform.deedpoll.domain.gazette.GazetteRequestLedger.Reservation;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PostgreSQL and real transactions: no Notify, no mocked persistence. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
    "deedpoll.gazette.enabled=true", "notify.api-key=synthetic-not-used",
    "notify.email-reply-to-id=10000000-0000-0000-0000-000000000001", "notify.document-retention-weeks=4",
    "notify.templates.gazette-adult=20000000-0000-0000-0000-000000000001",
    "notify.templates.gazette-minor=20000000-0000-0000-0000-000000000001",
    "notify.templates.gazette-mixed=20000000-0000-0000-0000-000000000001"
})
class GazetteRequestLedgerTest extends AbstractPostgresContainerTest {
    private static final String SUBJECT = "gazette-ledger-test";
    private static final CurrentUser ADMIN =
        CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "Gazette Admin", SUBJECT);
    private static final GazetteDeliveryConfiguration CONFIG =
        new GazetteDeliveryConfiguration(UUID.fromString("20000000-0000-0000-0000-000000000001"),
            UUID.fromString("10000000-0000-0000-0000-000000000001"), 4);
    private static final String FINGERPRINT = "confirmed-input-v1";

    @Autowired
    private GazetteRequestLedger ledger;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DeedPollService cases;
    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private DeedPollRepository caseRecords;
    private final Map<UUID, String> tokens = new ConcurrentHashMap<>();

    private final List<String> fixtureCases = new ArrayList<>();
    private Map<String, Object> oldSettings;

    @BeforeEach
    void configureRecipient() {
        oldSettings = jdbc.queryForMap("SELECT * FROM deedpoll_gazette_settings WHERE id = 1");
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = 'gazette@example.com', revision = 1,
                updated_at = now(), updated_by_id = 'fixture', updated_by_name = 'Fixture' WHERE id = 1
            """);
    }

    @AfterEach
    void cleanOwnEvidence() {
        jdbc.execute("DROP TRIGGER IF EXISTS gazette_test_note_failure ON deedpoll_notes");
        jdbc.execute("DROP FUNCTION IF EXISTS gazette_test_note_failure()");
        for (String id : fixtureCases) {
            jdbc.update("DELETE FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_notice_batch_case WHERE deed_poll_id = ?", id);
            jdbc.update("DELETE FROM deedpoll_gazette_request_case WHERE deed_poll_id = ?", id);
        }
        jdbc.update("DELETE FROM deedpoll_gazette_request_file WHERE batch_id IN "
            + "(SELECT id FROM deedpoll_gazette_request WHERE submitted_by_id = ?)", SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_request WHERE submitted_by_id = ?", SUBJECT);
        jdbc.update("DELETE FROM deedpoll_gazette_notice_batch WHERE sent_by_id = ?", SUBJECT);
        for (String id : fixtureCases) {
            jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id);
        }
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET recipient_email = ?, revision = ?, updated_at = ?,
                updated_by_id = ?, updated_by_name = ? WHERE id = 1
            """, oldSettings.get("recipient_email"), oldSettings.get("revision"), oldSettings.get("updated_at"),
            oldSettings.get("updated_by_id"), oldSettings.get("updated_by_name"));
    }

    @Test
    void reservesFrozenReferencesRecipientAndAttributionAndRepeatsWithoutAClaim() {
        String adult = ready("Adult");
        String minor = ready("MinorTransgender");
        UUID key = UUID.randomUUID();
        Reservation first = reserve(key, adult, minor);
        assertThat(first.claim()).isNotNull();
        assertThat(first.request()).satisfies(row -> {
            assertThat(row.recipientEmail()).isEqualTo("gazette@example.com");
            assertThat(row.adultCount()).isOne();
            assertThat(row.minorCount()).isOne();
            assertThat(row.submittedById()).isEqualTo(SUBJECT);
            assertThat(row.configuration()).isEqualTo(CONFIG);
        });
        jdbc.update("UPDATE deedpoll_gazette_settings SET recipient_email = 'changed@example.com', revision = 2");
        Reservation repeat = ledger.admit(ADMIN, key, List.of(minor, adult), tokens.get(key), 1);
        assertThat(repeat.claim()).isNull();
        assertThat(repeat.request().id()).isEqualTo(first.request().id());
        assertThat(repeat.request().recipientEmail()).isEqualTo("gazette@example.com");
        assertThat(noteCount(adult)).isOne();
        assertThat(noteCount(minor)).isOne();
        assertThatThrownBy(() -> ledger.admit(ADMIN, key, List.of(adult), FINGERPRINT, 1))
            .isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> ledger.admit(ADMIN, key, List.of(adult, minor), "changed", 1))
            .isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> ledger.admit(ADMIN, key, List.of(adult, minor), FINGERPRINT, 2))
            .isInstanceOf(ConflictError.class);
        CurrentUser other = CurrentUser.currentUser(List.of(Role.DEEDPOLL_ADMIN), "Gazette Admin", "other");
        assertThatThrownBy(() -> ledger.admit(other, key, List.of(adult, minor), FINGERPRINT, 1))
            .isInstanceOf(ConflictError.class);
    }

    @Test
    void refusesNonAdminsBeforeLookingUpOrWritingAnything() {
        assertThatThrownBy(() -> ledger.admit(CurrentUser.ANONYMOUS, null, null, null, 0))
            .isInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> ledger.read(CurrentUser.ANONYMOUS, UUID.randomUUID()))
            .isInstanceOf(ForbiddenError.class);
    }

    @Test
    void refusesMissingOrChangedRecipientAndInvalidSelectionWithoutEvidence() {
        String id = ready("Adult");
        assertThatThrownBy(() -> ledger.admit(ADMIN, UUID.randomUUID(), List.of(id), FINGERPRINT, 0))
            .isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id, id)).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID())).isInstanceOf(InvalidInputError.class);
        jdbc.update("""
            UPDATE deedpoll_gazette_settings SET revision = 0, recipient_email = null, updated_at = null,
                updated_by_id = null, updated_by_name = null
            """);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(ConflictError.class);
        assertThat(noteCount(id)).isZero();
        assertThat(reservations(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Unknown", "DeedOfRelinquishment"})
    void refusesWholeSelectionForAnExcludedOrUnclassifiedCase(String type) {
        String valid = ready("Adult");
        String excluded = ready(type);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), valid, excluded)).isInstanceOf(ConflictError.class);
        assertThat(reservations(valid)).isZero();
        assertThat(noteCount(valid)).isZero();
    }

    @Test
    void refusesEveryReadinessFailure() {
        String id = ready("Adult");
        for (String assignment : List.of("type = null", "state = 'Received'", "\"publishToLondonGazette\" = false",
            "\"dateNoticeSentToLondonGazette\" = now()", "\"displayReference\" = null")) {
            jdbc.update("UPDATE deedpoll_deedpoll SET " + assignment + " WHERE id = ?", id);
            assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(ConflictError.class);
            jdbc.update("""
                UPDATE deedpoll_deedpoll SET type = 'Adult', state = 'Enroled', "publishToLondonGazette" = true,
                    "dateNoticeSentToLondonGazette" = null, "displayReference" = 'restored' WHERE id = ?
                """, id);
        }
        assertThat(noteCount(id)).isZero();
    }

    @Test
    void allRequiredFilesAndLiveOwnerAreNeededAndSendAuthorityIsGrantedOnlyOnce() {
        Reservation result = reserve(UUID.randomUUID(), ready("Adult"), ready("Minor"));
        assertThatThrownBy(() -> ledger.startSend(result.claim(), List.of(file("adult", 1))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.startSend(new Claim(result.request().id(), UUID.randomUUID()), files(result))).isFalse();
        assertThat(ledger.startSend(result.claim(), files(result))).isTrue();
        assertThat(ledger.startSend(result.claim(), files(result))).isFalse();
        assertThat(ledger.read(ADMIN, result.request().id()).state()).isEqualTo(GazetteRequestState.Sending);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_request_file WHERE batch_id = ?",
            Integer.class, result.request().id())).isEqualTo(2);
    }

    @Test
    void sentSettlementCommitsEveryDateNoteAndFrozenMembershipAndKeepsReservations() {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        assertThat(ledger.startSend(result.claim(), files(result))).isTrue();
        UUID provider = UUID.randomUUID();
        assertThat(ledger.sent(result.claim(), provider, 3)).isTrue();
        assertThat(ledger.sent(result.claim(), provider, 3)).isTrue();
        assertThat(state(result)).isEqualTo(GazetteRequestState.Sent);
        assertThat(noteCount(id)).isEqualTo(2);
        assertThat(noticeDate(id)).isNotNull();
        assertThat(reservations(id)).isOne();
        cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Corrected")));
        assertThat(jdbc.queryForObject("SELECT reference_number FROM deedpoll_gazette_notice_batch_case "
            + "WHERE batch_id = ?", String.class, result.request().id())).isEqualTo("reference-" + id);
        assertThatThrownBy(() -> cases.removeDeedPoll(id)).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(ConflictError.class);
    }

    @Test
    void definitePreSendFailureReleasesCasesButKeepsIdentityAndDeletionEvidence() {
        String id = ready("Adult");
        UUID key = UUID.randomUUID();
        Reservation result = reserve(key, id);
        assertThat(ledger.preparationFailed(result.claim())).isTrue();
        assertThat(ledger.preparationFailed(result.claim())).isFalse();
        assertThat(state(result)).isEqualTo(GazetteRequestState.NotSent);
        assertThat(reservations(id)).isZero();
        assertThat(reserve(key, id).claim()).isNull();
        assertThat(noteCount(id)).isEqualTo(2);
        assertThatThrownBy(() -> cases.removeDeedPoll(id)).isInstanceOf(ConflictError.class);
        assertThat(reserve(UUID.randomUUID(), id).claim()).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 429})
    void onlyKnownProviderRefusalsReleaseAStartedRequest(int status) {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        ledger.startSend(result.claim(), files(result));
        assertThat(ledger.providerRefused(result.claim(), status)).isTrue();
        assertThat(ledger.startSend(result.claim(), files(result))).isFalse();
        assertThat(state(result)).isEqualTo(GazetteRequestState.NotSent);
        assertThat(reservations(id)).isZero();
        assertThat(noticeDate(id)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 408, 500, 502})
    void ambiguousFailuresCannotBeRecordedAsSafeRefusals(int status) {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        ledger.startSend(result.claim(), files(result));
        assertThatThrownBy(() -> ledger.providerRefused(result.claim(), status))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(ledger.uncertain(result.claim())).isTrue();
        assertThat(ledger.uncertain(result.claim())).isFalse();
        assertThat(reservations(id)).isOne();
        assertThat(ledger.startSend(result.claim(), files(result))).isFalse();
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(ConflictError.class);
        assertThat(noteCount(id)).isEqualTo(2);
        cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Corrected")));
    }

    @Test
    void expiredPreparationReleasesButExpiredSendRetainsAndLateReceiptNeverMarksSent() {
        String before = ready("Adult");
        Reservation preparing = reserve(UUID.randomUUID(), before);
        age(preparing);
        assertThat(ledger.startSend(preparing.claim(), files(preparing))).isFalse();
        assertThat(ledger.expire(preparing.request().id())).isTrue();
        assertThat(ledger.expire(preparing.request().id())).isFalse();
        assertThat(reservations(before)).isZero();
        String after = ready("Adult");
        Reservation sending = reserve(UUID.randomUUID(), after);
        ledger.startSend(sending.claim(), files(sending));
        age(sending);
        UUID provider = UUID.randomUUID();
        assertThat(ledger.sent(sending.claim(), provider, 2)).isFalse();
        assertThat(state(sending)).isEqualTo(GazetteRequestState.Uncertain);
        assertThat(ledger.read(ADMIN, sending.request().id()).providerNotificationId()).isEqualTo(provider);
        assertThat(ledger.sent(sending.claim(), provider, 2)).isFalse();
        assertThatThrownBy(() -> ledger.sent(sending.claim(), UUID.randomUUID(), 2)).isInstanceOf(ConflictError.class);
        assertThat(reservations(after)).isOne();
        assertThat(noticeDate(after)).isNull();
        assertThat(noteCount(after)).isEqualTo(2);
    }

    @Test
    void patchAndDeleteAreRefusedWhilePreparingAndSending() {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Unsafe"))))
                .isInstanceOf(ConflictError.class).hasMessageContaining("in progress");
            assertThatThrownBy(() -> cases.removeDeedPoll(id)).isInstanceOf(ConflictError.class);
            ledger.startSend(result.claim(), files(result));
        }
    }

    @Test
    void anAuditNoteFailureRollsBackAdmission() {
        String id = ready("Adult");
        failNotes();
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(RuntimeException.class);
        assertThat(reservations(id)).isZero();
        assertThat(noteCount(id)).isZero();
    }

    @Test
    void failedSuccessSettlementRetainsSendStartAndRollsBackAllCasesAuditAndReceipt() {
        String one = ready("Adult");
        String two = ready("Minor");
        Reservation result = reserve(UUID.randomUUID(), one, two);
        ledger.startSend(result.claim(), files(result));
        failNotes();
        assertThatThrownBy(() -> ledger.sent(result.claim(), UUID.randomUUID(), 1))
            .isInstanceOf(RuntimeException.class);
        assertThat(state(result)).isEqualTo(GazetteRequestState.Sending);
        assertThat(ledger.read(ADMIN, result.request().id()).providerNotificationId()).isNull();
        assertThat(noticeDate(one)).isNull();
        assertThat(noticeDate(two)).isNull();
        assertThat(noteCount(one)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_notice_batch WHERE id = ?",
            Integer.class, result.request().id())).isZero();
        assertThat(ledger.startSend(result.claim(), files(result))).isFalse();
    }

    @Test
    void failedFailureNoteDoesNotReleaseAReservation() {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        failNotes();
        assertThatThrownBy(() -> ledger.preparationFailed(result.claim())).isInstanceOf(RuntimeException.class);
        assertThat(state(result)).isEqualTo(GazetteRequestState.Preparing);
        assertThat(reservations(id)).isOne();
    }

    @Test
    void sameKeyRaceGrantsExactlyOneClaimAndOverlappingKeysHaveOnlyOneWinner() throws Exception {
        String id = ready("Adult");
        UUID key = UUID.randomUUID();
        List<Reservation> same = race(() -> reserve(key, id), () -> reserve(key, id));
        assertThat(same.stream().filter(row -> row.claim() != null).count()).isOne();
        assertThat(same.get(0).request().id()).isEqualTo(same.get(1).request().id());
        assertThat(noteCount(id)).isOne();
        String contested = ready("Adult");
        List<String> distinct = race(() -> outcome(contested), () -> outcome(contested));
        assertThat(distinct).containsExactlyInAnyOrder("reserved", "conflict");
        assertThat(noteCount(contested)).isOne();
    }

    @Test
    void concurrentSendStartGrantsOneAuthorityAndAcceptanceWritesOneSentMarker() throws Exception {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        assertThat(race(() -> ledger.startSend(result.claim(), files(result)),
            () -> ledger.startSend(result.claim(), files(result)))).containsExactlyInAnyOrder(true, false);
        UUID receipt = UUID.randomUUID();
        assertThat(race(() -> ledger.sent(result.claim(), receipt, 1), () -> ledger.sent(result.claim(), receipt, 1)))
            .containsExactly(true, true);
        assertThat(noteCount(id)).isEqualTo(2);
        assertThat(noticeDate(id)).isNotNull();
        assertThat(reservations(id)).isOne();
    }

    @Test
    void concurrentRecoveryWritesOnlyOneTerminalNote() throws Exception {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        ledger.startSend(result.claim(), files(result));
        age(result);
        assertThat(race(() -> ledger.expire(result.request().id()), () -> ledger.expire(result.request().id())))
            .containsExactlyInAnyOrder(true, false);
        assertThat(noteCount(id)).isEqualTo(2);
        assertThat(state(result)).isEqualTo(GazetteRequestState.Uncertain);
    }

    @Test
    void committedEditIsRecheckedAfterWaitingForTheCaseLock() throws Exception {
        String id = ready("Adult");
        CountDownLatch changed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch starting = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            final var edit = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("publishToLondonGazette", false)));
                changed.countDown();
                await(release);
                return true;
            }));
            await(changed);
            var reservation = pool.submit(() -> {
                starting.countDown();
                return outcome(id);
            });
            await(starting);
            try {
                assertThatThrownBy(() -> reservation.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            assertThat(edit.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(reservation.get(10, TimeUnit.SECONDS)).isEqualTo("conflict");
        } finally {
            release.countDown();
        }
        assertThat(reservations(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"edit", "delete"})
    void admissionCommitsBeforeAWaitingCaseWriterChecksTheGuard(String operation) throws Exception {
        String id = ready("Adult");
        jdbc.execute("""
            CREATE FUNCTION gazette_test_note_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN PERFORM pg_advisory_xact_lock(88441599); RETURN NEW; END $$
            """);
        jdbc.execute("CREATE TRIGGER gazette_test_note_failure BEFORE INSERT ON deedpoll_notes "
            + "FOR EACH ROW EXECUTE FUNCTION gazette_test_note_failure()");
        try (var blocker = jdbc.getDataSource().getConnection(); var statement = blocker.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(88441599)");
            try (var pool = Executors.newFixedThreadPool(2)) {
                try {
                    final var admission = pool.submit(() -> reserve(UUID.randomUUID(), id));
                    waitForBlockedAdmission();
                    CountDownLatch started = new CountDownLatch(1);
                    var writer = pool.submit(() -> {
                        started.countDown();
                        try {
                            if (operation.equals("edit")) {
                                cases.updateDeedPoll(id, DeedPollPatch.of(Map.of("newForename", "Unsafe")));
                            } else {
                                cases.removeDeedPoll(id);
                            }
                            return "changed";
                        } catch (ConflictError expected) {
                            return "conflict";
                        }
                    });
                    await(started);
                    assertThatThrownBy(() -> writer.get(150, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                    statement.execute("SELECT pg_advisory_unlock(88441599)");
                    assertThat(admission.get(10, TimeUnit.SECONDS).claim()).isNotNull();
                    assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo("conflict");
                } finally {
                    statement.execute("SELECT pg_advisory_unlock(88441599)");
                }
            }
        }
        assertThat(reservations(id)).isOne();
        assertThat(noteCount(id)).isOne();
    }

    private void waitForBlockedAdmission() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE objid = 88441599 AND NOT granted)", Boolean.class))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Admission did not reach its transactional note insert");
    }

    @Test
    void ledgerWillNotJoinACallerTransactionOrOverwriteLegacyEvidence() {
        String id = ready("Adult");
        assertThatThrownBy(() -> new TransactionTemplate(transactions)
            .execute(status -> reserve(UUID.randomUUID(), id))).isInstanceOf(IllegalStateException.class);
        UUID batch = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch VALUES (?, now(), ?, 'Legacy', ?, 1)",
            batch, SUBJECT, key);
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch_case VALUES (?, ?, 'legacy-reference')", batch, id);
        assertThatThrownBy(() -> reserve(key, id)).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> reserve(UUID.randomUUID(), id)).isInstanceOf(ConflictError.class);
    }

    @Test
    void databaseRefusesDuplicateKeysReservationsInvalidStatesAndDeletion() {
        String id = ready("Adult");
        Reservation result = reserve(UUID.randomUUID(), id);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO deedpoll_gazette_reservation "
            + "(deed_poll_id, request_batch_id) VALUES (?, ?)", id, result.request().id()))
            .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_gazette_request SET state = 'Sent' WHERE id = ?",
            result.request().id())).isInstanceOf(DataAccessException.class);
        Reservation other = reserve(UUID.randomUUID(), ready("Adult"));
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_gazette_request SET request_id = ? WHERE id = ?",
            result.request().requestId(), other.request().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
            "UPDATE deedpoll_gazette_request SET state = 'NotSent', settled_at = now() WHERE id = ?",
            result.request().id())).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = ?", id))
            .isInstanceOf(DataAccessException.class);
    }

    private Reservation reserve(UUID key, String... ids) {
        String token = tokens.computeIfAbsent(key, ignored -> {
            List<GazetteInput> inputs = new ArrayList<>();
            for (String id : ids) {
                var row = caseRecords.findById(id).orElse(null);
                if (row != null && row.getType() != null && switch (row.getType()) {
                        case Adult, AdultTransgender, Minor, MinorTransgender -> true;
                        default -> false;
                    }) {
                    inputs.add(GazetteInput.from(row));
                }
            }
            return GazetteConfirmation.issue(inputs, AuditClock.now().plusMinutes(30).withNano(0));
        });
        return ledger.admit(ADMIN, key, List.of(ids), token, 1);
    }

    private String ready(String type) {
        String id = UUID.randomUUID().toString();
        fixtureCases.add(id);
        jdbc.update("""
            INSERT INTO deedpoll_deedpoll (id, "modifiedOn", type, state, "dateEnroled", "displayReference")
            VALUES (?, now(), ?::"DeedPollType", 'Enroled', '2026-01-01', ?)
            """, id, type, "reference-" + id);
        for (int count = 0; count < 2; count++) {
            jdbc.update("""
                INSERT INTO deedpoll_witness
                    (id, "deedPollId", "witnessName", "witnessAddress", "witnessOccupation", declarant, "modifiedOn")
                VALUES (?, ?, 'Witness', 'Address', 'Occupation', false, now())
                """, UUID.randomUUID().toString(), id);
        }
        return id;
    }

    private void age(Reservation row) {
        jdbc.update("UPDATE deedpoll_gazette_request SET submitted_at = now() - interval '10 minutes', "
            + "lease_until = now() - interval '5 minutes' WHERE id = ?", row.request().id());
    }

    private GazetteRequestState state(Reservation row) {
        return ledger.read(ADMIN, row.request().id()).state();
    }

    private int noteCount(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_notes WHERE \"deedPollId\" = ?", Integer.class, id);
    }

    private int reservations(String id) {
        return jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_reservation WHERE deed_poll_id = ?",
            Integer.class, id);
    }

    private Object noticeDate(String id) {
        return jdbc.queryForObject("SELECT \"dateNoticeSentToLondonGazette\" FROM deedpoll_deedpoll WHERE id = ?",
            Object.class, id);
    }

    private static GazetteFileEvidence file(String kind, int count) {
        return new GazetteFileEvidence(kind, count, 200, "0".repeat(64));
    }

    private static List<GazetteFileEvidence> files(Reservation row) {
        List<GazetteFileEvidence> result = new ArrayList<>();
        if (row.request().adultCount() > 0) {
            result.add(file("adult", row.request().adultCount()));
        }
        if (row.request().minorCount() > 0) {
            result.add(file("minor", row.request().minorCount()));
        }
        return result;
    }

    private void failNotes() {
        jdbc.execute("""
            CREATE FUNCTION gazette_test_note_failure() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN RAISE EXCEPTION 'synthetic note failure'; END $$
            """);
        jdbc.execute("CREATE TRIGGER gazette_test_note_failure BEFORE INSERT ON deedpoll_notes "
            + "FOR EACH ROW EXECUTE FUNCTION gazette_test_note_failure()");
    }

    private String outcome(String id) {
        try {
            reserve(UUID.randomUUID(), id);
            return "reserved";
        } catch (ConflictError expected) {
            return "conflict";
        }
    }

    private static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                await(start);
                return first.call();
            });
            var two = pool.submit(() -> {
                await(start);
                return second.call();
            });
            start.countDown();
            return List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
