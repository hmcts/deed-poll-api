package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;

/**
 * A witness or declarant on a case: {@code deedpoll_witness}, ported from the
 * {@code DeedPollWitness} model in {@code reference/deed-poll.prisma}.
 *
 * <p>{@code declarant} is the source's Yes/No flag distinguishing the declarant from a witness, so
 * one row type covers both.
 */
@Entity
@Table(name = "deedpoll_witness")
@Getter
@Setter
public class DeedPollWitness {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private String id;

    // ON DELETE CASCADE in the database -- see V3__children.sql.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deedPollId", nullable = false)
    private DeedPoll deedPoll;

    @Column(name = "witnessName", length = 100, nullable = false)
    private String witnessName;

    @Column(name = "witnessAddress", length = 250, nullable = false)
    private String witnessAddress;

    @Column(name = "witnessOccupation", length = 100, nullable = false)
    private String witnessOccupation;

    @Column(name = "declarant", nullable = false)
    private boolean declarant;

    @Column(name = "createdOn", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "modifiedOn", nullable = false)
    private LocalDateTime modifiedOn;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdOn == null) {
            createdOn = now;
        }
        modifiedOn = now;
    }

    @PreUpdate
    void onUpdate() {
        modifiedOn = LocalDateTime.now();
    }
}
