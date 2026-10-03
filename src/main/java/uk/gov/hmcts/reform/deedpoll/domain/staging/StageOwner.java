package uk.gov.hmcts.reform.deedpoll.domain.staging;

import java.util.Objects;

/** Trusted adapter assertion, never constructed directly from request fields. UUIDs are not authority. */
public record StageOwner(Kind kind, String id, String subject) {
    public enum Kind { RECORD, DRAFT }

    public StageOwner {
        Objects.requireNonNull(kind);
        if (id == null || id.isBlank() || subject == null || subject.isBlank()
            || id.length() > 255 || subject.length() > 255) {
            throw new IllegalArgumentException("An authenticated durable owner is required");
        }
    }

    @Override
    public String toString() {
        return "StageOwner[redacted]";
    }
}
