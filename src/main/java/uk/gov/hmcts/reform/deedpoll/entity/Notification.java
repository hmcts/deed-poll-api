package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationFailureVerdict;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One email this service owes an applicant: {@code deedpoll_notification}, from
 * {@code V11__notifications.sql}.
 *
 * <p><strong>Not a port.</strong> Like {@link SavedQuery} and {@link Referral}, this has no model in
 * {@code reference/deed-poll.prisma} -- the TypeScript store sent nothing. So
 * {@code SchemaConformanceTest} has nothing to compare it against and {@code EntityMappingTest}'s
 * reference-enum factory does not reach {@link NotificationState}; {@code ddl-auto: validate} and the
 * round-trip test are what hold it to the migration.
 *
 * <p><strong>The parents are bare ids, not {@code @ManyToOne} relations, and that is the one place
 * this deviates from {@link DeedPollNote} and {@link Referral}.</strong> Those are children a
 * caseworker navigates to through their case, so mapping the parent makes the scope a relation rather
 * than a convention. An outbox row is not navigated to: the drain reads rows across every case, by a
 * predicate on {@code state} and {@code nextAttemptAt}, and needs nothing whatever off the case or the
 * referral -- the recipient and the personalisation were resolved and frozen when the row was
 * enqueued. A mapped parent would make the drain either load a case per row or hold a lazy proxy it
 * never touches. The database keeps both foreign keys and both cascades; see the migration.
 *
 * <p><strong>{@code recipient} and {@code personalisation} are frozen at enqueue, deliberately.</strong>
 * A later edit to the case cannot redirect an email already owed, and what the applicant was told
 * stays answerable from this row even when the caseworker edited the judge's reason before sending --
 * which is the case where recomputing the content would answer a different question.
 *
 * <p>{@code personalisation} is {@link SqlTypes#JSON} over {@code jsonb}, the shape
 * {@link SavedQuery#getConditions()} uses; {@code state} is a real Postgres enum type and so needs
 * {@link SqlTypes#NAMED_ENUM}, without which Hibernate sends the value as an integer ordinal.
 *
 * <p>{@code template} holds the {@code EmailTemplate} member name and never the Notify UUID: the UUID
 * is configuration and differs per environment, so a row storing it would stop being readable the
 * moment a template was re-pointed.
 */
@Entity
@Table(name = "deedpoll_notification")
@Getter
@Setter
public class Notification {

    /**
     * Also the {@code reference} the send carries to Notify, so a row and a notification are mutually
     * traceable. A uuid like {@link SavedQuery}'s and {@link Referral}'s: there is no source row whose
     * id has to survive a data migration.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    /**
     * The case this email is about. text, because {@code deedpoll_deedpoll."id"} is.
     */
    @Column(name = "deedPollId", nullable = false)
    private String deedPollId;

    /**
     * The referral that caused it, or null for a template that is not referral-driven. Half of the
     * partial unique index that makes one email of an {@link #exclusionGroup} per referral a constraint
     * rather than a check.
     */
    @Column(name = "referralId")
    private UUID referralId;

    /**
     * The {@code EmailTemplate} member name.
     */
    @Column(name = "template", nullable = false)
    private String template;

    /**
     * The {@code EmailExclusionGroup} member name -- the set of emails this referral gets exactly one of, and
     * the other half of the partial unique index from V15.
     *
     * <p><strong>A copy of the template's group taken when the row was written, not a lookup.</strong> The
     * index needs a column, and a row keeps the group it was written under, so re-grouping templates in a
     * later release cannot retrospectively make two already-sent emails conflict.
     *
     * <p>A member name and not an id, like {@link #template} beside it and for the same reason. Written by
     * {@code NotificationRepository.enqueue} from the enum; the column's {@code REFERRAL_OUTCOME} default is
     * what lets an application instance that predates V15 go on inserting during a rolling deployment.
     */
    @Column(name = "exclusionGroup", nullable = false)
    private String exclusionGroup;

    /**
     * What Notify rendered when the caseworker previewed it, so the stored content can be read back
     * against the wording it was written for. Null on a row enqueued without a preview.
     */
    @Column(name = "templateVersion")
    private Integer templateVersion;

    @Column(name = "recipient", nullable = false)
    private String recipient;

    /**
     * The resolved values and the caseworker's slots, exactly as sent. A {@link LinkedHashMap} so the
     * stored JSON reads in the order the resolver built it rather than in hash order.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "personalisation", nullable = false)
    private Map<String, Object> personalisation = new LinkedHashMap<>();

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state", nullable = false)
    private NotificationState state = NotificationState.Pending;

    /**
     * How many times a drain has taken this row, counted by the claim itself rather than by the
     * outcome -- so an attempt that killed the process is still counted and the row cannot be retried
     * forever.
     */
    @Column(name = "attempts", nullable = false)
    private int attempts;

    /**
     * When this row next becomes claimable. Doing double duty on purpose: it is the backoff after a
     * temporary failure and, while a drain holds the row, the lease that keeps another drain off it.
     * Left where the last claim put it once the row is terminal, where nothing reads it.
     */
    @Column(name = "nextAttemptAt", nullable = false)
    private LocalDateTime nextAttemptAt;

    /** What Notify answered with, so a delivery query is possible later without a migration. */
    @Column(name = "providerNotificationId")
    private UUID providerNotificationId;

    /**
     * The exact subject Notify returned when it accepted the send. Null until and unless the row is sent.
     */
    @Column(name = "subject")
    private String subject;

    /**
     * Notify's plain-text rendering of the accepted email. Null until and unless the row is sent.
     *
     * <p>This is correspondence history and may contain applicant and caseworker-authored content. It must
     * never be logged.
     */
    @Column(name = "body")
    private String body;

    /**
     * The taxonomy's verdict and whatever Notify said, kept on a row still waiting as well as on a
     * failed one -- "waiting, and here is why the last attempt did not work" is a question the Email
     * tab asks.
     */
    @Column(name = "failureReason")
    private String failureReason;

    /**
     * The machine-readable half of {@link #failureReason}, used by the partial unique index to distinguish a
     * permanent refusal -- where Notify definitely sent nothing -- from every uncertain outcome.
     *
     * <p>Nullable on a row that has never failed, and on an old row written during a rolling deployment by an
     * application instance that predates V12. The index treats null as uncertain and keeps it in the uniqueness
     * slot; only an explicit {@link NotificationFailureVerdict#PERMANENT} permits a new row.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "failureVerdict")
    private NotificationFailureVerdict failureVerdict;

    /**
     * The caseworker who sent it, and not nullable unlike every other {@code createdBy} here: they
     * authored part of the content, so this is the author of a letter to an applicant rather than
     * ordinary audit noise.
     */
    @Column(name = "createdBy", nullable = false, length = 200)
    private String createdBy;

    @Column(name = "createdOn", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "modifiedOn", nullable = false)
    private LocalDateTime modifiedOn;

    @Column(name = "modifiedBy", length = 200)
    private String modifiedBy;

    /**
     * Stamps the audit columns, and defaults {@code nextAttemptAt} to now so a newly enqueued row is
     * claimable by the very next drain. The database has the same default; this one is what makes the
     * value readable from the entity without a refresh.
     */
    @PrePersist
    void onCreate() {
        LocalDateTime now = AuditClock.now();
        if (createdOn == null) {
            createdOn = now;
        }
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
        modifiedOn = now;
    }

    @PreUpdate
    void onUpdate() {
        modifiedOn = AuditClock.now();
    }
}
