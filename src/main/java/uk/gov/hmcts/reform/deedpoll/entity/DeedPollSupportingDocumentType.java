package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A kind of supporting document a case may need: {@code deedpoll_supportingdocumenttype}, ported
 * from the {@code DeedPollSupportingDocumentType} model in {@code reference/deed-poll.prisma}.
 *
 * <p>The two multiselects hold Dynamics option-set integers, not enum names, exactly as the source
 * stores them: {@code applicableMaritalStatuses} against {@code MaritalStatus} and
 * {@code applicableTypes} against {@code DeedPollType}. They are what decides which document types
 * a given case is asked for. Empty rather than null by default, matching the reference's
 * {@code @default([])}.
 *
 * <p>This table has no {@code createdOn}/{@code modifiedOn}: the reference declares none, because
 * it is reference data rather than case data.
 */
@Entity
@Table(name = "deedpoll_supportingdocumenttype")
@Getter
@Setter
public class DeedPollSupportingDocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private String id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @Column(name = "applicableMaritalStatuses")
    private Integer[] applicableMaritalStatuses = new Integer[0];

    @Column(name = "applicableTypes")
    private Integer[] applicableTypes = new Integer[0];

    // No JPA cascade: the database restricts deleting a type that is still in use.
    @OneToMany(mappedBy = "documentType", fetch = FetchType.LAZY)
    private List<DeedPollSupportingDocument> documents = new ArrayList<>();
}
