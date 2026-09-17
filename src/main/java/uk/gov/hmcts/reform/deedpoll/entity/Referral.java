package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A referral of a case to the judiciary: {@code deedpoll_referral}, from {@code V6__referrals.sql},
 * with the claim and direction columns {@code V8__referral_claim_and_direction.sql} adds.
 *
 * <p><strong>Not a port.</strong> Like {@link SavedQuery}, this has no model in
 * {@code reference/deed-poll.prisma}. The source store held one referral inline on the case,
 * latest-wins -- {@code dateReferred}, {@code masterName}, {@code masterDecision},
 * {@code referralNotes} and the rest of that block are still on {@link DeedPoll} and still read-only
 * -- and this entity replaces that snapshot so a case can carry many referrals over its lifetime.
 * {@code SchemaConformanceTest} has nothing to compare it against and {@code EntityMappingTest}'s
 * reference-enum factory does not reach its enums; {@code ddl-auto: validate} and the round-trip test
 * are what hold it to the migration.
 *
 * <p><strong>Nothing here writes the case's flat columns.</strong> Creating a referral does not touch
 * {@code dateReferred} or {@code referralNotes}; those stay the ported historical snapshot, and
 * {@code V7__backfill_referrals.sql} reads them once, in the other direction.
 *
 * <p>The parent is a {@code @ManyToOne} rather than a bare id, as on {@link DeedPollNote}, so the
 * scope of a referral is a mapped relation rather than a convention -- and the cascade stays in the
 * database, where V6 declares it.
 *
 * <p>{@code reasons} holds {@code ReferralReason} names as a JSON array, the same shape as
 * {@link SavedQuery#getConditions()} and for the same reason: {@link SqlTypes#JSON} maps a
 * {@code List<String>} onto {@code jsonb} with one annotation, where an enum array would need a
 * custom type. Validation against the enum is the domain's job, on write. {@code state} and
 * {@code urgency} are real Postgres enum types and so need {@link SqlTypes#NAMED_ENUM}; without it
 * Hibernate sends the value as an integer ordinal.
 *
 * <p>{@code notes} is one field doing two jobs deliberately -- the explanation {@code Other} requires
 * and the general referral note. {@code urgency} is the referral's own and may differ from the case's.
 */
@Entity
@Table(name = "deedpoll_referral")
@Getter
@Setter
public class Referral {

    /**
     * A uuid, unlike the ported entities' text ids: there is no source row whose id has to survive a
     * data migration, so this follows {@link SavedQuery} rather than {@link DeedPollNote}.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    // The foreign key is ON DELETE CASCADE in the database, which is where the cascade stays --
    // see V6__referrals.sql. The column is text because deedpoll_deedpoll."id" is.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deedPollId", nullable = false)
    private DeedPoll deedPoll;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state", nullable = false)
    private ReferralState state = ReferralState.Open;

    /**
     * {@code ReferralReason} names, validated on write. Empty on a backfilled referral.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reasons", nullable = false)
    private List<String> reasons = new ArrayList<>();

    @Column(name = "notes")
    private String notes;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "urgency", nullable = false)
    private Urgency urgency = Urgency.Normal;

    @Column(name = "urgencyReason")
    private String urgencyReason;

    /** Seeded from the case's flat columns rather than raised by a caseworker, so it has no reasons. */
    @Column(name = "migrated", nullable = false)
    private boolean migrated;

    /**
     * The judge holding this referral, as a display name resolved from their token at claim time.
     * Null on an {@code Open} referral and on every backfilled one -- the power app recorded no claim.
     */
    @Column(name = "claimedBy")
    private String claimedBy;

    @Column(name = "claimedAt")
    private LocalDateTime claimedAt;

    /**
     * When the holding judge last interacted with this referral: claiming it, reading it through the
     * judicial surface, or saving a direction. The input to the inactivity sweep, and a separate column
     * from {@link #claimedAt} on purpose -- the expiry is measured from the last interaction, so a judge
     * still working on a referral keeps it. Nulled when a claim is released, which is what stops the
     * sweep reclaiming the same row twice.
     */
    @Column(name = "lastTouchedAt")
    private LocalDateTime lastTouchedAt;

    /**
     * The direction, reusing the ported {@link MasterDecision} vocabulary rather than a
     * referral-specific copy of it. A real Postgres enum type, so it needs
     * {@link SqlTypes#NAMED_ENUM} like {@code state} and {@code urgency}.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "decision")
    private MasterDecision decision;

    @Column(name = "directionsText")
    private String directionsText;

    /**
     * The judge's decision on London Gazette publication. A {@link Boolean} rather than a primitive
     * because absent and false are different answers: a referral with no direction has not been asked.
     * Never derived from the caseworker's referral reasons.
     */
    @Column(name = "publishToGazette")
    private Boolean publishToGazette;

    @Column(name = "decisionDate")
    private LocalDateTime decisionDate;

    /** The deciding judge's display name, resolved from their token when the direction was recorded. */
    @Column(name = "decidedBy")
    private String decidedBy;

    @Column(name = "createdOn", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "modifiedOn", nullable = false)
    private LocalDateTime modifiedOn;

    @Column(name = "createdBy", length = 200)
    private String createdBy;

    @Column(name = "modifiedBy", length = 200)
    private String modifiedBy;

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
