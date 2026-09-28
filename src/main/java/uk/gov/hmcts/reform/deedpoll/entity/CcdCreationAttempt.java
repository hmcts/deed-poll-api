package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.CcdCreationAttemptStatus;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One attempt to create and link a CCD case for one deed-poll record: {@code deedpoll_ccdcreationattempt}.
 *
 * <p><strong>Not a port.</strong> See {@code V22__ccd_creation_attempt.sql} for why it is a table, and
 * {@link CcdCreationAttemptStatus} for the lifecycle. Written only through the conditional statements in
 * {@code CcdCreationAttemptRepository}, never by a {@code save} of a loaded copy: every change of status is a
 * race between workers, and a merge from a stale read is how one worker would undo another's claim.
 *
 * <p>The id is assigned rather than generated, because {@code open} is a native insert that has to bind it.
 */
@Entity
@Table(name = "deedpoll_ccdcreationattempt")
@Getter
@Setter
public class CcdCreationAttempt {

    /**
     * The column bound on {@code failureDetail}, and the length the service truncates to before writing.
     */
    public static final int MAX_FAILURE_DETAIL = 1000;

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "deedPollId", nullable = false)
    private String deedPollId;

    @Column(name = "operationId", nullable = false, unique = true)
    private UUID operationId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "status", nullable = false)
    private CcdCreationAttemptStatus status = CcdCreationAttemptStatus.Pending;

    @Column(name = "claimedBy", length = 200)
    private String claimedBy;

    @Column(name = "leaseExpiresAt")
    private LocalDateTime leaseExpiresAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "failureDetail", length = MAX_FAILURE_DETAIL)
    private String failureDetail;

    /**
     * Whether {@code submitCaseCreation} may have been called for this attempt (V23). Once true it is never
     * reset. While it is false, or {@link #provenNothingCreated} is true, the attempt may be retried from scratch;
     * see {@code CcdLinkingRule.mayRetryFromScratch}. Rows that predate V23 were backfilled true.
     */
    @Column(name = "submissionMayHaveReached", nullable = false)
    private boolean submissionMayHaveReached;

    /**
     * Whether it is proven that nothing this attempt submitted created a case (V24): set only when a claim that
     * created nothing ran out of tries, kept by "reconcile again", cleared by "retry from scratch". While it is true
     * a retry from scratch is open whatever {@link #submissionMayHaveReached} says. Never published.
     */
    @Column(name = "provenNothingCreated", nullable = false)
    private boolean provenNothingCreated;

    /**
     * When a {@code Pending} attempt may next be claimed; null means now (V23).
     */
    @Column(name = "notBefore")
    private LocalDateTime notBefore;

    /** The IDAM subject of the admin who last repaired the attempt. Attribution only, never published. */
    @Column(name = "repairedById")
    private String repairedById;

    @Column(name = "repairedOn")
    private LocalDateTime repairedOn;

    @Column(name = "createdOn", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "modifiedOn", nullable = false)
    private LocalDateTime modifiedOn;

    @PrePersist
    void onCreate() {
        LocalDateTime now = AuditClock.now();
        if (createdOn == null) {
            createdOn = now;
        }
        modifiedOn = now;
    }

    @PreUpdate
    void onUpdate() {
        modifiedOn = AuditClock.now();
    }
}
