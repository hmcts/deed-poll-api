package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** The identity of one case within a Gazette batch. */
@Embeddable
@Getter
@Setter
public class GazetteNoticeBatchCaseId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "deed_poll_id", nullable = false, columnDefinition = "text")
    private String deedPollId;

    public GazetteNoticeBatchCaseId() {
    }

    public GazetteNoticeBatchCaseId(UUID batchId, String deedPollId) {
        this.batchId = batchId;
        this.deedPollId = deedPollId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GazetteNoticeBatchCaseId that)) {
            return false;
        }
        return Objects.equals(batchId, that.batchId) && Objects.equals(deedPollId, that.deedPollId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(batchId, deedPollId);
    }
}
