package uk.gov.hmcts.reform.deedpoll.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Durable temporary bytes. No expiry for generated deeds; read-back must precede release (Task 4). */
@Component
public class StagedPayloads {
    private final JdbcTemplate jdbc;
    private final long maxBytes;

    public StagedPayloads(JdbcTemplate jdbc,
                          @Value("${deedpoll.documents.staging.max-total-bytes:0}") long maxBytes) {
        this.jdbc = jdbc;
        this.maxBytes = maxBytes;
    }

    /** Join the reservation transaction, serialising quota admission across every replica/owner. */
    @Transactional(propagation = Propagation.MANDATORY)
    public String retain(String id, byte[] content) {
        if (maxBytes <= 0 || content.length == 0 || content.length > UploadPolicy.MAX_UPLOAD_BYTES) {
            throw new ConflictError("Durable document preparation requires configured capacity and a bounded file");
        }
        jdbc.execute("SELECT pg_advisory_xact_lock(7340032, 26214400)");
        long used = jdbc.queryForObject(
            "SELECT coalesce(sum(\"sizeBytes\"), 0) FROM deedpoll_stagedpayload", Long.class);
        if (content.length > maxBytes || used > maxBytes - content.length) {
            throw new ConflictError("Document staging capacity has been reached; retained documents were kept");
        }
        String checksum = checksum(content);
        jdbc.update("""
            INSERT INTO deedpoll_stagedpayload (id, content, "sizeBytes", checksum) VALUES (?, ?, ?, ?)
            """, id, content, content.length, checksum);
        return checksum;
    }

    public static String checksum(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
