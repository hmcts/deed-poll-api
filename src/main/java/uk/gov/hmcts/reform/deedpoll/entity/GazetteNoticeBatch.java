package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Gazette audit evidence. The sender's subject is internal; only the frozen name is attribution.
 * The caller supplies the request identity and send time: persistence must never invent either.
 */
@Entity
@Table(name = "deedpoll_gazette_notice_batch")
@Getter
@Setter
public class GazetteNoticeBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private LocalDateTime sentAt;

    @Column(name = "sent_by_id", nullable = false, updatable = false)
    private String sentById;

    @Column(name = "sent_by_name", nullable = false, updatable = false)
    private String sentByName;

    @Column(name = "request_id", nullable = false, updatable = false)
    private UUID requestId;

    @Column(name = "case_count", nullable = false, updatable = false)
    private int caseCount;
}
