package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.repository.RequestBudgetRepository.Spend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequestBudgetRepository#spend} against a real Postgres: the upsert's quoting, its window reset and its row
 * lock cannot be observed anywhere else. Each test uses its own subjects, so the tests share no rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class RequestBudgetRepositoryTest extends AbstractPostgresContainerTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Instant NOON = Instant.parse("2026-10-16T12:00:00Z");
    private static final int THREADS = 8;
    private static final int SPENDS_PER_THREAD = 25;
    private static final int TIMEOUT_SECONDS = 60;

    @Autowired
    private RequestBudgetRepository budgets;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String subject() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("the count increments within a window, and the window's end is the truncated start plus the window")
    void incrementsWithinAWindow() {
        String subject = subject();

        Spend first = budgets.spend(subject, "search", NOON.plusSeconds(5), MINUTE);
        Spend second = budgets.spend(subject, "search", NOON.plusSeconds(30), MINUTE);
        Spend third = budgets.spend(subject, "search", NOON.plusSeconds(59).plusMillis(999), MINUTE);

        assertThat(List.of(first.count(), second.count(), third.count())).containsExactly(1, 2, 3);
        assertThat(List.of(first.windowEnd(), second.windowEnd(), third.windowEnd()))
            .containsOnly(NOON.plus(MINUTE));
    }

    @Test
    @DisplayName("a new window starts the count again at 1")
    void resetsInANewWindow() {
        String subject = subject();
        budgets.spend(subject, "search", NOON, MINUTE);
        budgets.spend(subject, "search", NOON.plusSeconds(10), MINUTE);

        Spend next = budgets.spend(subject, "search", NOON.plus(MINUTE), MINUTE);
        Spend later = budgets.spend(subject, "search", NOON.plus(Duration.ofHours(3)).plusSeconds(1), MINUTE);

        assertThat(next).isEqualTo(new Spend(1, NOON.plus(MINUTE).plus(MINUTE)));
        assertThat(later).isEqualTo(new Spend(1, NOON.plus(Duration.ofHours(3)).plus(MINUTE)));
    }

    @Test
    @DisplayName("an hour window is aligned to the hour, so every replica computes the same window")
    void alignsTheWindowToItsLength() {
        Spend spend = budgets.spend(subject(), "email-send", NOON.plus(Duration.ofMinutes(47)), Duration.ofHours(1));

        assertThat(spend.windowEnd()).isEqualTo(NOON.plus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("two subjects and two buckets are counted independently")
    void keepsSubjectsAndBucketsApart() {
        String one = subject();
        final String other = subject();
        budgets.spend(one, "search", NOON, MINUTE);
        budgets.spend(one, "search", NOON, MINUTE);

        assertThat(budgets.spend(one, "upload", NOON, MINUTE).count()).isEqualTo(1);
        assertThat(budgets.spend(other, "search", NOON, MINUTE).count()).isEqualTo(1);
        assertThat(budgets.spend(one, "search", NOON, MINUTE).count()).isEqualTo(3);
    }

    @Test
    @DisplayName("a window shorter than a millisecond is refused rather than dividing by zero")
    void refusesAnEmptyWindow() {
        // @Repository's exception translation wraps the IllegalArgumentException; the cause is the refusal.
        assertThatThrownBy(() -> budgets.spend(subject(), "search", NOON, Duration.ZERO))
            .isInstanceOf(InvalidDataAccessApiUsageException.class)
            .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a second transaction on the same row waits for the first and counts on top of it")
    void waitsForAnUncommittedSpend() throws Exception {
        String subject = subject();
        CountDownLatch firstSpent = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            final Future<Integer> first = pool.submit(() -> transaction.execute(status -> {
                int count = budgets.spend(subject, "search", NOON, MINUTE).count();
                firstSpent.countDown();
                await(releaseFirst);
                return count;
            }));
            assertThat(firstSpent.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            Future<Integer> second = pool.submit(() -> budgets.spend(subject, "search", NOON, MINUTE).count());
            // Still blocked on the first transaction's row lock: it has not been handed a count yet.
            Thread.sleep(500);
            assertThat(second.isDone()).isFalse();

            releaseFirst.countDown();
            assertThat(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("concurrent spends on one row lose no update")
    void losesNoUpdateUnderConcurrency() throws Exception {
        String subject = subject();
        List<Integer> counts = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            List<Future<?>> workers = new ArrayList<>();
            for (int thread = 0; thread < THREADS; thread++) {
                workers.add(pool.submit(() -> {
                    for (int i = 0; i < SPENDS_PER_THREAD; i++) {
                        counts.add(budgets.spend(subject, "search", NOON, MINUTE).count());
                    }
                }));
            }
            for (Future<?> worker : workers) {
                worker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        int total = THREADS * SPENDS_PER_THREAD;
        assertThat(counts).containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, total).boxed().toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the first transaction");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
