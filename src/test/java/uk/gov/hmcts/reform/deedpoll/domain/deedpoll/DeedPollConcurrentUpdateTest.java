package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollWitness;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollWitnessRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferenceCounterRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that two caseworkers patching the same case at once do not overwrite each other.
 *
 * <p><strong>Why this needs its own test against a real database.</strong>
 * {@link DeedPollService#updateDeedPoll} is a read-modify-write -- it loads the case, overlays the patch onto
 * what it read, derives {@code state} from that overlay, and writes back the whole row, because the entity
 * has no dirty-column tracking. Under two concurrent patches that shape loses data twice over: the second
 * {@code UPDATE} restores every column to the value it read before the first commit, and the {@code state} it
 * derived was computed from an overlay that did not contain the first write. Neither loss raises anything --
 * both patches return 200 with the record each of them believes it wrote.
 *
 * <p>It cannot be observed anywhere else in the suite. Every other test of the write path runs one
 * transaction at a time, and a single-threaded test passes whether or not the row is locked; the defect first
 * appears in an environment where two caseworkers have the same case open.
 *
 * <p>Both tests run the schedule {@link #applyConcurrently} describes -- both transactions read before either
 * commits, and the second commits last -- which is the interleaving that makes the loss possible. Removing the
 * lock from {@link DeedPollRepository#findByIdForUpdate} fails both of them.
 *
 * <p>Not {@code @Transactional}: the point is what two separate committed transactions leave behind.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class DeedPollConcurrentUpdateTest extends AbstractPostgresContainerTest {

    /** createDeedPoll cannot allocate a reference without a Date Received to derive the year. */
    private static final String RECEIVED = "2026-07-01T00:00:00.000Z";

    private static final String ENROLED = "2026-08-01T00:00:00.000Z";

    private static final int TIMEOUT_SECONDS = 60;

    /** How long the first transaction stays open after the second has said it is on its way to the row. */
    private static final long OVERLAP_MILLIS = 500;

    @Autowired
    private DeedPollService deedPollService;

    @Autowired
    private DeedPollRepository deedPolls;

    @Autowired
    private DeedPollWitnessRepository witnesses;

    @Autowired
    private ReferenceCounterRepository referenceCounters;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clearTables() {
        witnesses.deleteAll();
        deedPolls.deleteAll();
        referenceCounters.deleteAll();
    }

    private static DeedPollPatch patch(Object... keysAndValues) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return DeedPollPatch.of(values);
    }

    private String createdCase() {
        return deedPollService
            .createDeedPoll(patch("type", DeedPollType.Adult, "dateReceived", RECEIVED))
            .deedPoll()
            .getId();
    }

    /**
     * A case carrying the two witnesses an enrolment needs, so that the patch which sets
     * {@code dateEnroled} -- and the concurrent patch that leaves it in place -- get as far as the row.
     * The gate itself is {@code DeedPollService.requireWitnessesForEnrolment}; here it is a precondition,
     * not the claim.
     */
    private String witnessedCase() {
        String caseId = createdCase();
        DeedPoll parent = deedPolls.findById(caseId).orElseThrow();
        witnesses.saveAll(List.of(witness(parent, "Sam Doe"), witness(parent, "Ada Wren")));
        return caseId;
    }

    private static DeedPollWitness witness(DeedPoll parent, String name) {
        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(parent);
        witness.setWitnessName(name);
        witness.setWitnessAddress("1 High St");
        witness.setWitnessOccupation("Teacher");
        return witness;
    }

    @Test
    @DisplayName("two concurrent patches of different fields both survive")
    void concurrentPatchesOfDifferentFieldsBothSurvive() throws Exception {
        String caseId = createdCase();

        applyConcurrently(caseId, patch("newForename", "Ada"), patch("newSurname", "Lovelace"));

        DeedPoll stored = deedPolls.findById(caseId).orElseThrow();
        // The claim in full: neither write was silently discarded, though the two patches name different
        // columns and neither is in conflict with the other. Read without a lock, the second patch's full-row
        // UPDATE restores newForename to the null it read, and answers 200 having thrown the first away.
        assertThat(stored.getNewForename()).isEqualTo("Ada");
        assertThat(stored.getNewSurname()).isEqualTo("Lovelace");
    }

    @Test
    @DisplayName("a patch derives state from the writes that committed before it, not from a stale read")
    void derivesStateFromTheCommittedRowRatherThanAStaleRead() throws Exception {
        String caseId = witnessedCase();

        // The second patch mentions no date at all, so its derived state depends entirely on the dates it
        // reads: against a stale read it sees no enrolment date and derives Received, storing a case that is
        // enrolled and says it is not.
        applyConcurrently(caseId, patch("dateEnroled", ENROLED), patch("newSurname", "Lovelace"));

        DeedPoll stored = deedPolls.findById(caseId).orElseThrow();
        assertThat(stored.getDateEnroled()).isNotNull();
        assertThat(stored.getNewSurname()).isEqualTo("Lovelace");
        assertThat(stored.getState()).isEqualTo(DeedPollState.Enroled);
    }

    /**
     * Applies two patches to one case from two threads, on the schedule that loses a write: both transactions
     * read before either commits, and the second commits last.
     *
     * <p>The first applies its patch, waits to be told the second is on its way, then holds its transaction
     * open for {@code OVERLAP_MILLIS} before committing. The second waits for that signal, so it never starts
     * before the first has read and mutated, and then holds on for twice as long, so it always commits after
     * the first. Nothing is written to the row until each commits -- {@code save} on an already-managed entity
     * does not flush -- so with no lock the second reads the pre-patch row, commits last, and its full-row
     * {@code UPDATE} is the one that survives, with the first's edit missing from it and its own {@code state}
     * derived without it. With the lock the second cannot read until the first has committed, and there is
     * nothing stale for it to write back.
     *
     * <p>Waiting on a signal rather than sleeping blind is what keeps that order on a slow machine. The sleeps
     * cover only what no signal can report: the gap between "about to read" and "at the row", where the
     * statement that arrives is the one that blocks.
     */
    private void applyConcurrently(String caseId, DeedPollPatch first, DeedPollPatch second) throws Exception {
        CountDownLatch firstApplied = new CountDownLatch(1);
        CountDownLatch secondStarting = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstWrite = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                deedPollService.updateDeedPoll(caseId, first);
                firstApplied.countDown();
                await(secondStarting);
                sleep(OVERLAP_MILLIS);
                return null;
            }));

            Future<?> secondWrite = pool.submit(() -> {
                await(firstApplied);
                secondStarting.countDown();
                return new TransactionTemplate(transactionManager).execute(status -> {
                    deedPollService.updateDeedPoll(caseId, second);
                    sleep(2 * OVERLAP_MILLIS);
                    return null;
                });
            });

            // Surfaces either worker's exception here rather than as an assertion on an unexplained row.
            firstWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            secondWrite.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the other write never got as far as signalling");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
