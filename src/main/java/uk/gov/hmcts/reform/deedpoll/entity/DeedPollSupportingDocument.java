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
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A supporting document on a case: {@code deedpoll_supportingdocument}, ported from the
 * {@code DeedPollSupportingDocument} model in {@code reference/deed-poll.prisma}.
 *
 * <p>Metadata only. The blob itself lives outside the database -- {@code storageKey} is the handle
 * to it -- and all four file columns are nullable because a row can exist before, or without, an
 * uploaded file.
 */
@Entity
@Table(name = "deedpoll_supportingdocument")
@Getter
@Setter
public class DeedPollSupportingDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private String id;

    // ON DELETE CASCADE in the database -- see V3__children.sql.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deedPollId", nullable = false)
    private DeedPoll deedPoll;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "direction", nullable = false)
    private DocumentDirection direction;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    // ON DELETE RESTRICT in the database: a document type still in use cannot be deleted.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "documentTypeId", nullable = false)
    private DeedPollSupportingDocumentType documentType;

    @Column(name = "storageKey")
    private String storageKey;

    @Column(name = "originalName")
    private String originalName;

    @Column(name = "mimeType")
    private String mimeType;

    @Column(name = "sizeBytes")
    private Integer sizeBytes;

    // Where the document's remote storage has got to (V25). varchar in the database, not a Postgres enum type, so
    // plain STRING rather than NAMED_ENUM. Local until a CDAM store says otherwise.
    @Enumerated(EnumType.STRING)
    @Column(name = "storageState", length = 32, nullable = false)
    private DocumentStorageState storageState = DocumentStorageState.Local;

    // A storage reference only: never published in a response (plan 03a, decision 7).
    @Column(name = "cdamDocumentId")
    private UUID cdamDocumentId;

    // TRANSIENT (plan 03a, decision 8): held only while Uploaded or AttachUncertain, and never logged or projected.
    @Column(name = "cdamHashToken", length = 64)
    private String cdamHashToken;

    @Column(name = "storageAttemptedOn")
    private LocalDateTime storageAttemptedOn;

    @Column(name = "storageUploadAttempts", nullable = false)
    private short storageUploadAttempts;

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
