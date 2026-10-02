package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import java.util.List;

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
    private final TransactionTemplate transaction;

    public DeedPreparations(DeedPollRepository cases, DeedPollSupportingDocumentRepository documents,
                            DeedDocumentService deeds, DeedGenerator generator, StagedPayloads payloads,
                            JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.cases = cases;
        this.documents = documents;
        this.deeds = deeds;
        this.generator = generator;
        this.payloads = payloads;
        this.jdbc = jdbc;
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
            throw new ConflictError("The case must be linked to CCD before preparing its deed");
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
                ("deedPollId", "documentId", template, "originalName", "mimeType", "sizeBytes", checksum)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """, caseId, document.getId(), template.name(), document.getOriginalName(),
            generated.mimeType(), generated.size(), checksum);
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

    private static ConflictError unavailable() {
        return new ConflictError("The existing deed is unavailable or incompatible; it must not be regenerated");
    }
}
