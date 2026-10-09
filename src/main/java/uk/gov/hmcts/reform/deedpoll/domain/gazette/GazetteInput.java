package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable mapped inputs, held only in the originating operation's memory, never persisted or returned by HTTP. */
public record GazetteInput(String caseId, String referenceNumber, String kind, LocalDateTime dateEnroled,
                           String state, boolean publish, LocalDateTime noticeSent, List<Object> values) {
    public GazetteInput {
        if (!("adult".equals(kind) || "minor".equals(kind)) || values == null
            || values.size() != GazetteColumn.columns(kind.equals("adult")).size()
            || values.stream().anyMatch(value -> value != null && !(value instanceof String)
                && !(value instanceof LocalDateTime))) {
            throw new IllegalArgumentException("Invalid Gazette mapped input");
        }
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static GazetteInput from(DeedPoll row) {
        String group = switch (row.getType()) {
            case Adult, AdultTransgender -> "adult";
            case Minor, MinorTransgender -> "minor";
            default -> throw new IllegalArgumentException("Unsupported Gazette input type");
        };
        return new GazetteInput(row.getId(), row.getDisplayReference(), group, row.getDateEnroled(),
            row.getState() == null ? null : row.getState().name(), row.isPublishToLondonGazette(),
            row.getDateNoticeSentToLondonGazette(),
            GazetteColumn.columns(group.equals("adult")).stream().map(column -> column.value(row)).toList());
    }

    @Override
    public String toString() {
        return "GazetteInput[redacted]";
    }
}
