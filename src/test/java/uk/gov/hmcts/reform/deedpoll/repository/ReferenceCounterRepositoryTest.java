package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts {@link ReferenceCounterRepository#allocateNextSerial(int)} against a real Postgres.
 *
 * <p><strong>Why this test has to hit a database.</strong> That statement is the only hand-written SQL in the
 * repository layer, and the two things that make it correct cannot be observed anywhere else. Its identifiers
 * are quoted because {@code "lastSerial"} is mixed case -- unquoted, Postgres folds it to {@code lastserial}
 * and the statement does not resolve -- and its atomicity comes from the row lock
 * {@code ON CONFLICT … DO UPDATE} takes. Every other test of the reference-number path mocks this repository,
 * so a quoting slip or a renamed conflict target would pass the whole suite and first be found in an
 * environment handing two cases the same reference number.
 *
 * <p>The serial is externally visible -- it is the number on a caseworker's deed poll -- so a duplicate is not
 * a defect that can be tidied up afterwards.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReferenceCounterRepositoryTest extends AbstractPostgresContainerTest {

    /** Years are chosen per test so the tests do not share counter rows, and are outside any real year. */
    private static final int FIRST_YEAR = 4001;
    private static final int SECOND_YEAR = 4002;
    private static final int CONCURRENT_YEAR = 4003;
    private static final int INDEPENDENT_YEAR = 4004;

    private static final int THREADS = 8;
    private static final int ALLOCATIONS_PER_THREAD = 25;
    private static final int TIMEOUT_SECONDS = 60;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("the first allocation of a year seeds the counter and returns 1")
    void seedsTheCounterOnTheFirstAllocationOfAYear() {
        // The case a read-then-lock cannot handle: there is no row to lock yet. The statement inserts and
        // returns in one go, so this is not a special case in the code either.
        Integer first = transaction().execute(status -> referenceCounters.allocateNextSerial(FIRST_YEAR));

        assertThat(first).isEqualTo(1);
    }

    @Test
    @DisplayName("successive allocations of a year increment, and the returned value is the serial not a row count")
    void incrementsOnEachAllocation() {
        List<Integer> allocated = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            allocated.add(transaction().execute(status -> referenceCounters.allocateNextSerial(SECOND_YEAR)));
        }

        // 1, 2, 3 rather than 1, 1, 1. Worth asserting explicitly: adding @Modifying to the query -- which
        // looks like the right annotation for an INSERT -- would make Spring Data run it as an update and
        // return the number of rows affected, which is 1 every time. Every case would then get serial 1.
        assertThat(allocated).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("counters are independent per year")
    void keepsAYearsCounterSeparateFromAnothers() {
        transaction().execute(status -> referenceCounters.allocateNextSerial(INDEPENDENT_YEAR));
        transaction().execute(status -> referenceCounters.allocateNextSerial(INDEPENDENT_YEAR));

        Integer otherYear = transaction().execute(status -> referenceCounters.allocateNextSerial(FIRST_YEAR + 100));

        assertThat(otherYear).isEqualTo(1);
    }

    @Test
    @DisplayName("concurrent allocations never hand out the same serial")
    void neverAllocatesTheSameSerialTwiceUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Integer> allocated = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Callable<Void>> workers = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                workers.add(() -> {
                    for (int i = 0; i < ALLOCATIONS_PER_THREAD; i++) {
                        // A transaction per allocation, which is the shape createDeedPoll uses: the serial is
                        // allocated inside the caller's transaction, so the lock is held to its commit.
                        allocated.add(transaction().execute(
                            status -> referenceCounters.allocateNextSerial(CONCURRENT_YEAR)));
                    }
                    return null;
                });
            }

            for (Future<Void> finished : pool.invokeAll(workers)) {
                // Surfaces a worker's exception rather than letting a failure look like a short list.
                finished.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = THREADS * ALLOCATIONS_PER_THREAD;
        // The property that matters, stated as a set: no serial appears twice, and the run covers 1..total with
        // no gaps. A read-then-write would fail this by duplicating; a lock taken too late would fail it by
        // skipping.
        assertThat(allocated).hasSize(total).doesNotHaveDuplicates();
        assertThat(allocated).containsExactlyInAnyOrderElementsOf(
            IntStream.rangeClosed(1, total).boxed().toList());
    }
}
