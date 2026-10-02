package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import com.fasterxml.jackson.annotation.JsonIgnoreType;

/** Internal immutable reservation metadata, never a public document or evidence of attachment. */
@JsonIgnoreType
public record PreparedDeed(String documentId, DeedTemplate template, String originalName,
                           String mimeType, int sizeBytes, String checksum) {
    @Override
    public String toString() {
        return "PreparedDeed[template=" + template + ", sizeBytes=" + sizeBytes + "]";
    }
}
