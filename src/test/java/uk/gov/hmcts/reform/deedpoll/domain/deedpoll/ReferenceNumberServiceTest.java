package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports the allocating blocks of {@code reference/ts/deed-poll/reference-number.test.ts} --
 * {@code allocateSerial} and {@code allocateForCreate} -- and adds the concurrency proof the plan
 * requires. The pure blocks are {@link ReferenceNumbersTest}.
 *
 * <p><strong>Against a real database, not a mocked transaction client.</strong> The reference stubs
 * the Prisma client and asserts the arguments it was called with, which is all a TypeScript unit test
 * can do. What makes this allocator correct is a Postgres row lock, and a mock cannot hold one -- so
 * these tests run against the throwaway Postgres and assert the serials that actually come back.
 *
 * <p>The class is deliberately NOT {@code @Transactional}: the concurrency tests need real committing
 * transactions in more than one thread, and a test-managed rollback-only transaction would make the
 * lock they are proving unobservable. The counter table is cleared around each test instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferenceNumberServiceTest extends AbstractPostgresContainerTest {

    private static final int YEAR = 2026;
    private static final int PARALLEL_ALLOCATIONS = 8;
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired
    private ReferenceNumberService referenceNumberService;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    @AfterEach
    void clearCounters() {
        referenceCounters.deleteAll();
    }

    /** Runs a unit of work in its own committing transaction, as a caseworker request would. */
    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    @Test
    @DisplayName("seeds a new year at 1")
    void seedsANewYearAtOne() {
        // The reference asserts the upsert's arguments (create lastSerial 1, else increment); here the
        // observable consequence is asserted instead, which is the same claim without the mock.
        assertThat(inTransaction(() -> referenceNumberService.allocateSerial(YEAR))).isEqualTo(1);
    }

    @Test
    @DisplayName("returns the incremented serial for an existing year")
    void returnsTheIncrementedSerialForAnExistingYear() {
        assertThat(inTransaction(() -> referenceNumberService.allocateSerial(YEAR))).isEqualTo(1);
        assertThat(inTransaction(() -> referenceNumberService.allocateSerial(YEAR))).isEqualTo(2);
        assertThat(inTransaction(() -> referenceNumberService.allocateSerial(YEAR))).isEqualTo(3);
    }

    @Test
    @DisplayName("the counter is shared across DP and DR within a year, and separate between years")
    void theCounterIsSharedWithinAYearAndSeparateBetweenYears() {
        // The reference documents this ("shared per-year counter across DP and DR") but, having mocked
        // the counter, cannot assert it.
        String relinquishment = inTransaction(() -> referenceNumberService.allocateForCreate(
            DeedPollType.DeedOfRelinquishment, LocalDateTime.of(YEAR, 5, 1, 0, 0))).displayReference();
        String deedPoll = inTransaction(() -> referenceNumberService.allocateForCreate(
            DeedPollType.Adult, LocalDateTime.of(YEAR, 6, 1, 0, 0))).displayReference();
        String nextYear = inTransaction(() -> referenceNumberService.allocateForCreate(
            DeedPollType.Adult, LocalDateTime.of(YEAR + 1, 1, 1, 0, 0))).displayReference();

        assertThat(relinquishment).isEqualTo("KBDR2026-00001");
        assertThat(deedPoll).as("the DR consumed serial 1, so the DP gets 2").isEqualTo("KBDP2026-00002");
        assertThat(nextYear).as("a new year starts its own sequence").isEqualTo("KBDP2027-00001");
    }

    @Test
    @DisplayName("allocateForCreate derives year, allocates serial, sets prefix from type")
    void allocateForCreateDerivesYearAllocatesSerialAndSetsPrefix() {
        // Two allocations first, so the third is serial 3 -- the value the reference's mock returns.
        inTransaction(() -> referenceNumberService.allocateSerial(YEAR));
        inTransaction(() -> referenceNumberService.allocateSerial(YEAR));

        ReferenceNumberService.AllocatedReference allocated = inTransaction(() ->
            referenceNumberService.allocateForCreate(
                DeedPollType.DeedOfRelinquishment, LocalDateTime.of(2026, 5, 1, 0, 0)));

        assertThat(allocated).isEqualTo(new ReferenceNumberService.AllocatedReference(3, "KBDR2026-00003"));
    }

    @Test
    @DisplayName("a create with no Date Received is refused, and consumes no serial")
    void createWithNoDateReceivedIsRefusedAndConsumesNoSerial() {
        assertThatThrownBy(() -> inTransaction(() ->
            referenceNumberService.allocateForCreate(DeedPollType.Adult, null)))
            .isInstanceOf(InvalidInputError.class);

        // The year is derived before the counter is touched, so the refusal leaves no gap in an
        // external-facing, permanent sequence.
        assertThat(referenceCounters.findAll()).isEmpty();
    }

    @Test
    @DisplayName("allocation demands the caller's transaction rather than opening its own")
    void allocationDemandsTheCallersTransaction() {
        // The serial must be allocated under the same transaction as the row that will carry it, so a
        // caller who forgot to open one is told immediately instead of silently burning serials.
        assertThatThrownBy(() -> referenceNumberService.allocateSerial(YEAR))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(referenceCounters.findAll()).isEmpty();
    }

    @Test
    @DisplayName("parallel allocations never return the same serial")
    void parallelAllocationsNeverReturnTheSameSerial() throws Exception {
        // The source Power Automate flow read the current maximum reference number and added one,
        // which hands concurrent creates the same number. This is the test that would have caught it.
        CyclicBarrier allThreadsReady = new CyclicBarrier(PARALLEL_ALLOCATIONS);
        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_ALLOCATIONS);
        try {
            List<Future<Integer>> allocations = new ArrayList<>();
            for (int i = 0; i < PARALLEL_ALLOCATIONS; i++) {
                allocations.add(executor.submit(() -> inTransaction(() -> {
                    awaitBarrier(allThreadsReady);
                    return referenceNumberService.allocateSerial(YEAR);
                })));
            }

            List<Integer> serials = new ArrayList<>();
            for (Future<Integer> allocation : allocations) {
                serials.add(allocation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            }

            assertThat(serials)
                .as("%d concurrent allocations, no duplicates and no gaps", PARALLEL_ALLOCATIONS)
                .containsExactlyInAnyOrderElementsOf(rangeOfSerials());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("a second allocation waits for the first transaction to commit")
    void secondAllocationWaitsForTheFirstTransactionToCommit() throws Exception {
        // Distinctness alone could be luck. This asserts the row lock itself: the second allocation
        // cannot return until the first transaction has committed, which is what stops the two seeing
        // the same counter value in the first place.
        CountDownLatch firstHasAllocated = new CountDownLatch(1);
        AtomicBoolean firstIsAboutToCommit = new AtomicBoolean(false);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = executor.submit(() -> inTransaction(() -> {
                final int serial = referenceNumberService.allocateSerial(YEAR);
                firstHasAllocated.countDown();
                sleep(300);
                // Set inside the transaction, so it is true before the commit that releases the lock.
                firstIsAboutToCommit.set(true);
                return serial;
            }));

            assertThat(firstHasAllocated.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            int second = inTransaction(() -> referenceNumberService.allocateSerial(YEAR));
            boolean firstHadCommitted = firstIsAboutToCommit.get();

            assertThat(firstHadCommitted)
                .as("the second allocation returned before the first committed, so it did not wait")
                .isTrue();
            assertThat(second).isEqualTo(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) + 1);
        } finally {
            executor.shutdownNow();
        }
    }

    private static List<Integer> rangeOfSerials() {
        List<Integer> expected = new ArrayList<>();
        for (int serial = 1; serial <= PARALLEL_ALLOCATIONS; serial++) {
            expected.add(serial);
        }
        return expected;
    }

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Threads did not reach the barrier together", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the counter's row lock", e);
        }
    }
}
