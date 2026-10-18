package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The reference as sent, never resolved from today's case. Foreign keys stay in the database;
 * no mapped association or delete cascade can silently erase this audit evidence.
 */
@Entity
@Table(name = "deedpoll_gazette_notice_batch_case")
@Getter
@Setter
public class GazetteNoticeBatchCase {

    @EmbeddedId
    private GazetteNoticeBatchCaseId id;

    @Column(name = "reference_number", nullable = false, updatable = false)
    private String referenceNumber;
}
