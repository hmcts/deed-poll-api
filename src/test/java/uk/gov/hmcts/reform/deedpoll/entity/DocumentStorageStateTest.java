package uk.gov.hmcts.reform.deedpoll.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageRule;

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
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.AttachUncertain;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Attached;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Failed;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Local;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.UploadPending;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.UploadUncertain;
import static uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Uploaded;

/**
 * A document's storage lifecycle and its recovery rule, with no database in it.
 *
 * <p>Container-free on purpose, as {@link CcdCreationAttemptStatusTest} is: the migration and its backfill are proved
 * against Postgres in {@code DocumentStorageMigrationTest}, which Docker-free CI does not run. Asserted exhaustively
 * over {@code values() x values()}, so a state added without a decision about where it may go fails here.
 */
class DocumentStorageStateTest {

    private static final Path V25 = Path.of("src/main/resources/db/migration/V25__document_storage_state.sql");

    /** The whole rule, restated as a table so a change to it is a change to this test too. */
    private static final Map<DocumentStorageState, Set<DocumentStorageState>> ALLOWED = Map.of(
        Local, EnumSet.noneOf(DocumentStorageState.class),
        UploadPending, EnumSet.of(UploadUncertain, Uploaded, Failed),
        UploadUncertain, EnumSet.of(UploadPending),
        Uploaded, EnumSet.of(AttachUncertain, Attached, Failed),
        AttachUncertain, EnumSet.of(Attached),
        Attached, EnumSet.noneOf(DocumentStorageState.class),
        Failed, EnumSet.noneOf(DocumentStorageState.class));

    @Test
    @DisplayName("allows exactly the moves the table lists, and refuses every other pair")
    void allowsExactlyTheListedMoves() {
        assertThat(ALLOWED).containsOnlyKeys(DocumentStorageState.values());
        for (DocumentStorageState from : DocumentStorageState.values()) {
            for (DocumentStorageState to : DocumentStorageState.values()) {
                assertThat(from.canMoveTo(to))
                    .as("%s -> %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
            }
        }
    }

    @ParameterizedTest
    @EnumSource(DocumentStorageState.class)
    @DisplayName("no state may stay where it is by a move")
    void noSelfMoves(DocumentStorageState state) {
        assertThat(state.canMoveTo(state)).isFalse();
    }

    @Test
    @DisplayName("nothing moves out of Attached or Local")
    void attachedAndLocalAreTerminal() {
        assertThat(Attached.successors()).isEmpty();
        assertThat(Local.successors()).isEmpty();
    }

    @Test
    @DisplayName("nothing moves into Local, so a CDAM document is never reported as on the filesystem")
    void nothingBecomesLocal() {
        for (DocumentStorageState from : DocumentStorageState.values()) {
            assertThat(from.canMoveTo(Local)).as("%s -> Local", from).isFalse();
        }
    }

    @Test
    @DisplayName("once an attach may have landed, the only way on is a confirmed attach")
    void anUncertainAttachNeverFailsOrUploadsAgain() {
        // Failed means nothing is stored against the case; an attach that may have landed cannot say that.
        assertThat(reachableFrom(AttachUncertain, 0)).containsExactly(Attached);
    }

    @Test
    @DisplayName("an uncertain upload is sent again only while fewer than two uploads have been sent")
    void anUncertainUploadIsRetriedOnce() {
        assertThat(DocumentStorageRule.MAX_UPLOAD_ATTEMPTS).isEqualTo(2);
        assertThat(DocumentStorageRule.mayUploadAgain(UploadUncertain, 1)).isTrue();
        assertThat(DocumentStorageRule.mayUploadAgain(UploadUncertain, 2)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = DocumentStorageState.class, names = "UploadUncertain", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("no other state is ever uploaded again")
    void onlyAnUncertainUploadIsUploadedAgain(DocumentStorageState state) {
        assertThat(DocumentStorageRule.mayUploadAgain(state, 0)).isFalse();
        assertThat(DocumentStorageRule.mayUploadAgain(state, 1)).isFalse();
    }

    @Test
    @DisplayName("UploadUncertain -> UploadPending is the only way back to an upload, and it is the conditional edge")
    void theOnlyWayBackToAnUploadIsTheConditionalRetry() {
        for (DocumentStorageState from : DocumentStorageState.values()) {
            if (from.canMoveTo(UploadPending)) {
                assertThat(from).isEqualTo(UploadUncertain);
            }
        }
    }

    @Test
    @DisplayName("with the cap taken into account, an uncertain upload can reach the upload at most once more")
    void theCapStopsASecondRetry() {
        // A row counts each upload before it is sent. After the second, the walk from UploadUncertain goes nowhere.
        assertThat(reachableFrom(UploadUncertain, DocumentStorageRule.MAX_UPLOAD_ATTEMPTS)).isEmpty();
        assertThat(reachableFrom(UploadUncertain, 1)).contains(UploadPending, Uploaded, Attached);
    }

    @Test
    @DisplayName("an attach is sent from Uploaded, and sent again after a lost answer, and from nowhere else")
    void attachIsSentOnlyFromUploadedOrAnUncertainAttach() {
        for (DocumentStorageState state : DocumentStorageState.values()) {
            assertThat(DocumentStorageRule.mayAttach(state))
                .as("%s", state)
                .isEqualTo(state == Uploaded || state == AttachUncertain);
        }
    }

    @Test
    @DisplayName("only Local and Attached are stored, so an uncertain or failed document is never served")
    void onlyLocalAndAttachedAreStored() {
        assertThat(Arrays.stream(DocumentStorageState.values()).filter(DocumentStorageState::isStored))
            .containsExactly(Local, Attached);
    }

    @Test
    @DisplayName("an uncertain upload is the one state reported as needing attention")
    void anUncertainUploadNeedsAttention() {
        assertThat(Arrays.stream(DocumentStorageState.values()).filter(DocumentStorageState::needsAttention))
            .containsExactly(UploadUncertain);
    }

    @Test
    @DisplayName("the hash token is held exactly while an attach is still to be confirmed")
    void theHashTokenIsHeldOnlyWhileAttaching() {
        assertThat(Arrays.stream(DocumentStorageState.values()).filter(DocumentStorageState::holdsHashToken))
            .containsExactly(Uploaded, AttachUncertain);
    }

    @Test
    @DisplayName("V25's CHECK lists the Java enum's members, in the same order")
    void theMigrationListsTheSameStates() throws IOException {
        String sql = Files.readString(V25);
        String marker = "CHECK (\"storageState\" IN (";
        int start = sql.indexOf(marker) + marker.length();
        String body = sql.substring(start, sql.indexOf("))", start));

        assertThat(Arrays.stream(body.split(",")).map(member -> member.strip().replace("'", "")))
            .containsExactlyElementsOf(Arrays.stream(DocumentStorageState.values()).map(Enum::name).toList());
    }

    @Test
    @DisplayName("V25's CHECKs state the same hash-token predicate and upload cap the Java does")
    void theMigrationStatesTheSameTokenRuleAndCap() throws IOException {
        String sql = Files.readString(V25);

        assertThat(sql).contains(
            "CHECK ((\"cdamHashToken\" IS NOT NULL) = (\"storageState\" IN ('Uploaded', 'AttachUncertain')))");
        assertThat(sql).contains(
            "CHECK (\"storageUploadAttempts\" BETWEEN 0 AND " + DocumentStorageRule.MAX_UPLOAD_ATTEMPTS + ")");
    }

    /**
     * Every state reachable from {@code start}, taking the conditional {@code UploadUncertain -> UploadPending} edge
     * only while {@link DocumentStorageRule#mayUploadAgain} allows it, and counting each upload that edge sends.
     */
    private static Set<DocumentStorageState> reachableFrom(DocumentStorageState start, int uploadAttempts) {
        Set<DocumentStorageState> seen = EnumSet.noneOf(DocumentStorageState.class);
        Deque<Visit> todo = new ArrayDeque<>();
        todo.push(new Visit(start, uploadAttempts));
        while (!todo.isEmpty()) {
            Visit visit = todo.pop();
            for (DocumentStorageState next : visit.state().successors()) {
                int attempts = visit.uploadAttempts();
                if (next == UploadPending) {
                    if (!DocumentStorageRule.mayUploadAgain(visit.state(), attempts)) {
                        continue;
                    }
                    attempts++;
                }
                if (seen.add(next)) {
                    todo.push(new Visit(next, attempts));
                }
            }
        }
        return seen;
    }

    private record Visit(DocumentStorageState state, int uploadAttempts) {
    }
}
