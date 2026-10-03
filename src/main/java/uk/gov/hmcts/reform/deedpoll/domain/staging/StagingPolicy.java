package uk.gov.hmcts.reform.deedpoll.domain.staging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadPolicy;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;

import java.time.Duration;

/** Draft file/count ceilings preserve the accepted citizen contract; capacity, expiry and schedule are explicit. */
@ConfigurationProperties("deedpoll.documents.staging")
public record StagingPolicy(boolean enabled, long maxTotalBytes, int recordMaxFiles,
                            long recordMaxBytes, long draftMaxBytes, Duration recordExpiry,
                            Duration draftExpiry, long cleanupIntervalMs, int cleanupRows,
                            long cleanupBytes, Duration cleanupTime) {
    public StagingPolicy {
        if (enabled && (maxTotalBytes <= 0 || recordMaxFiles <= 0 || recordMaxBytes <= 0
            || draftMaxBytes <= 0 || !positive(recordExpiry) || !positive(draftExpiry)
            || cleanupIntervalMs <= 0 || cleanupRows <= 0 || cleanupBytes < UploadPolicy.MAX_UPLOAD_BYTES
            || !positive(cleanupTime))) {
            throw new IllegalArgumentException("Staging requires explicit finite quotas, expiry and cleanup limits");
        }
    }

    public void requireEnabled() {
        if (!enabled) {
            throw new ConflictError("Document staging is disabled");
        }
    }

    public void validate(StageOwner owner, UploadedFile file) {
        requireEnabled();
        long ceiling = owner.kind() == StageOwner.Kind.DRAFT ? 7340032 : UploadPolicy.MAX_UPLOAD_BYTES;
        if (file.content() == null || file.size() <= 0 || file.size() != file.content().length
            || file.size() > ceiling || !UploadPolicy.isAllowedUploadType(file.mimeType())
            || file.originalName() == null || file.originalName().isBlank() || file.originalName().length() > 255
            || file.originalName().chars().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\')) {
            throw new ConflictError("The staged file has invalid content size or metadata");
        }
    }

    public int maxFiles(StageOwner owner) {
        return owner.kind() == StageOwner.Kind.DRAFT ? 10 : recordMaxFiles;
    }

    public long maxBytes(StageOwner owner) {
        return owner.kind() == StageOwner.Kind.DRAFT ? draftMaxBytes : recordMaxBytes;
    }

    public Duration expiry(StageOwner owner) {
        return owner.kind() == StageOwner.Kind.DRAFT ? draftExpiry : recordExpiry;
    }

    private static boolean positive(Duration value) {
        return value != null && !value.isNegative() && !value.isZero() && value.toMillis() > 0;
    }
}
