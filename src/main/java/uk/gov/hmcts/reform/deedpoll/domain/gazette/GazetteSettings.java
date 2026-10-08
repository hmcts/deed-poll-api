package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import java.time.LocalDateTime;

/** Shared recipient configuration; attribution excludes the private authenticated subject. */
public record GazetteSettings(String recipientEmail, int revision, LocalDateTime updatedAt, String updatedBy) {
}
