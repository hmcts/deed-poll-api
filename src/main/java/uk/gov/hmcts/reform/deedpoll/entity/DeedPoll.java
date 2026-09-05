package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The case: {@code deedpoll_deedpoll}, ported field for field from the {@code DeedPoll} model in
 * {@code reference/deed-poll.prisma}.
 *
 * <p>This is a MAPPING, not a redesign. Field names are the reference's field names and every
 * column name is stated explicitly, even where a naming strategy could have derived it, so that
 * a reader comparing this class against the reference can do so line by line. Flyway owns the
 * DDL -- nothing here generates a schema -- and {@code spring.jpa.hibernate.ddl-auto=validate}
 * is what proves the two agree.
 *
 * <p>Three mappings carry more history than they look:
 *
 * <ul>
 *   <li>{@code signatureReceivedDate} maps to {@code "signaturerecieveddate"}. The misspelling is
 *       the source Dynamics column's and is deliberate; the corrected name is exposed here.</li>
 *   <li>The enum fields are {@link EnumType#STRING} onto native Postgres enum types, which needs
 *       {@link SqlTypes#NAMED_ENUM} as well: without it Hibernate sends the value as
 *       {@code varchar} and Postgres refuses to cast it to the enum type on write.</li>
 *   <li>{@code createdOn} and {@code modifiedOn} are maintained here rather than by the database,
 *       reproducing the reference's {@code @default(now())} and {@code @updatedAt}.
 *       {@code modifiedOn} is {@code NOT NULL} with no database default, so an insert that
 *       skipped this would fail.</li>
 * </ul>
 */
@Entity
@Table(name = "deedpoll_deedpoll")
@Getter
@Setter
// The reference names three multiselect fields iShall, iHaveSubscribed and andAlsoMyOld, and
// referencenumber and urgencyreason all in lower case. Those names are what the TypeScript
// callers and tests say, so they are kept; the checkstyle member-name pattern rejects a second
// upper-case character and is suppressed for this class rather than the names being "tidied".
@SuppressWarnings("checkstyle:MemberName")
public class DeedPoll {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    private String id;

    // --- Identity ---
    // referencenumber is the serial component of the display reference, allocated on create.
    // displayReference is the rendered "{prefix}{year}-{serial}" form, stored as a unique key.
    @Column(name = "referencenumber")
    private Integer referencenumber;

    @Column(name = "displayReference", unique = true)
    private String displayReference;

    // --- General / classification ---
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "type")
    private DeedPollType type;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "state")
    private DeedPollState state;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "privacy", nullable = false)
    private Privacy privacy = Privacy.Normal;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "urgency", nullable = false)
    private Urgency urgency = Urgency.Normal;

    @Column(name = "urgencyreason")
    private String urgencyreason;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "postCounter")
    private PostCounter postCounter;

    @Column(name = "inPerson")
    private Boolean inPerson;

    @Column(name = "migratedRecord", nullable = false)
    private boolean migratedRecord;

    @Column(name = "retainPermanently", nullable = false)
    private boolean retainPermanently;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "maritalStatus")
    private MaritalStatus maritalStatus;

    @Column(name = "feeReference")
    private String feeReference;

    @Column(name = "onlineApplicationReference")
    private String onlineApplicationReference;

    // --- Applicant name ---
    @Column(name = "newForename", length = 1000)
    private String newForename;

    @Column(name = "newMiddleName", length = 1000)
    private String newMiddleName;

    @Column(name = "newSurname", length = 1000)
    private String newSurname;

    @Column(name = "oldForename", length = 1000)
    private String oldForename;

    @Column(name = "oldMiddleName", length = 1000)
    private String oldMiddleName;

    @Column(name = "oldSurname", length = 1000)
    private String oldSurname;

    // --- Applicant address & contact (flat text -- no contact/account linkage) ---
    @Column(name = "applicantBuildingNoName")
    private String applicantBuildingNoName;

    @Column(name = "applicantEmail")
    private String applicantEmail;

    @Column(name = "applicantTelephone")
    private String applicantTelephone;

    @Column(name = "applicantAddress1")
    private String applicantAddress1;

    @Column(name = "applicantAddress2")
    private String applicantAddress2;

    @Column(name = "applicantTownCity")
    private String applicantTownCity;

    @Column(name = "applicantPostcode")
    private String applicantPostcode;

    // --- Representative address & contact (flat text) ---
    @Column(name = "representativeName")
    private String representativeName;

    @Column(name = "representativeBuildingNoName")
    private String representativeBuildingNoName;

    @Column(name = "representativeCompany")
    private String representativeCompany;

    @Column(name = "representativeAddress1")
    private String representativeAddress1;

    @Column(name = "representativeEmail")
    private String representativeEmail;

    @Column(name = "representativeAddress2")
    private String representativeAddress2;

    @Column(name = "representativeTelephone")
    private String representativeTelephone;

    @Column(name = "representativeTownCity")
    private String representativeTownCity;

    @Column(name = "representativePostcode")
    private String representativePostcode;

    // --- Minor ---
    @Column(name = "ageOfMinor")
    private Integer ageOfMinor;

    @Column(name = "ageOfMinorMonths")
    private Integer ageOfMinorMonths;

    @Column(name = "minorDateOfBirth")
    private LocalDateTime minorDateOfBirth;

    @Column(name = "parents")
    private String parents;

    // --- Public-journey fields ---
    // All nullable: they exist because the source Dynamics entity cannot store what a redesigned
    // public-facing journey needs to capture, and the caseworker service is unaffected by them.
    // The reference documents the specific gap each one closes.
    @Column(name = "placeOfBirth", length = 100)
    private String placeOfBirth;

    @Column(name = "nationalitySubject", length = 20)
    private String nationalitySubject;

    @Column(name = "nationalitySectionProvenance", length = 30)
    private String nationalitySectionProvenance;

    @Column(name = "parentRelationship", length = 100)
    private String parentRelationship;

    @Column(name = "parentAddress1", length = 100)
    private String parentAddress1;

    @Column(name = "parentAddress2", length = 100)
    private String parentAddress2;

    @Column(name = "parentTownCity", length = 100)
    private String parentTownCity;

    @Column(name = "parentPostcode", length = 10)
    private String parentPostcode;

    @Column(name = "parentCountry", length = 100)
    private String parentCountry;

    @Column(name = "secondParentName", length = 1000)
    private String secondParentName;

    @Column(name = "secondParentRelationship", length = 100)
    private String secondParentRelationship;

    // --- Nationality ---
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "nationality")
    private Nationality nationality;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "nationalitySection")
    private NationalitySection nationalitySection;

    @Column(name = "nationalitySectionOther")
    private String nationalitySectionOther;

    // --- Declarations ---
    // Multiselects held as integer[], preserving the Dynamics option values:
    //   iShall           1 wife, 2 husband, 3 civil partner, 4 children, 5 I alone
    //   iHaveSubscribed  1 forename, 2 middle, 3 surname
    //   andAlsoMyOld     1 forename, 2 middle, 3 surname
    // Empty rather than null by default, matching the reference's @default([]) -- the column is
    // nullable, but no caller should have to distinguish "no selections" from "not set".
    @Column(name = "iShall")
    private Integer[] iShall = new Integer[0];

    @Column(name = "iHaveSubscribed")
    private Integer[] iHaveSubscribed = new Integer[0];

    @Column(name = "andAlsoMyOld")
    private Integer[] andAlsoMyOld = new Integer[0];

    @Column(name = "dateOfDeclaration")
    private LocalDateTime dateOfDeclaration;

    // --- Dates driving state ---
    @Column(name = "dateOfApplication")
    private LocalDateTime dateOfApplication;

    @Column(name = "dateReceived")
    private LocalDateTime dateReceived;

    @Column(name = "awaitingFurtherDocsInfo")
    private LocalDateTime awaitingFurtherDocsInfo;

    @Column(name = "signatureRequestedDate")
    private LocalDateTime signatureRequestedDate;

    // The source column is misspelled "recieved". The column keeps the source spelling; this
    // field exposes the corrected name, exactly as the reference's @map does.
    @Column(name = "signaturerecieveddate")
    private LocalDateTime signatureReceivedDate;

    @Column(name = "dateReadyForMaster")
    private LocalDateTime dateReadyForMaster;

    @Column(name = "dateReferred")
    private LocalDateTime dateReferred;

    @Column(name = "dateEnroled")
    private LocalDateTime dateEnroled;

    @Column(name = "dateReturned")
    private LocalDateTime dateReturned;

    @Column(name = "dateNoticeSentToLondonGazette")
    private LocalDateTime dateNoticeSentToLondonGazette;

    // --- Master / referral ---
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "referToMasterChoice")
    private ReferToMaster referToMasterChoice;

    @Column(name = "masterName")
    private String masterName;

    @Column(name = "masterDirections")
    private String masterDirections;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "masterDecision")
    private MasterDecision masterDecision;

    @Column(name = "masterDateOfDecision")
    private LocalDateTime masterDateOfDecision;

    @Column(name = "dateDecisionDirectionsReceived")
    private LocalDateTime dateDecisionDirectionsReceived;

    @Column(name = "referralNotes")
    private String referralNotes;

    @Column(name = "publishToLondonGazette", nullable = false)
    private boolean publishToLondonGazette = true;

    // --- Return / refund ---
    @Column(name = "returnReason")
    private String returnReason;

    @Column(name = "refundAmount", precision = 12, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "dateOfRefund")
    private LocalDateTime dateOfRefund;

    @Column(name = "refundReference")
    private String refundReference;

    // --- Audit ---
    // createdBy/modifiedBy hold the display name resolved at write time, not a foreign key:
    // there is no user table, and the source case header shows the name as captured. Nullable,
    // because rows predating the columns have no attributable author.
    @Column(name = "createdOn", nullable = false)
    private LocalDateTime createdOn;

    @Column(name = "modifiedOn", nullable = false)
    private LocalDateTime modifiedOn;

    @Column(name = "createdBy", length = 200)
    private String createdBy;

    @Column(name = "modifiedBy", length = 200)
    private String modifiedBy;

    // --- Relations ---
    // No JPA cascade: the database cascades the delete (see V3__children.sql), and duplicating
    // that in Hibernate would mean two places to keep in step. Lazy, because the list view reads
    // cases without their children and the case view fetches them deliberately.
    // Oldest first, reproducing the `orderBy: { createdOn: "asc" }` the reference declares on each of
    // the three includes. A child list is a chronology -- notes especially -- so the order is part of
    // what the page means, not a preference of whichever query happened to load it.
    @OneToMany(mappedBy = "deedPoll", fetch = FetchType.LAZY)
    @OrderBy("createdOn ASC")
    private List<DeedPollNote> notes = new ArrayList<>();

    @OneToMany(mappedBy = "deedPoll", fetch = FetchType.LAZY)
    @OrderBy("createdOn ASC")
    private List<DeedPollWitness> witnesses = new ArrayList<>();

    @OneToMany(mappedBy = "deedPoll", fetch = FetchType.LAZY)
    @OrderBy("createdOn ASC")
    private List<DeedPollSupportingDocument> documents = new ArrayList<>();

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
