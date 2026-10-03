package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.storage.CorrespondenceDocumentReader;
import uk.gov.hmcts.reform.deedpoll.storage.DocumentStorageRule;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Commits one immutable deed and document identity before remote work. This seam is wired into
 * email orchestration in Task 3; a reservation is NOT a usable/attached document.
 */
@Service
public class DeedPreparations {
    private final DeedPollRepository cases;
    private final DeedPollSupportingDocumentRepository documents;
    private final DeedDocumentService deeds;
    private final DeedGenerator generator;
    private final StagedPayloads payloads;
    private final JdbcTemplate jdbc;
    private final CorrespondenceDocumentReader reader;
    private final TransactionTemplate transaction;

    public DeedPreparations(DeedPollRepository cases, DeedPollSupportingDocumentRepository documents,
                            DeedDocumentService deeds, DeedGenerator generator, StagedPayloads payloads,
                            JdbcTemplate jdbc, PlatformTransactionManager transactions,
                            CorrespondenceDocumentReader reader) {
        this.cases = cases;
        this.documents = documents;
        this.deeds = deeds;
        this.generator = generator;
        this.payloads = payloads;
        this.jdbc = jdbc;
        this.reader = reader;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Separate requests, whatever their idempotency key, reserve against the same case key. */
    public PreparedDeed prepare(String caseId, DeedTemplate template) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deed preparation must commit before remote work; no outer transaction");
        }
        return transaction.execute(status -> reserve(caseId, template));
    }

    private PreparedDeed reserve(String caseId, DeedTemplate template) {
        cases.lockForDeedPreparation(caseId).orElseThrow(() -> new NotFoundError("DeedPoll not found"));
        List<PreparedDeed> reservations = jdbc.query("""
            SELECT "documentId", template, "originalName", "mimeType", "sizeBytes", checksum
              FROM deedpoll_deedpreparation WHERE "deedPollId" = ?
            """, (row, index) -> new PreparedDeed(row.getString(1), DeedTemplate.valueOf(row.getString(2)),
                row.getString(3), row.getString(4), row.getInt(5), row.getString(6)), caseId);
        var existing = deeds.existingDeed(caseId);
        if (!reservations.isEmpty()) {
            PreparedDeed reserved = reservations.getFirst();
            DeedPollSupportingDocument document = documents.findByIdAndDeedPollId(reserved.documentId(), caseId)
                .orElseThrow(DeedPreparations::unavailable);
            deeds.requireCompatible(document, template);
            if (reserved.template() != template || existing.isEmpty()
                || !existing.get().getId().equals(reserved.documentId())
                || !reserved.originalName().equals(document.getOriginalName())
                || !reserved.mimeType().equals(document.getMimeType())
                || !Integer.valueOf(reserved.sizeBytes()).equals(document.getSizeBytes())) {
                throw unavailable();
            }
            if (document.getStorageState() != DocumentStorageState.Attached
                && !jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM deedpoll_stagedpayload WHERE id = ?)",
                    Boolean.class, reserved.documentId())) {
                throw unavailable();
            }
            // Failed/uncertain states still return preparation evidence, never a usable deed.
            return reserved;
        }
        if (existing.isPresent()) {
            DeedPollSupportingDocument document = existing.get();
            deeds.requireCompatible(document, template);
            if (document.getStorageState() != DocumentStorageState.Attached || document.getCdamDocumentId() == null
                || document.getStorageKey() == null || document.getOriginalName() == null
                || document.getMimeType() == null || document.getSizeBytes() == null) {
                // Local legacy bytes require 03c. Do not infer generation provenance or upload them here.
                throw unavailable();
            }
            return new PreparedDeed(document.getId(), template, document.getOriginalName(),
                document.getMimeType(), document.getSizeBytes(), null);
        }
        DeedPoll deedPoll = cases.findById(caseId).orElseThrow();
        if (deedPoll.getCcdCaseReference() == null) {
            throw new ConflictError(DocumentStorageRule.CASE_NOT_READY);
        }
        GeneratedDeed generated = generator.generate(deedPoll, template);
        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(deedPoll);
        document.setDocumentType(deeds.typeFor(template));
        document.setName(DeedDocumentTypes.name(template));
        document.setDirection(DocumentDirection.Outgoing);
        document.setOriginalName(DeedFilename.of(deedPoll, generated));
        document.setMimeType(generated.mimeType());
        document.setSizeBytes(generated.size());
        document.setStorageState(DocumentStorageState.UploadPending);
        documents.saveAndFlush(document);
        String checksum = payloads.retain(document.getId(), generated.content());
        jdbc.update("""
            INSERT INTO deedpoll_deedpreparation
                ("deedPollId", "documentId", template, "originalName", "mimeType", "sizeBytes", checksum,
                 "inputFingerprint")
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """, caseId, document.getId(), template.name(), document.getOriginalName(),
            generated.mimeType(), generated.size(), checksum, DeedDocumentService.inputFingerprint(deedPoll, template));
        return new PreparedDeed(document.getId(), template, document.getOriginalName(),
            generated.mimeType(), generated.size(), checksum);
    }

    /** Explicit case-scoped payload read; neither document entities nor list queries load these bytes. */
    @Transactional(readOnly = true)
    public byte[] content(String caseId, String documentId) {
        List<byte[]> found = jdbc.query("""
            SELECT p.content FROM deedpoll_stagedpayload p
            JOIN deedpoll_deedpreparation d ON d."documentId" = p.id
            WHERE d."deedPollId" = ? AND d."documentId" = ?
              AND d.checksum = p.checksum AND d."sizeBytes" = p."sizeBytes"
            """, (row, index) -> row.getBytes(1), caseId, documentId);
        if (found.isEmpty()) {
            throw unavailable();
        }
        return found.getFirst();
    }

    /** Called under the final case lock, before a prepared deed can be used in new correspondence. */
    @Transactional
    public void requireCurrent(String caseId, PreparedDeed prepared) {
        final var current = cases.findById(caseId).orElseThrow(() -> new NotFoundError("DeedPoll not found"));
        var document = documents.findByIdAndDeedPollIdForUpdate(prepared.documentId(), caseId)
            .orElseThrow(DeedPreparations::unavailable);
        deeds.requireCompatible(document, prepared.template());
        if (document.getStorageState() != DocumentStorageState.Attached || document.getCdamDocumentId() == null
            || !document.getCdamDocumentId().toString().equals(document.getStorageKey())) {
            throw unavailable();
        }
        List<String> fingerprints = jdbc.query(
            "SELECT \"inputFingerprint\" FROM deedpoll_deedpreparation WHERE \"deedPollId\" = ?",
            (row, index) -> row.getString(1), caseId);
        // Legacy attachments have no generation provenance. The coordinator still checks the case snapshot,
        // and reserve below rechecks their identity and metadata without inventing a fingerprint.
        boolean legacy = prepared.checksum() == null && fingerprints.isEmpty();
        if (fingerprints.size() == 1 && fingerprints.getFirst() == null) {
            throw new ConflictError("This deed preparation predates input verification. "
                + "Its original case inputs must be investigated before sending this email.");
        }
        if (!legacy && fingerprints.size() != 1) {
            throw unavailable();
        }
        if (!legacy
            && !DeedDocumentService.inputFingerprint(current, prepared.template()).equals(fingerprints.getFirst())) {
            throw new ConflictError("This case changed while its deed was being prepared. "
                + "Review the case before sending this email.");
        }
        if (!prepared.equals(reserve(caseId, prepared.template()))) {
            throw unavailable();
        }
    }

    /** Keep the payload on every failed read; an Attached row never acquires a new upload budget. */
    public void verifyAndRelease(String caseId, PreparedDeed prepared) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deed verification must run outside a transaction");
        }
        if (!jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM deedpoll_stagedpayload WHERE id = ?)",
            Boolean.class, prepared.documentId())) {
            return;
        }
        var download = reader.downloadDocument(caseId, prepared.documentId());
        try (var stream = download.stream()) {
            byte[] bytes = stream.readNBytes(prepared.sizeBytes() + 1);
            if (bytes.length != prepared.sizeBytes() || !StagedPayloads.checksum(bytes).equals(prepared.checksum())) {
                throw new UpstreamFailureError("The stored deed could not be verified; its preparation was kept");
            }
        } catch (IOException failure) {
            throw new UpstreamFailureError("The stored deed could not be verified; its preparation was kept");
        }
        transaction.executeWithoutResult(status -> {
            var document = documents.findByIdAndDeedPollIdForUpdate(prepared.documentId(), caseId)
                .orElseThrow(DeedPreparations::unavailable);
            if (document.getStorageState() != DocumentStorageState.Attached
                || !Objects.equals(document.getCdamDocumentId(), download.document().getCdamDocumentId())) {
                throw unavailable();
            }
            jdbc.update("""
                DELETE FROM deedpoll_stagedpayload p USING deedpoll_deedpreparation d
                WHERE p.id = d."documentId" AND d."deedPollId" = ? AND p.id = ?
                  AND p.checksum = ? AND d.checksum = p.checksum AND d."sizeBytes" = p."sizeBytes"
                """, caseId, prepared.documentId(), prepared.checksum());
        });
    }

    private static ConflictError unavailable() {
        return new ConflictError("The existing deed is unavailable or incompatible; it must not be regenerated");
    }
}
