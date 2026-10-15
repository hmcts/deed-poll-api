package uk.gov.hmcts.reform.deedpoll.domain.staging;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.ccd.CaseDocumentsEvent;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollPatch;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.StorageReceipt;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocumentType;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentStore;
import uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentUploads;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageStates;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = "deedpoll.documents.staging.max-total-bytes=10485760")
class OwnedStagingTest extends AbstractPostgresContainerTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private StagedPayloads payloads;
    @Autowired
    private DeedPollRepository cases;
    @Autowired
    private DeedPollSupportingDocumentRepository documents;
    @Autowired
    private DeedPollSupportingDocumentTypeRepository types;
    @Autowired
    private DeedPollService caseService;
    @Autowired
    private DocumentStorageStates states;
    @Autowired
    private uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUploadAudit uploadAudit;
    private final List<String> createdCases = new ArrayList<>();
    private final List<String> stages = new ArrayList<>();
    private final MovingClock clock = new MovingClock();
    private final UploadedFile file = UploadedFile.of(new byte[]{1, 2, 3}, "original.pdf", "application/pdf");
    private CdamDocumentStore store;
    private CaseDocumentsEvent event;
    private CorrespondenceDocumentReader reader;
    private CdamDocumentUploads remote;
    private OwnedStaging staging;
    private String typeId;

    @BeforeEach
    void setup() {
        var type = new DeedPollSupportingDocumentType();
        type.setName("Staging test");
        typeId = types.saveAndFlush(type).getId();
        store = mock(CdamDocumentStore.class);
        event = mock(CaseDocumentsEvent.class);
        reader = mock(CorrespondenceDocumentReader.class);
        remote = new CdamDocumentUploads(states, store, event);
        staging = replica(StagingPolicyTest.policy(2, 6, 2));
        when(store.save(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new StorageReceipt(UUID.randomUUID().toString(), Optional.of("transient-token"));
        });
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("""
                UPDATE deedpoll_supportingdocument SET "storageState" = 'Attached', "cdamHashToken" = NULL
                WHERE "deedPollId" IN (SELECT id FROM deedpoll_deedpoll WHERE "ccdCaseReference" = ?)
                """, call.getArgument(0, String.class));
            return null;
        }).when(event).submit(anyString(), any());
        when(reader.downloadDocument(anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new DocumentDownload(documents.findById(call.getArgument(1, String.class)).orElseThrow(),
                new ByteArrayInputStream(file.content()));
        });
    }

    @AfterEach
    void cleanup() {
        for (String id : stages) {
            jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", id);
            jdbc.update("DELETE FROM deedpoll_ownedstage WHERE id = ?", id);
        }
        createdCases.forEach(cases::deleteById);
        types.deleteById(typeId);
    }

    @Test
    void waitingAdmissionAndPromotionDoNotHoldQuotaOrStageAheadOfTheCase() throws Exception {
        var owner = owner();
        link(owner.id());
        UUID operation = UUID.randomUUID();
        var stage = admit(staging, owner, operation);
        CountDownLatch waitingForCase = new CountDownLatch(2);
        var observedCases = mock(DeedPollRepository.class, org.mockito.AdditionalAnswers.delegatesTo(cases));
        doAnswer(call -> {
            waitingForCase.countDown();
            return cases.lockForDeedPreparation(call.getArgument(0));
        }).when(observedCases).lockForDeedPreparation(owner.id());
        var replica = new OwnedStaging(jdbc, transactions, StagingPolicyTest.policy(2, 6, 2), payloads,
            observedCases, documents, types, Optional.of(remote), reader, uploadAudit, clock);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var requests = new ArrayList<java.util.concurrent.Future<?>>();
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                cases.lockForDeedPreparation(owner.id()).orElseThrow();
                requests.add(executor.submit(() -> replica.admit(owner, operation, file)));
                requests.add(executor.submit(() -> replica.promote(owner, stage.id(), owner.id(), typeId)));
                try {
                    assertThat(waitingForCase.await(5, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                // A deed reservation holding the case can still acquire quota; no stage -> case edge exists.
                assertThat(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(7340032, 26214400)",
                    Boolean.class)).isTrue();
                assertThat(jdbc.queryForObject("SELECT id FROM deedpoll_ownedstage WHERE id = ? FOR UPDATE NOWAIT",
                    String.class, stage.id())).isEqualTo(stage.id());
            });
            for (var request : requests) {
                request.get(5, TimeUnit.SECONDS);
            }
        }
        assertThat(state(stage.id())).isEqualTo("Consumed");
        verify(store, times(1)).save(any());
    }

    @Test
    void retainedRecordAdmissionPreventsDeletionUntilRemoved() {
        var owner = owner();
        var stage = admit(staging, owner, UUID.randomUUID());
        assertThatThrownBy(() -> caseService.removeDeedPoll(owner.id())).isInstanceOf(ConflictError.class);
        assertThat(cases.existsById(owner.id())).isTrue();
        assertThat(retained(stage.id())).isTrue();
        staging.remove(owner, stage.id());
        caseService.removeDeedPoll(owner.id());
        assertThatThrownBy(() -> staging.admit(owner, UUID.randomUUID(), file)).isInstanceOf(NotFoundError.class);
    }

    @Test
    void draftPromotionRetainsItsTargetWhileStorageIsUncertain() {
        var draft = new StageOwner(StageOwner.Kind.DRAFT, UUID.randomUUID().toString(), "subject");
        String target = newCase();
        link(target);
        var stage = admit(staging, draft, UUID.randomUUID());
        doThrow(new UpstreamFailureError("unavailable")).when(reader).downloadDocument(anyString(), anyString());
        assertThatThrownBy(() -> staging.promote(draft, stage.id(), target, typeId))
            .isInstanceOf(UpstreamFailureError.class);
        assertThatThrownBy(() -> caseService.removeDeedPoll(target)).isInstanceOf(ConflictError.class);
        assertThat(retained(stage.id())).isTrue();
    }

    @Test
    void cleanupSkipsLockedPayloadAndExpiresLaterFreeRows() {
        final var first = admit(staging, owner(), UUID.randomUUID());
        clock.advance(Duration.ofSeconds(1));
        var blocked = admit(staging, owner(), UUID.randomUUID());
        clock.advance(Duration.ofSeconds(1));
        var later = admit(staging, owner(), UUID.randomUUID());
        clock.advance(Duration.ofHours(2));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM deedpoll_stagedpayload WHERE id = ? FOR UPDATE",
                String.class, blocked.id());
            try (var executor = Executors.newSingleThreadExecutor()) {
                try {
                    assertThat(executor.submit(staging::expire).get(5, TimeUnit.SECONDS)).isEqualTo(2);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            assertThat(state(first.id())).isEqualTo("Expired");
            assertThat(retained(first.id())).isFalse();
            assertThat(state(later.id())).isEqualTo("Expired");
            assertThat(retained(later.id())).isFalse();
            assertThat(state(blocked.id())).isEqualTo("Staged");
            assertThat(retained(blocked.id())).isTrue();
        });
        assertThat(staging.expire()).isOne();
    }

    @Test
    void promotionUsesTheSharedDocumentNameLimit() {
        var owner = owner();
        link(owner.id());
        String name = "x".repeat(200) + ".pdf";
        var stage = staging.admit(owner, UUID.randomUUID(),
            UploadedFile.of(file.content(), name, file.mimeType()));
        stages.add(stage.id());
        String id = staging.promote(owner, stage.id(), owner.id(), typeId);
        var document = documents.findById(id).orElseThrow();
        assertThat(document.getName()).hasSize(
            uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentSchemas.MAX_NAME_LENGTH);
        assertThat(document.getOriginalName()).isEqualTo(name);
    }

    @Test
    void admissionSurvivesRestartAndPreservesExpiryAndImmutableContent() {
        var owner = owner();
        UUID operation = UUID.randomUUID();
        var first = admit(staging, owner, operation);
        clock.advance(Duration.ofMinutes(30));
        assertThat(replica(StagingPolicyTest.policy(2, 6, 2)).admit(owner, operation, file)).isEqualTo(first);
        assertThatThrownBy(() -> staging.admit(owner, operation,
            UploadedFile.of(new byte[]{4, 5, 6}, "original.pdf", "application/pdf"))).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> staging.admit(owner, operation,
            UploadedFile.of(file.content(), "changed.pdf", "application/pdf"))).isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_stagedpayload SET content = ? WHERE id = ?",
            new byte[]{4, 5, 6}, first.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE deedpoll_ownedstage SET \"expiresOn\" = now() WHERE id = ?",
            first.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test
    void wrongOwnerAndSubjectCannotReadRemoveOrPromote() {
        var owner = owner();
        var first = admit(staging, owner, UUID.randomUUID());
        var stranger = new StageOwner(owner.kind(), owner.id(), "different-subject");
        assertThatThrownBy(() -> staging.remove(stranger, first.id())).isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> staging.promote(stranger, first.id(), owner.id(), typeId))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> staging.promote(owner, first.id(), newCase(), typeId))
            .isInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> staging.admit(stranger, UUID.fromString(operation(first.id())), file))
            .isInstanceOf(NotFoundError.class);
        assertThat(state(first.id())).isEqualTo("Staged");
    }

    @Test
    void promotionWaitsForBindingThenReusesIdentityAcrossReplicas() {
        var owner = owner();
        var stage = admit(staging, owner, UUID.randomUUID());
        assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(ConflictError.class);
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).isEmpty();
        link(owner.id());
        String document = staging.promote(owner, stage.id(), owner.id(), typeId);
        assertThat(state(stage.id())).isEqualTo("Consumed");
        assertThat(retained(stage.id())).isFalse();
        assertThat(replica(StagingPolicyTest.policy(2, 6, 2)).promote(owner, stage.id(), owner.id(), typeId))
            .isEqualTo(document);
        verify(store, times(1)).save(any());
        assertThat(documents.findById(document).orElseThrow().getOriginalName()).isEqualTo("original.pdf");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM deedpoll_audit_event
            WHERE entity_id = ? AND action = 'DOCUMENT_UPLOADED' AND actor_id = ?
            """, Long.class, document, owner.subject())).isEqualTo(1L);
        assertThatThrownBy(() -> staging.remove(owner, stage.id())).isInstanceOf(ConflictError.class);
    }

    @Test
    void draftFixtureBindsOnceAndDoesNotEstablishCitizenAuthority() {
        var draft = new StageOwner(StageOwner.Kind.DRAFT, UUID.randomUUID().toString(), "fixture-subject");
        var stage = admit(staging, draft, UUID.randomUUID());
        String caseId = newCase();
        link(caseId);
        staging.promote(draft, stage.id(), caseId, typeId);
        assertThatThrownBy(() -> staging.promote(draft, stage.id(), newCase(), typeId))
            .isInstanceOf(ConflictError.class);
        assertThatThrownBy(() -> staging.promote(draft, stage.id(), caseId, "different-type"))
            .isInstanceOf(ConflictError.class);
    }

    @Test
    void ownerQuotaRaceAndIdempotencyRaceAreAtomic() throws Exception {
        var owner = owner();
        var bounded = replica(StagingPolicyTest.policy(1, 3, 2));
        var results = race(() -> admit(bounded, owner, UUID.randomUUID()),
            () -> admit(bounded, owner, UUID.randomUUID()));
        assertThat(results.stream().filter(OwnedStaging.Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ConflictError.class::isInstance)).hasSize(1);
        var another = owner();
        UUID operation = UUID.randomUUID();
        results = race(() -> admit(staging, another, operation), () -> admit(staging, another, operation));
        assertThat(results.get(0)).isEqualTo(results.get(1));
    }

    @Test
    void expiryBoundaryAndRemovalKeepIdempotencyEvidence() {
        var owner = owner();
        UUID operation = UUID.randomUUID();
        var stage = admit(staging, owner, operation);
        clock.advance(Duration.ofHours(1));
        link(owner.id());
        assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(ConflictError.class);
        assertThat(staging.expire()).isOne();
        assertThat(state(stage.id())).isEqualTo("Expired");
        assertThat(retained(stage.id())).isFalse();
        assertThat(staging.admit(owner, operation, file).expiresOn()).isEqualTo(stage.expiresOn());
        assertThat(staging.admit(owner, operation, file).state()).isEqualTo("Expired");
        var removed = admit(staging, owner, UUID.randomUUID());
        staging.remove(owner, removed.id());
        staging.remove(owner, removed.id());
        assertThat(state(removed.id())).isEqualTo("Removed");
    }

    @Test
    void cleanupIsBoundedAndReplicaSafe() throws Exception {
        for (int i = 0; i < 5; i++) {
            admit(staging, owner(), UUID.randomUUID());
        }
        clock.advance(Duration.ofHours(1));
        assertThat(staging.expire()).isEqualTo(2);
        var results = race(staging::expire, replica(StagingPolicyTest.policy(2, 6, 2))::expire);
        assertThat(results).allMatch(Integer.class::isInstance);
        assertThat(results.stream().mapToInt(value -> (Integer) value).sum()).isEqualTo(3);
        assertThat(stages).allSatisfy(id -> assertThat(state(id)).isEqualTo("Expired"));
    }

    @Test
    void promotionVersusRemovalCannotDiscardPromotingBytes() throws Exception {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        doThrow(new SimulatedDeath()).when(store).save(any());
        var results = race(() -> staging.promote(owner, stage.id(), owner.id(), typeId), () -> {
            staging.remove(owner, stage.id());
            return "removed";
        });
        assertThat(results).hasSize(2);
        if (state(stage.id()).equals("Removed")) {
            assertThat(retained(stage.id())).isFalse();
            assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).isEmpty();
        } else {
            assertThat(state(stage.id())).isEqualTo("Promoting");
            assertThat(retained(stage.id())).isTrue();
            assertThat(results).anyMatch(ConflictError.class::isInstance);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"upload", "attach", "accepted", "readback"})
    void crashAtEachRemoteBoundaryNeverReuploadsOrExpiresEvidence(String boundary) {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        switch (boundary) {
            case "upload" -> doThrow(new SimulatedDeath()).when(store).save(any());
            case "attach" -> doThrow(new SimulatedDeath()).when(event).submit(anyString(), any());
            case "accepted" -> doAnswer(call -> {
                jdbc.update("""
                    UPDATE deedpoll_supportingdocument SET "storageState" = 'Attached', "cdamHashToken" = NULL
                    WHERE "deedPollId" = ?
                    """, owner.id());
                throw new SimulatedDeath();
            }).when(event).submit(anyString(), any());
            case "readback" -> doThrow(new SimulatedDeath()).when(reader).downloadDocument(anyString(), anyString());
            default -> throw new AssertionError(boundary);
        }
        assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(SimulatedDeath.class);
        clock.advance(Duration.ofDays(2));
        assertThat(staging.expire()).isZero();
        assertThat(retained(stage.id())).isTrue();
        assertThatThrownBy(() -> staging.remove(owner, stage.id())).isInstanceOf(ConflictError.class);
        var restarted = replica(StagingPolicyTest.policy(2, 6, 2));
        if (boundary.equals("accepted")) {
            restarted.promote(owner, stage.id(), owner.id(), typeId);
            assertThat(retained(stage.id())).isFalse();
        } else {
            assertThatThrownBy(() -> restarted.promote(owner, stage.id(), owner.id(), typeId))
                .isInstanceOfAny(ConflictError.class, SimulatedDeath.class);
        }
        verify(store, times(1)).save(any());
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).hasSize(1);
    }

    @Test
    void failedReadbackKeepsBytesAndCountsThemAgainstQuota() {
        var owner = owner();
        link(owner.id());
        var bounded = replica(StagingPolicyTest.policy(1, 3, 2));
        var stage = admit(bounded, owner, UUID.randomUUID());
        doAnswer(call -> new DocumentDownload(
            documents.findById(call.getArgument(1, String.class)).orElseThrow(),
            new ByteArrayInputStream(new byte[]{9, 9, 9})))
            .when(reader).downloadDocument(anyString(), anyString());
        assertThatThrownBy(() -> bounded.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(UpstreamFailureError.class);
        assertThat(retained(stage.id())).isTrue();
        assertThatThrownBy(() -> admit(bounded, owner, UUID.randomUUID())).isInstanceOf(ConflictError.class);
    }

    @Test
    void crashAfterReservationBeforeUploadCanResumeTheSameDocument() {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        var stopped = mock(CdamDocumentUploads.class);
        doThrow(new SimulatedDeath()).when(stopped).continuePrepared(anyString(), anyString(), any());
        var beforeClaim = new OwnedStaging(jdbc, transactions, StagingPolicyTest.policy(2, 6, 2),
            payloads, cases, documents, types, Optional.of(stopped), reader, uploadAudit, clock);
        assertThatThrownBy(() -> beforeClaim.promote(owner, stage.id(), owner.id(), typeId))
            .isInstanceOf(SimulatedDeath.class);
        var reserved = documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id()).getFirst();
        assertThat(reserved.getStorageUploadAttempts()).isZero();
        clock.advance(Duration.ofDays(1));
        assertThat(staging.expire()).isZero();
        assertThat(staging.promote(owner, stage.id(), owner.id(), typeId)).isEqualTo(reserved.getId());
        verify(store, times(1)).save(any());
    }

    @Test
    void failedConsumptionCommitKeepsPayloadUntilVerifiedRetry() {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        jdbc.execute("""
            CREATE FUNCTION staging_test_refuse_consume() RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.state = 'Consumed' THEN RAISE EXCEPTION 'simulated commit failure'; END IF;
                RETURN NEW;
            END; $$;
            CREATE CONSTRAINT TRIGGER staging_test_consume AFTER UPDATE ON deedpoll_ownedstage
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION staging_test_refuse_consume();
            """);
        try {
            assertThatThrownBy(() -> staging.promote(owner, stage.id(), owner.id(), typeId))
                .isInstanceOf(RuntimeException.class);
            assertThat(state(stage.id())).isEqualTo("Promoting");
            assertThat(retained(stage.id())).isTrue();
        } finally {
            jdbc.execute("DROP TRIGGER staging_test_consume ON deedpoll_ownedstage");
            jdbc.execute("DROP FUNCTION staging_test_refuse_consume()");
        }
        staging.promote(owner, stage.id(), owner.id(), typeId);
        verify(store, times(1)).save(any());
        assertThat(retained(stage.id())).isFalse();
    }

    @Test
    void expiryCannotDeleteWhilePromotionHoldsTheStageLock() throws Exception {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT id FROM deedpoll_ownedstage WHERE id = ? FOR UPDATE",
                String.class, stage.id());
            clock.advance(Duration.ofHours(1));
            try (var executor = Executors.newSingleThreadExecutor()) {
                try {
                    assertThat(executor.submit(staging::expire).get(5, TimeUnit.SECONDS)).isZero();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
            assertThat(retained(stage.id())).isTrue();
        });
        assertThat(staging.expire()).isOne();
    }

    @Test
    void parallelPromotionsHaveOneUploadClaim() throws Exception {
        var owner = owner();
        link(owner.id());
        var stage = admit(staging, owner, UUID.randomUUID());
        race(() -> staging.promote(owner, stage.id(), owner.id(), typeId),
            () -> replica(StagingPolicyTest.policy(2, 6, 2)).promote(owner, stage.id(), owner.id(), typeId));
        assertThat(state(stage.id())).isEqualTo("Consumed");
        assertThat(documents.findByDeedPollIdOrderByCreatedOnAsc(owner.id())).hasSize(1);
        verify(store, times(1)).save(any());
    }

    @Test
    void sharedGlobalCapCountsGeneratedPreparationBytes() throws Exception {
        String preparation = UUID.randomUUID().toString();
        stages.add(preparation);
        new TransactionTemplate(transactions).executeWithoutResult(status -> payloads.retain(preparation, new byte[3]));
        long used = jdbc.queryForObject("SELECT sum(\"sizeBytes\") FROM deedpoll_stagedpayload", Long.class);
        var bounded = new OwnedStaging(jdbc, transactions, StagingPolicyTest.policy(2, 6, 2),
            new StagedPayloads(jdbc, used + 3), cases, documents, types, Optional.of(remote), reader,
            uploadAudit, clock);
        var first = owner();
        var second = owner();
        var results = race(() -> admit(bounded, first, UUID.randomUUID()),
            () -> admit(bounded, second, UUID.randomUUID()));
        assertThat(results.stream().filter(OwnedStaging.Receipt.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(ConflictError.class::isInstance)).hasSize(1);
        assertThat(retained(preparation)).isTrue();
    }

    @Test
    void cleanupStopsAtByteBudgetAndLeavesPreparationsAlone() {
        var policy = new StagingPolicy(true, 50000000, 5, 50000000, 50000000, Duration.ofHours(1),
            Duration.ofHours(2), 60000, 10, 26214400, Duration.ofSeconds(10));
        var bounded = new OwnedStaging(jdbc, transactions, policy, new StagedPayloads(jdbc, 50000000),
            cases, documents, types, Optional.of(remote), reader, uploadAudit, clock);
        var owner = owner();
        for (int i = 0; i < 3; i++) {
            var receipt = bounded.admit(owner, UUID.randomUUID(),
                UploadedFile.of(new byte[10000000], "large.pdf", "application/pdf"));
            stages.add(receipt.id());
        }
        String preparation = UUID.randomUUID().toString();
        stages.add(preparation);
        new TransactionTemplate(transactions).executeWithoutResult(status ->
            new StagedPayloads(jdbc, 50000000).retain(preparation, new byte[3]));
        clock.advance(Duration.ofDays(1));
        assertThat(bounded.expire()).isEqualTo(2);
        assertThat(retained(preparation)).isTrue();
        assertThat(bounded.expire()).isOne();
    }

    @Test
    void draftCountAndOwnerByteLimitsAreIndependent() {
        var draft = new StageOwner(StageOwner.Kind.DRAFT, UUID.randomUUID().toString(), "fixture-subject");
        var bounded = replica(StagingPolicyTest.policy(100, 100, 2));
        for (int i = 0; i < 10; i++) {
            admit(bounded, draft, UUID.randomUUID());
        }
        assertThatThrownBy(() -> admit(bounded, draft, UUID.randomUUID())).isInstanceOf(ConflictError.class);
        var record = owner();
        var bytesOnly = replica(StagingPolicyTest.policy(100, 3, 2));
        admit(bytesOnly, record, UUID.randomUUID());
        assertThatThrownBy(() -> admit(bytesOnly, record, UUID.randomUUID())).isInstanceOf(ConflictError.class);
    }

    @Test
    void transactionAndDisabledGuardsRunBeforeAnyWork() {
        var owner = owner();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status ->
            staging.admit(owner, UUID.randomUUID(), file))).isInstanceOf(IllegalStateException.class);
        var disabled = replica(new StagingPolicy(false, 0, 0, 0, 0, null, null, 0, 0, 0, null));
        assertThatThrownBy(() -> disabled.admit(owner, UUID.randomUUID(), file)).isInstanceOf(ConflictError.class);
    }

    private OwnedStaging replica(StagingPolicy policy) {
        return new OwnedStaging(jdbc, transactions, policy, payloads, cases, documents, types,
            Optional.of(remote), reader, uploadAudit, clock);
    }

    private StageOwner owner() {
        return new StageOwner(StageOwner.Kind.RECORD, newCase(), "authenticated-subject");
    }

    private String newCase() {
        String id = caseService.createDeedPoll(DeedPollPatch.of(Map.of("dateReceived", "2026-07-01T00:00:00.000Z")))
            .deedPoll().getId();
        createdCases.add(id);
        return id;
    }

    private void link(String id) {
        jdbc.update("UPDATE deedpoll_deedpoll SET \"ccdCaseReference\" = ? WHERE id = ?",
            String.valueOf(1000000000000000L + Math.abs((long) id.hashCode())), id);
    }

    private OwnedStaging.Receipt admit(OwnedStaging target, StageOwner owner, UUID operation) {
        var receipt = target.admit(owner, operation, file);
        synchronized (stages) {
            if (!stages.contains(receipt.id())) {
                stages.add(receipt.id());
            }
        }
        return receipt;
    }

    private String state(String id) {
        return jdbc.queryForObject("SELECT state FROM deedpoll_ownedstage WHERE id = ?", String.class, id);
    }

    private String operation(String id) {
        return jdbc.queryForObject("SELECT \"operationId\" FROM deedpoll_ownedstage WHERE id = ?", String.class, id);
    }

    private boolean retained(String id) {
        return jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM deedpoll_stagedpayload WHERE id = ?)",
            Boolean.class, id);
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
                try {
                    return work.call();
                } catch (ConflictError | SimulatedDeath expected) {
                    return expected;
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private static class SimulatedDeath extends Error {
        private static final long serialVersionUID = 1L;
    }

    private static class MovingClock extends Clock {
        private Instant now = Instant.parse("2026-10-02T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
