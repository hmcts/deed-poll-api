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
 * A caseworker note on a case: {@code deedpoll_notes}, ported from the {@code DeedPollNote} model
 * in {@code reference/deed-poll.prisma}.
 *
 * <p>There is no {@code deedpoll_NoteID}. The source's autonumber display code is deliberately not
 * ported -- notes are cited by their text, not a number -- and the reference records the omission
 * explicitly. Do not add it back.
 *
 * <p>{@code createdBy} and {@code modifiedBy} are nullable so notes predating those columns render
 * "Not recorded" rather than "null", and so a caller that sends no user name is never attributed
 * to someone who did not write the note.
 */
@Entity
@Table(name = "deedpoll_notes")
@Getter
@Setter
public class DeedPollNote {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private String id;

    // The foreign key is ON DELETE CASCADE in the database, which is where the cascade stays --
    // see V3__children.sql.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deedPollId", nullable = false)
    private DeedPoll deedPoll;

    @Column(name = "note", length = 2000, nullable = false)
    private String note;

    @Column(name = "createdBy", length = 200)
    private String createdBy;

    @Column(name = "modifiedBy", length = 200)
    private String modifiedBy;

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
