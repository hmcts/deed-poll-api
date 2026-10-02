package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "deedpoll.documents.staging.max-total-bytes=10485760")
class DeedPreparationsTest extends AbstractPostgresContainerTest {
    @Autowired
    private DeedPreparations preparations;
    @Autowired
    private DeedPollService service;
    @Autowired
    private DeedPollRepository cases;
    @Autowired
    private DeedPollSupportingDocumentRepository documents;
    @Autowired
    private DeedDocumentService deeds;
    @Autowired
    private DeedGenerator generator;
    @Autowired
    private StagedPayloads payloads;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String id : created) {
            jdbc.update("""
                DELETE FROM deedpoll_stagedpayload WHERE id IN
                    (SELECT "documentId" FROM deedpoll_deedpreparation WHERE "deedPollId" = ?)
                """, id);
            cases.deleteById(id);
        }
    }

    @Test
    void preparationLogsContainNoBytesChecksumOrApplicantName() {
        String id = linkedCase();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"newForename\" = 'SensitiveApplicantValue' WHERE id = ?", id);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
            "uk.gov.hmcts.reform.deedpoll");
        var logs = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        logs.start();
        logger.addAppender(logs);
        try {
            PreparedDeed prepared = preparations.prepare(id, DeedTemplate.ADULT);
            preparations.content(id, prepared.documentId());
            assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
                .doesNotContain(prepared.checksum(), "SensitiveApplicantValue", "<w:document"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void originalIdentityAndBytesSurviveEditsAndAnotherServiceInstance() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.ADULT);
        byte[] original = preparations.content(id, first.documentId());
        assertThat(StagedPayloads.checksum(original)).isEqualTo(first.checksum());
        jdbc.update("UPDATE deedpoll_deedpoll SET \"newForename\" = 'Changed' WHERE id = ?", id);
        var restarted = new DeedPreparations(cases, documents, deeds, generator, payloads, jdbc, transactions);
        assertThat(restarted.prepare(id, DeedTemplate.ADULT)).isEqualTo(first);
        assertThat(restarted.content(id, first.documentId())).isEqualTo(original);
        original[0] = 0;
        assertThat(preparations.content(id, first.documentId())[0]).isNotZero();
        var row = documents.findById(first.documentId()).orElseThrow();
        assertThat(row.getStorageState()).isEqualTo(DocumentStorageState.UploadPending);
        assertThat(row.getStorageUploadAttempts()).isZero();
        assertThat(row.getCdamHashToken()).isNull();
    }

    @Test
    void independentRequestsCannotReserveTwoDeeds() throws Exception {
        String id = linkedCase();
        List<Object> results = race(() -> preparations.prepare(id, DeedTemplate.ADULT),
            () -> preparations.prepare(id, DeedTemplate.ADULT));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).hasSize(1);
    }

    @Test
    void competingTemplatesYieldOneReservationAndOneRefusal() throws Exception {
        String id = linkedCase();
        List<Object> results = race(() -> prepareOrRefusal(id, DeedTemplate.ADULT),
            () -> prepareOrRefusal(id, DeedTemplate.MINOR));
        assertThat(results.stream().filter(PreparedDeed.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ConflictError.class::isInstance)).hasSize(1);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).hasSize(1);
    }

    @Test
    void failedOrUncertainPreparationKeepsBytesAndDoesNotRegenerate() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.ADULT);
        byte[] original = preparations.content(id, first.documentId());
        for (String state : List.of("Failed", "UploadUncertain")) {
            jdbc.update("UPDATE deedpoll_supportingdocument SET \"storageState\" = ? WHERE id = ?",
                state, first.documentId());
            assertThat(preparations.prepare(id, DeedTemplate.ADULT)).isEqualTo(first);
            assertThat(preparations.content(id, first.documentId())).isEqualTo(original);
        }
    }

    @Test
    void deletedOrRefiledDocumentDoesNotGrantPermissionToRegenerate() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.ADULT);
        jdbc.update("UPDATE deedpoll_supportingdocument SET direction = 'Incoming' WHERE id = ?", first.documentId());
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        documents.deleteById(first.documentId());
        assertThatThrownBy(() -> deeds.storeDeed(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        assertThat(preparations.content(id, first.documentId())).isNotEmpty();
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).isEmpty();
    }

    @Test
    void unavailablePayloadAndWrongCaseNeverRegenerate() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.ADULT);
        assertThatThrownBy(() -> preparations.content(linkedCase(), first.documentId()))
            .isInstanceOf(ConflictError.class);
        jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", first.documentId());
        assertThatThrownBy(() -> preparations.content(id, first.documentId())).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).hasSize(1);
    }

    @Test
    void attachedLegacyDeedIsReusedWithoutInventingProvenance() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.MINOR);
        jdbc.update("DELETE FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?", id);
        jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", first.documentId());
        jdbc.update("""
            UPDATE deedpoll_supportingdocument SET "storageState" = 'Attached',
                "cdamDocumentId" = gen_random_uuid(), "storageKey" = 'legacy' WHERE id = ?
            """, first.documentId());
        PreparedDeed reused = preparations.prepare(id, DeedTemplate.MINOR);
        assertThat(reused.documentId()).isEqualTo(first.documentId());
        assertThat(reused.checksum()).isNull();
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
    }

    @Test
    void duplicateLegacyEvidenceIsRefusedAndKept() {
        String id = linkedCase();
        PreparedDeed first = preparations.prepare(id, DeedTemplate.ADULT);
        jdbc.update("""
            INSERT INTO deedpoll_supportingdocument
                (id, "deedPollId", direction, name, "documentTypeId", "createdOn", "modifiedOn")
            SELECT gen_random_uuid(), "deedPollId", 'Incoming', name, "documentTypeId", now(), now()
              FROM deedpoll_supportingdocument WHERE id = ?
            """, first.documentId());
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT))
            .isInstanceOf(ConflictError.class).hasMessageContaining("multiple");
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).hasSize(2);
    }

    @Test
    void absentCapacityRollsBackDocumentTypePayloadAndReservation() {
        String id = linkedCase();
        var disabled = new DeedPreparations(cases, documents, deeds, generator,
            new StagedPayloads(jdbc, 0), jdbc, transactions);
        assertThatThrownBy(() -> disabled.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?",
            Long.class, id)).isZero();
    }

    @Test
    void quotaRaceAdmitsOnlyOneAndCountsRetainedUncertainBytes() throws Exception {
        String first = linkedCase();
        String second = linkedCase();
        long capacity = Math.max(generator.generate(cases.findById(first).orElseThrow(), DeedTemplate.ADULT).size(),
            generator.generate(cases.findById(second).orElseThrow(), DeedTemplate.ADULT).size());
        long alreadyUsed = jdbc.queryForObject("SELECT coalesce(sum(\"sizeBytes\"), 0) FROM deedpoll_stagedpayload",
            Long.class);
        var bounded = new DeedPreparations(cases, documents, deeds, generator,
            new StagedPayloads(jdbc, capacity + alreadyUsed), jdbc, transactions);
        List<Object> results = race(() -> reserveOrRefusal(bounded, first), () -> reserveOrRefusal(bounded, second));
        assertThat(results.stream().filter(PreparedDeed.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ConflictError.class::isInstance)).hasSize(1);
        PreparedDeed retained = results.stream().filter(PreparedDeed.class::isInstance)
            .map(PreparedDeed.class::cast).findFirst().orElseThrow();
        jdbc.update("UPDATE deedpoll_supportingdocument SET \"storageState\" = 'UploadUncertain' WHERE id = ?",
            retained.documentId());
        assertThat(reserveOrRefusal(bounded, linkedCase())).isInstanceOf(ConflictError.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_stagedpayload WHERE id = ?",
            Long.class, retained.documentId())).isOne();
    }

    @Test
    void rejectsUnlinkedCaseAndAnOuterTransaction() {
        String id = linkedCase();
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = NULL WHERE id = ?", id);
        assertThatThrownBy(() -> preparations.prepare(id, DeedTemplate.ADULT)).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
            preparations.prepare(id, DeedTemplate.ADULT))).isInstanceOf(IllegalStateException.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(id)).isEmpty();
    }

    private Object prepareOrRefusal(String id, DeedTemplate template) {
        try {
            return preparations.prepare(id, template);
        } catch (ConflictError refused) {
            return refused;
        }
    }

    private static Object reserveOrRefusal(DeedPreparations bounded, String id) {
        try {
            return bounded.prepare(id, DeedTemplate.ADULT);
        } catch (ConflictError refused) {
            return refused;
        }
    }

    private static List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(first, second).stream().map(work -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Race did not start");
                }
                return work.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private String linkedCase() {
        String id = service.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", "2026-07-01T00:00:00.000Z")))
            .deedPoll().getId();
        created.add(id);
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?",
            String.valueOf(1000000000000000L + Math.abs((long) id.hashCode())), id);
        return id;
    }
}
