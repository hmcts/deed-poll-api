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
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A referral of a case to the judiciary: {@code deedpoll_referral}, from {@code V6__referrals.sql},
 * with the claim and direction columns {@code V8__referral_claim_and_direction.sql} adds, the two owner
 * subjects {@code V14__referral_owner_subjects.sql} adds, and the confirmation columns
 * {@code V16__referral_confirmation.sql} adds, the direction-changed flag
 * {@code V38__referral_direction_changed_at.sql} adds, and the assignment columns
 * {@code V43__referral_assignment.sql} adds.
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
     *
     * <p><strong>Attribution, and nothing else.</strong> It is what a caseworker reads on the case and what a
     * lapse note names, and it authorises nothing: {@link #claimedById} is who the claim belongs to. Until
     * {@code V14__referral_owner_subjects.sql} this column was both, which is the defect that migration removes
     * -- two judges sharing a display name could read and direct each other's referrals.
     */
    @Column(name = "claimedBy")
    private String claimedBy;

    /**
     * The IDAM subject of the judge holding this referral -- who the claim actually belongs to, from
     * {@code V14__referral_owner_subjects.sql}.
     *
     * <p>Every ownership check on a referral compares this: {@code ReferralService.requireHeldBy},
     * {@code JudicialReferralService.visibleTo} and its {@code heldBy} helper. A subject is stable where a
     * display name is not, so a judge keeps their claim when IDAM renames them and cannot reach another judge's
     * by happening to share a name.
     *
     * <p>Null on an {@code Open} referral, on every backfilled one, and on any claim made before that migration
     * -- which is not backfilled, because a subject cannot be derived from a name. A null owner is nobody's, so
     * it fails closed at every check above rather than falling back to the name.
     */
    @Column(name = "claimedById")
    private String claimedById;

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
     * The Masters-list entry this referral is assigned to, from {@code V43__referral_assignment.sql}. A bare id
     * rather than a relation: nothing here reads the Master through the referral, and the cap counts by this column.
     *
     * <p>Null on an {@code Open} referral and on a claim made before V43 until linking attaches it. Kept once
     * directed, so a {@code Decided} or {@code Closed} referral still says whose it was; cleared only when the
     * processing window unassigns it. Never published: responses carry the Master's id and name, never a subject.
     */
    @Column(name = "assignedMasterId")
    private UUID assignedMasterId;

    /**
     * When this referral was last assigned, and so when its processing window started. Null on a claim made before
     * V43, whose window counts from {@link #claimedAt}.
     */
    @Column(name = "assignedAt")
    private LocalDateTime assignedAt;

    /**
     * The working day whose referral window this assignment counts towards for the per-Master cap, in
     * {@code Europe/London}. Set with {@link #windowSlot}, never alone (V43's check).
     */
    @Column(name = "windowDate")
    private LocalDate windowDate;

    /**
     * The morning or afternoon of {@link #windowDate}. A {@code varchar(2)} with a check, not a Postgres enum, so
     * plain {@link EnumType#STRING} without {@link SqlTypes#NAMED_ENUM}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "windowSlot", length = 2)
    private WindowSlot windowSlot;

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
     * The Master's answer to "Are there any changes to the standard publications in the Gazette?" -- true for
     * do not publish, or publish only as the direction text says; false for publish as standard. A
     * {@link Boolean} rather than a primitive because absent and false are different answers: a referral with no
     * direction has not been asked. Never derived from the caseworker's referral reasons. V41 renamed it from
     * {@code publishToGazette} and inverted the stored answers.
     */
    @Column(name = "changesToStandardPublication")
    private Boolean changesToStandardPublication;

    @Column(name = "decisionDate")
    private LocalDateTime decisionDate;

    /**
     * The deciding judge's display name, resolved from their token when the direction was recorded.
     *
     * <p>Attribution, like {@link #claimedBy}: it signs the direction for the office reading it, and
     * {@link #decidedById} is what decides whether its author may re-read it.
     */
    @Column(name = "decidedBy")
    private String decidedBy;

    /**
     * The IDAM subject of the judge who recorded the direction, from
     * {@code V14__referral_owner_subjects.sql}.
     *
     * <p>What {@code JudicialReferralService.visibleTo} compares on a {@code Decided} referral, so a judge can
     * re-read their own direction while the office actions it and no other judge can. Never cleared once set --
     * a direction is a permanent act, unlike a claim -- and null on a direction recorded before that migration,
     * which stays visible and actionable to caseworkers and belongs to no judge's personal list.
     */
    @Column(name = "decidedById")
    private String decidedById;

    /**
     * When the directing judge last revised or withdrew their direction, from
     * {@code V38__referral_direction_changed_at.sql}.
     *
     * <p>The caseworker's flag that the direction in front of them is not the one first given. Never cleared once
     * set, because it is a fact about the referral's history; the old and new values are in the audit trail.
     * Null on every referral whose direction stood as given.
     */
    @Column(name = "directionChangedAt")
    private LocalDateTime directionChangedAt;

    /**
     * When the applicant was asked to confirm whether they still want the deed enrolled, from
     * {@code V16__referral_confirmation.sql}.
     *
     * <p>Stamped as the caseworker sends {@code REQUEST_CONFIRMATION_TO_PROCEED}, in the same transaction
     * as the outbox row and not when Notify accepts it -- so a case's process state does not depend on an
     * asynchronous delivery outcome that may still be retrying.
     *
     * <p>Null on every referral that was never asked, which is every referral that predates the feature.
     * Null is also what makes recording an answer nobody asked for refusable rather than silently valid.
     */
    @Column(name = "confirmationRequestedAt")
    private LocalDateTime confirmationRequestedAt;

    /**
     * The applicant's answer, once a caseworker has read their reply and recorded it. A real Postgres enum
     * type, so it needs {@link SqlTypes#NAMED_ENUM} like {@code state}, {@code urgency} and
     * {@code decision}; without it Hibernate sends the value as an integer ordinal.
     *
     * <p>Null means outstanding, and that is the only thing null here means -- which is what lets the
     * waiting list be a {@code WHERE} clause over this and {@link #confirmationRequestedAt} rather than a
     * sweep. Recording an outcome twice is refused rather than overwritten: the second caseworker needs to
     * know the first already acted.
     *
     * <p>Neither outcome moves the case on by itself. See {@link ConfirmationOutcome}.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "confirmationOutcome")
    private ConfirmationOutcome confirmationOutcome;

    /**
     * When the caseworker recorded the answer, from the clock.
     *
     * <p>Not when the applicant sent their reply: nothing here knows that, and a column claiming to would
     * write a guess down as a fact.
     */
    @Column(name = "confirmationReceivedAt")
    private LocalDateTime confirmationReceivedAt;

    /**
     * The caseworker who recorded the answer, as a display name resolved from their token, never
     * client-supplied.
     *
     * <p>Attribution only, and unlike {@link #claimedBy}/{@link #decidedBy} it has no matching subject
     * column: nothing compares it. Recording an outcome is a caseworker act on a case the caller can
     * already read, so no ownership check turns on who did it.
     */
    @Column(name = "confirmationRecordedBy")
    private String confirmationRecordedBy;

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
