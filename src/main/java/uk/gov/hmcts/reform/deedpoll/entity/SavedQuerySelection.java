package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * One user's choice to see one saved query: {@code deedpoll_savedqueryselection}.
 *
 * <p>A sub-resource rather than a field on the query, because editing a query is the owner's right
 * whereas choosing to see it is every user's -- keeping them separate is what stops "add to my list"
 * needing edit permission.
 *
 * <p><strong>A row here is not a grant.</strong> Visibility is still "shared or mine", so a query
 * unshared by its owner after being selected stops appearing for the subscriber and the row simply
 * stays. Deleting the query does take the row: the foreign key is {@code ON DELETE CASCADE} in the
 * database, which is where the cascade stays -- see {@code V5__saved_queries.sql}. There is
 * deliberately no {@code @ManyToOne} to {@link SavedQuery}: nothing needs to navigate from a selection
 * to its query, and a mapped association would give Hibernate a second opinion about the delete rule.
 */
@Entity
@Table(name = "deedpoll_savedqueryselection")
@Getter
@Setter
public class SavedQuerySelection {

    @EmbeddedId
    private SavedQuerySelectionId id;

    public SavedQuerySelection() {
    }

    public SavedQuerySelection(UUID savedQueryId, String userId) {
        this.id = new SavedQuerySelectionId(savedQueryId, userId);
    }
}
