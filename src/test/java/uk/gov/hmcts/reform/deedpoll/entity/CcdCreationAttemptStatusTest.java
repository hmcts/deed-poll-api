package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.ccd.CcdLinkingRule;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.AwaitingConfirmation;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Claimed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Linked;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.NeedsRepair;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus.Pending;

/**
 * The CCD creation attempt's lifecycle, with no database in it.
 *
 * <p>Container-free on purpose: the conditional statements that settle races are proved against Postgres in
 * {@code CcdCreationAttemptRepositoryTest}, which Docker-free CI does not run, and the rule they carry out is the
 * part most worth CI running. Asserted exhaustively over {@code values() x values()}, so a sixth status added
 * without a decision about where it may go fails here rather than silently going nowhere.
 */
class CcdCreationAttemptStatusTest {

    private static final Path V22 = Path.of("src/main/resources/db/migration/V22__ccd_creation_attempt.sql");

    /** The whole rule, restated as a table so a change to it is a change to this test too. */
    private static final Map<CcdCreationAttemptStatus, Set<CcdCreationAttemptStatus>> ALLOWED = Map.of(
        Pending, EnumSet.of(Claimed),
        Claimed, EnumSet.of(Pending, AwaitingConfirmation, Linked, NeedsRepair),
        AwaitingConfirmation, EnumSet.of(Linked, NeedsRepair),
        NeedsRepair, EnumSet.of(AwaitingConfirmation, Pending),
        Linked, EnumSet.noneOf(CcdCreationAttemptStatus.class));

    @Test
    @DisplayName("allows exactly the moves the table lists, and refuses every other pair")
    void allowsExactlyTheListedMoves() {
        assertThat(ALLOWED).containsOnlyKeys(CcdCreationAttemptStatus.values());
        for (CcdCreationAttemptStatus from : CcdCreationAttemptStatus.values()) {
            for (CcdCreationAttemptStatus to : CcdCreationAttemptStatus.values()) {
                assertThat(from.canMoveTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(CcdCreationAttemptStatus.class)
    @DisplayName("no status may stay where it is by a move")
    void noSelfMoves(CcdCreationAttemptStatus status) {
        // A move that changes nothing would still clear the claim and overwrite the failure detail -- a way to
        // erase what another worker recorded while appearing to do nothing.
        assertThat(status.canMoveTo(status)).isFalse();
    }

    @Test
    @DisplayName("Linked is the only status that is not live, and it is terminal")
    void linkedIsTheOnlyNonLiveStatusAndIsTerminal() {
        assertThat(Arrays.stream(CcdCreationAttemptStatus.values()).filter(status -> !status.isLive()))
            .containsExactly(Linked);
        assertThat(Linked.successors()).isEmpty();
    }

    @Test
    @DisplayName("NeedsRepair is live, so it blocks a second attempt that could mint a second case")
    void needsRepairIsLive() {
        assertThat(NeedsRepair.isLive()).isTrue();
    }

    @Test
    @DisplayName("once an attempt may have been submitted, it can never get back to Pending or Claimed")
    void nothingAfterSubmissionReturnsToACreation() {
        // Recovery is by reading, not retrying: a fresh start-event token mints a fresh idempotency key, and a
        // second creation is how one record gets two CCD cases. Reachability, not just the direct edges, so a
        // route through NeedsRepair cannot reopen one either. AwaitingConfirmation means a submission may have
        // been made, so the walk takes the one conditional edge only where the rule allows it with that fact set.
        Set<CcdCreationAttemptStatus> reachable = reachableFrom(AwaitingConfirmation, true);

        assertThat(reachable).doesNotContain(Pending, Claimed);
        assertThat(reachable).contains(Linked, NeedsRepair);
    }

    @Test
    @DisplayName("every status can reach Linked, so no attempt is stuck without a way to finish")
    void everyLiveStatusCanReachLinked() {
        for (CcdCreationAttemptStatus status : CcdCreationAttemptStatus.values()) {
            if (status.isLive()) {
                assertThat(reachableFrom(status)).as("from %s", status).contains(Linked);
            }
        }
    }

    @Test
    @DisplayName("V22's live index states the same predicate isLive() does")
    void theLiveIndexMatchesIsLive() throws IOException {
        String sql = Files.readString(V22);

        assertThat(sql).contains("""
            CREATE UNIQUE INDEX "deedpoll_ccdcreationattempt_live_key"
                ON "deedpoll_ccdcreationattempt" ("deedPollId")
             WHERE "status" <> 'Linked';""");
    }

    @Test
    @DisplayName("V22's enum type has the Java enum's members, in the same order")
    void thePostgresTypeHasTheSameMembers() throws IOException {
        String sql = Files.readString(V22);
        int start = sql.indexOf("CREATE TYPE \"CcdCreationAttemptStatus\" AS ENUM (");
        String body = sql.substring(sql.indexOf('(', start) + 1, sql.indexOf(");", start));

        assertThat(Arrays.stream(body.split(",")).map(member -> member.strip().replace("'", "")))
            .containsExactlyElementsOf(Arrays.stream(CcdCreationAttemptStatus.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("NeedsRepair -> Pending is the only conditional edge, and the only way back to Pending but Claimed's")
    void theOnlyConditionalEdgeIsTheRetryFromScratch() {
        // Every edge into Pending but Claimed's -- whose caller asserts nothing was submitted -- has to be gated on
        // submissionMayHaveReached, or an attempt that may have reached CCD could be started again.
        for (CcdCreationAttemptStatus from : CcdCreationAttemptStatus.values()) {
            if (from != Claimed && from.canMoveTo(Pending)) {
                assertThat(from).isEqualTo(NeedsRepair);
                assertThat(CcdLinkingRule.mayRetryFromScratch(from, true)).as("refused once submitted").isFalse();
                assertThat(CcdLinkingRule.mayRetryFromScratch(from, false)).as("allowed before").isTrue();
            }
        }
    }

    private static Set<CcdCreationAttemptStatus> reachableFrom(CcdCreationAttemptStatus start) {
        return reachableFrom(start, false);
    }

    /**
     * Every status reachable from {@code start}, taking the conditional {@code NeedsRepair -> Pending} edge only
     * where {@link CcdLinkingRule#mayRetryFromScratch} allows it for {@code submissionMayHaveReached}.
     */
    private static Set<CcdCreationAttemptStatus> reachableFrom(CcdCreationAttemptStatus start,
                                                               boolean submissionMayHaveReached) {
        Set<CcdCreationAttemptStatus> seen = EnumSet.noneOf(CcdCreationAttemptStatus.class);
        Deque<CcdCreationAttemptStatus> todo = new ArrayDeque<>(edgesFrom(start, submissionMayHaveReached));
        while (!todo.isEmpty()) {
            CcdCreationAttemptStatus next = todo.pop();
            if (seen.add(next)) {
                todo.addAll(edgesFrom(next, submissionMayHaveReached));
            }
        }
        return seen;
    }

    private static Set<CcdCreationAttemptStatus> edgesFrom(CcdCreationAttemptStatus from,
                                                           boolean submissionMayHaveReached) {
        Set<CcdCreationAttemptStatus> edges = EnumSet.noneOf(CcdCreationAttemptStatus.class);
        edges.addAll(from.successors());
        if (from == NeedsRepair && !CcdLinkingRule.mayRetryFromScratch(from, submissionMayHaveReached)) {
            edges.remove(Pending);
        }
        return edges;
    }
}
