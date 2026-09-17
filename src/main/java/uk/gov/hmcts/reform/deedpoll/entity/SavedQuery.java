package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A named case-list query: {@code deedpoll_savedquery}, from {@code V5__saved_queries.sql}.
 *
 * <p><strong>Not a port.</strong> Every other entity here maps a model in
 * {@code reference/deed-poll.prisma} field for field; this one has no reference model, because saved
 * queries are new behaviour rather than something the TypeScript store had. So
 * {@code SchemaConformanceTest} has nothing to compare it against and {@code EntityMappingTest}'s
 * enum and round-trip factories do not reach it -- {@code ddl-auto: validate} and this entity's own
 * round-trip test are what hold it to the migration.
 *
 * <p>{@code conditions} is the JSON array of wire strings the list URL carried --
 * {@code ["state:in:ENROLED,SUBMITTED"]} -- and not structured objects, which is what lets
 * {@code CaseQuery.parse} validate a stored query with no adapter and no second parser.
 * {@link SqlTypes#JSON} is what maps the {@code List<String>} onto the {@code jsonb} column; without
 * it Hibernate would want a collection table.
 *
 * <p>{@code ownerId} is separate from {@code createdBy} deliberately. The audit columns hold a display
 * name resolved at write time, and the owner needs a stable id from the token's subject as well as the
 * name as it read when saved, so a departed owner's query still says whose it was.
 */
@Entity
@Table(name = "deedpoll_savedquery")
@Getter
@Setter
public class SavedQuery {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** Published to the library, which makes it findable -- not present in anyone else's list. */
    @Column(name = "shared", nullable = false)
    private boolean shared;

    @Column(name = "ownerId", nullable = false)
    private String ownerId;

    @Column(name = "ownerName", nullable = false)
    private String ownerName;

    // --- The parts of the list URL that define a view. Deliberately no `page`. ---
    @Column(name = "filter")
    private String filter;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "conditions", nullable = false)
    private List<String> conditions = new ArrayList<>();

    @Column(name = "sortField")
    private String sortField;

    @Column(name = "sortDirection")
    private String sortDirection;

    @Column(name = "searchTerm")
    private String searchTerm;

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
