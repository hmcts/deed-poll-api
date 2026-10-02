package uk.gov.hmcts.reform.deedpoll.domain.staging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUpload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentTypeRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentUploads;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageRule;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Internal seam: adapters must authenticate owner and authorise its target case before calling. */
@Service
public class OwnedStaging {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StagingPolicy policy;
    private final StagedPayloads payloads;
    private final DeedPollRepository cases;
    private final DeedPollSupportingDocumentRepository documents;
    private final DeedPollSupportingDocumentTypeRepository types;
    private final Optional<CdamDocumentUploads> uploads;
    private final CorrespondenceDocumentReader reader;
    private final Clock clock;

    @Autowired
    public OwnedStaging(JdbcTemplate jdbc, PlatformTransactionManager transactions, StagingPolicy policy,
                        StagedPayloads payloads, DeedPollRepository cases,
                        DeedPollSupportingDocumentRepository documents,
                        DeedPollSupportingDocumentTypeRepository types, Optional<CdamDocumentUploads> uploads,
                        CorrespondenceDocumentReader reader) {
        this(jdbc, transactions, policy, payloads, cases, documents, types, uploads, reader, Clock.systemUTC());
    }

    public OwnedStaging(JdbcTemplate jdbc, PlatformTransactionManager transactions, StagingPolicy policy,
                        StagedPayloads payloads, DeedPollRepository cases,
                        DeedPollSupportingDocumentRepository documents,
                        DeedPollSupportingDocumentTypeRepository types, Optional<CdamDocumentUploads> uploads,
                        CorrespondenceDocumentReader reader, Clock clock) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.policy = policy;
        this.payloads = payloads;
        this.cases = cases;
        this.documents = documents;
        this.types = types;
        this.uploads = uploads;
        this.reader = reader;
        this.clock = clock;
        if (policy.enabled() && uploads.isEmpty()) {
            throw new IllegalArgumentException("Owned staging requires CDAM storage");
        }
    }

    public record Receipt(String id, Instant expiresOn, String state) { }

    private record Stage(String id, String originalName, String mimeType, int size, String checksum,
                         Instant expiry, String state, String caseId, String documentId, String typeId) {
        Receipt receipt() {
            return new Receipt(id, expiry, state);
        }

        @Override
        public String toString() {
            return "Stage[redacted]";
        }
    }

    public Receipt admit(StageOwner owner, UUID operation, UploadedFile submitted) {
        outsideTransaction();
        Objects.requireNonNull(operation);
        policy.validate(owner, submitted);
        UploadedFile file = UploadedFile.of(submitted.content().clone(),
            submitted.originalName(), submitted.mimeType());
        return transaction.execute(status -> {
            // Same lock as generated preparations: neither global nor owner quotas can oversubscribe.
            jdbc.execute("SELECT pg_advisory_xact_lock(7340032, 26214400)");
            List<String> existing = jdbc.query("""
                SELECT id FROM deedpoll_ownedstage WHERE "ownerKind" = ? AND "ownerId" = ? AND "operationId" = ?
                """, (row, index) -> row.getString(1), owner.kind().name(), owner.id(), operation);
            if (!existing.isEmpty()) {
                Stage stage = lock(owner, existing.getFirst());
                if (!stage.originalName().equals(file.originalName()) || !stage.mimeType().equals(file.mimeType())
                    || stage.size() != file.size()
                    || !stage.checksum().equals(StagedPayloads.checksum(file.content()))) {
                    throw conflict();
                }
                return stage.receipt();
            }
            if (owner.kind() == StageOwner.Kind.RECORD && !cases.existsById(owner.id())) {
                throw missing();
            }
            long[] used = jdbc.queryForObject("""
                SELECT count(*), coalesce(sum(s."sizeBytes"), 0) FROM deedpoll_ownedstage s
                JOIN deedpoll_stagedpayload p ON p.id = s.id WHERE s."ownerKind" = ? AND s."ownerId" = ?
                """, (row, index) -> new long[]{row.getLong(1), row.getLong(2)}, owner.kind().name(), owner.id());
            if (used[0] >= policy.maxFiles(owner) || file.size() > policy.maxBytes(owner)
                || used[1] > policy.maxBytes(owner) - file.size()) {
                throw new ConflictError("The owner's staging capacity has been reached");
            }
            String id = UUID.randomUUID().toString();
            String checksum = payloads.retain(id, file.content());
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            Instant expiry = now.plus(policy.expiry(owner));
            jdbc.update("""
                INSERT INTO deedpoll_ownedstage
                    (id, "ownerKind", "ownerId", subject, "operationId", "originalName", "mimeType", "sizeBytes",
                     checksum, "createdOn", "expiresOn", state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'Staged')
                """, id, owner.kind().name(), owner.id(), owner.subject(), operation,
                file.originalName(), file.mimeType(),
                file.size(), checksum, Timestamp.from(now), Timestamp.from(expiry));
            return new Receipt(id, expiry, "Staged");
        });
    }

    /** A durable draft-to-case assertion must come from the owning application, never an upload request. */
    public String promote(StageOwner owner, String stageId, String caseId, String typeId) {
        outsideTransaction();
        if (owner.kind() == StageOwner.Kind.RECORD && !owner.id().equals(caseId)) {
            throw missing();
        }
        Stage stage = transaction.execute(status -> reserve(owner, stageId, caseId, typeId));
        if (stage.state().equals("Consumed")) {
            return stage.documentId();
        }
        List<byte[]> retained = jdbc.query("SELECT content FROM deedpoll_stagedpayload WHERE id = ?",
            (row, index) -> row.getBytes(1), stage.id());
        if (retained.isEmpty()) {
            // Another replica may have completed read-back between reservation and this read.
            Stage current = transaction.execute(status -> lock(owner, stageId));
            if (current.state().equals("Consumed")) {
                return current.documentId();
            }
            throw conflict();
        }
        byte[] bytes = retained.getFirst();
        DocumentUpload upload = DocumentUpload.of(caseId, typeId,
            UploadedFile.of(bytes, stage.originalName(), stage.mimeType()), Optional.empty(),
            Optional.of(DocumentDirection.Incoming));
        uploads.orElseThrow().continuePrepared(caseId, stage.documentId(), upload);
        verifyAndConsume(owner, stage);
        return stage.documentId();
    }

    private Stage reserve(StageOwner owner, String id, String caseId, String typeId) {
        Stage stage = lock(owner, id);
        if (!stage.state().equals("Staged")) {
            if ((stage.state().equals("Promoting") || stage.state().equals("Consumed"))
                && caseId.equals(stage.caseId()) && Objects.equals(typeId, stage.typeId())) {
                return stage;
            }
            throw conflict();
        }
        if (!clock.instant().isBefore(stage.expiry())) {
            throw conflict();
        }
        cases.lockForDeedPreparation(caseId).orElseThrow(OwnedStaging::missing);
        var parent = cases.findById(caseId).orElseThrow(OwnedStaging::missing);
        DocumentStorageRule.requireCaseReady(Optional.ofNullable(parent.getCcdCaseReference()));
        var type = types.findById(typeId).orElseThrow(OwnedStaging::missing);
        var document = new DocumentUpload(caseId, typeId,
            UploadedFile.of(new byte[0], stage.originalName(), stage.mimeType()),
            stage.originalName().substring(0, Math.min(100, stage.originalName().length())), DocumentDirection.Incoming)
            .newRow(parent, type);
        document.setSizeBytes(stage.size());
        document.setStorageState(DocumentStorageState.UploadPending);
        documents.saveAndFlush(document);
        jdbc.update("""
            UPDATE deedpoll_ownedstage SET state = 'Promoting', "caseId" = ?, "documentId" = ?,
                "documentTypeId" = ? WHERE id = ?
            """, caseId, document.getId(), typeId, id);
        return lock(owner, id);
    }

    private void verifyAndConsume(StageOwner owner, Stage stage) {
        var download = reader.downloadDocument(stage.caseId(), stage.documentId());
        try (var stream = download.stream()) {
            byte[] bytes = stream.readNBytes(stage.size() + 1);
            if (bytes.length != stage.size() || !StagedPayloads.checksum(bytes).equals(stage.checksum())) {
                throw new UpstreamFailureError("Stored upload verification failed; staging bytes were kept");
            }
        } catch (IOException failure) {
            throw new UpstreamFailureError("Stored upload verification failed; staging bytes were kept");
        }
        transaction.executeWithoutResult(status -> {
            Stage current = lock(owner, stage.id());
            var document = documents.findByIdAndDeedPollIdForUpdate(stage.documentId(), stage.caseId())
                .orElseThrow(OwnedStaging::missing);
            if (document.getStorageState() != DocumentStorageState.Attached
                || !Objects.equals(document.getCdamDocumentId(), download.document().getCdamDocumentId())) {
                throw conflict();
            }
            if (current.state().equals("Promoting")) {
                jdbc.update("UPDATE deedpoll_ownedstage SET state = 'Consumed' WHERE id = ?", stage.id());
                jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", stage.id());
            }
        });
    }

    public void remove(StageOwner owner, String id) {
        outsideTransaction();
        transaction.executeWithoutResult(status -> {
            Stage stage = lock(owner, id);
            if (stage.state().equals("Removed") || stage.state().equals("Expired")) {
                return;
            }
            if (!stage.state().equals("Staged")) {
                throw conflict();
            }
            discard(id, "Removed");
        });
    }

    /** Each deletion commits separately. SKIP LOCKED coordinates schedulers and promotion/removal. */
    public int expire() {
        outsideTransaction();
        long started = System.nanoTime();
        long bytes = 0;
        int rows = 0;
        while (rows < policy.cleanupRows() && System.nanoTime() - started < policy.cleanupTime().toNanos()) {
            long budget = policy.cleanupBytes() - bytes;
            long remainingMs = Math.max(1,
                (policy.cleanupTime().toNanos() - (System.nanoTime() - started)) / 1000000);
            Integer removed = transaction.execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '" + remainingMs + "ms'");
                jdbc.execute("SET LOCAL lock_timeout = '100ms'");
                List<String> ids = jdbc.query("""
                    SELECT id FROM deedpoll_ownedstage WHERE state = 'Staged' AND "expiresOn" <= ?
                        AND "sizeBytes" <= ? ORDER BY "expiresOn", id LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, index) -> row.getString(1), Timestamp.from(clock.instant()), budget);
                if (ids.isEmpty()) {
                    return 0;
                }
                int size = jdbc.queryForObject("SELECT \"sizeBytes\" FROM deedpoll_ownedstage WHERE id = ?",
                    Integer.class, ids.getFirst());
                discard(ids.getFirst(), "Expired");
                return size;
            });
            if (removed == 0) {
                break;
            }
            bytes += removed;
            rows++;
        }
        return rows;
    }

    private void discard(String id, String state) {
        jdbc.update("UPDATE deedpoll_ownedstage SET state = ? WHERE id = ?", state, id);
        jdbc.update("DELETE FROM deedpoll_stagedpayload WHERE id = ?", id);
    }

    private Stage lock(StageOwner owner, String id) {
        List<Stage> found = jdbc.query("""
            SELECT * FROM deedpoll_ownedstage WHERE id = ? AND "ownerKind" = ? AND "ownerId" = ?
                AND subject = ? FOR UPDATE
            """, (row, index) -> new Stage(row.getString("id"),
                row.getString("originalName"), row.getString("mimeType"),
                row.getInt("sizeBytes"), row.getString("checksum"), row.getTimestamp("expiresOn").toInstant(),
                row.getString("state"), row.getString("caseId"), row.getString("documentId"),
                row.getString("documentTypeId")), id, owner.kind().name(), owner.id(), owner.subject());
        if (found.isEmpty()) {
            throw missing();
        }
        return found.getFirst();
    }

    private void outsideTransaction() {
        policy.requireEnabled();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Staging requires committed steps outside any caller transaction");
        }
    }

    private static ConflictError conflict() {
        return new ConflictError("The stage cannot be changed or promoted; retained evidence must be reviewed");
    }

    private static NotFoundError missing() {
        return new NotFoundError("Owned stage or target not found");
    }
}
