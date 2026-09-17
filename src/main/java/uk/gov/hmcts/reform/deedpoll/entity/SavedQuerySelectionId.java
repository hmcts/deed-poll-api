package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * The composite key of {@link SavedQuerySelection}: the query and the user who selected it.
 *
 * <p>A class rather than {@code @IdClass}-free field pair because JPA requires one for a composite
 * key, and {@code equals}/{@code hashCode} are written out rather than generated: Hibernate compares
 * keys inside the persistence context on every load, and a Lombok {@code @EqualsAndHashCode} here
 * would be one annotation away from a subtly different contract for the one class where identity is
 * the whole point.
 */
@Embeddable
@Getter
@Setter
public class SavedQuerySelectionId implements Serializable {

    private static final long serialVersionUID = 1L;

    @Column(name = "savedQueryId", nullable = false)
    private UUID savedQueryId;

    @Column(name = "userId", nullable = false)
    private String userId;

    public SavedQuerySelectionId() {
    }

    public SavedQuerySelectionId(UUID savedQueryId, String userId) {
        this.savedQueryId = savedQueryId;
        this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SavedQuerySelectionId that)) {
            return false;
        }
        return Objects.equals(savedQueryId, that.savedQueryId) && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(savedQueryId, userId);
    }
}
