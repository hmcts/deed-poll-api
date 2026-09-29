package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NotificationState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.reference.PrismaSchema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the JPA entities agree with the Flyway schema, and the Java enums with the reference
 * schema's.
 *
 * <p>The strongest assertion here is that the context boots at all. Booting it with
 * {@code spring.jpa.hibernate.ddl-auto=validate} makes Hibernate compare every mapped table and
 * column against the migrated database and fail on any mismatch, which catches a mistyped column
 * name or a wrong type without this test having to restate the field list. Because that check is
 * vacuous for an entity Hibernate never saw, the first test asserts every one is in the metamodel.
 *
 * <p>Validation checks names and types, not values, so the round-trip tests write and re-read a
 * case and its children. They prove the mappings Hibernate cannot check on its own: the native
 * Postgres enum types, the {@code integer[]} multiselects, {@code numeric(12,2)}, and the
 * deliberately misspelled {@code signaturerecieveddate} column. They also cover validation's one
 * blind spot that matters to this schema -- Hibernate compares column names case-INSENSITIVELY, so
 * it accepts {@code "newforename"} for a column actually called {@code "newForename"}, and only a
 * statement that quotes the name and runs finds the mistake. Every column here is mixed case, so
 * that blind spot would otherwise be wide.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class EntityMappingTest extends AbstractPostgresContainerTest {

    private static final String ENUM_PACKAGE = DeedPollType.class.getPackageName();

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Set here as well as in {@code application.yaml}, so this test keeps asserting the mappings
     * even if the running application's setting is ever relaxed.
     */
    @DynamicPropertySource
    static void validateAgainstFlyway(DynamicPropertyRegistry registry) {
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Test
    @DisplayName("every entity is mapped, so Hibernate validated every one of them")
    void everyEntityIsMapped() {
        Set<Class<?>> mapped = entityManager.getMetamodel().getEntities().stream()
            .map(EntityType::getJavaType)
            .collect(Collectors.toSet());

        // The six ported models, then the five that are not a port. Exact rather than "contains", because
        // validation is vacuous for an entity Hibernate never saw and an entity dropped from the scan would
        // otherwise stop being checked silently.
        assertThat(mapped).containsExactlyInAnyOrder(
            DeedPoll.class,
            DeedPollNote.class,
            DeedPollWitness.class,
            DeedPollSupportingDocumentType.class,
            DeedPollSupportingDocument.class,
            ReferenceCounter.class,
            SavedQuery.class,
            SavedQuerySelection.class,
            Referral.class,
            Notification.class,
            CcdCreationAttempt.class);
    }

    @TestFactory
    @DisplayName("every reference enum has a Java enum with the same members in the same order")
    List<DynamicTest> everyReferenceEnumHasAJavaEnum() {
        return PrismaSchema.loadReference().enums().entrySet().stream()
            .map(entry -> DynamicTest.dynamicTest(entry.getKey(), () -> {
                Class<?> javaEnum = Class.forName(ENUM_PACKAGE + "." + entry.getKey());
                assertThat(javaEnum.isEnum()).as("%s is a Java enum", javaEnum.getName()).isTrue();
                List<String> members = Arrays.stream(javaEnum.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .toList();
                // Names, because they are the values stored in the Postgres enum; order, because a
                // member's position is the Dynamics option-set integer.
                assertThat(members)
                    .as("members of %s, in the reference's declaration order", entry.getKey())
                    .containsExactlyElementsOf(entry.getValue());
            }))
            .toList();
    }

    @Test
    @DisplayName("a case round trips through every column type Hibernate cannot check for itself")
    void caseRoundTrips() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setType(DeedPollType.DeedOfRelinquishment);
        deedPoll.setState(DeedPollState.AwaitingSignature);
        deedPoll.setPrivacy(Privacy.Private);
        deedPoll.setUrgency(Urgency.Urgent);
        deedPoll.setPostCounter(PostCounter.Post);
        deedPoll.setMaritalStatus(MaritalStatus.MarriedCivilPartnership);
        deedPoll.setNationality(Nationality.CommonwealthCitizen);
        deedPoll.setNationalitySection(NationalitySection.Section11);
        deedPoll.setReferToMasterChoice(ReferToMaster.Yes);
        deedPoll.setMasterDecision(MasterDecision.Approved);
        // The denormalised referral read-model, on the case's own row and typed with V6's "ReferralState"
        // rather than a second enum: without @JdbcTypeCode(NAMED_ENUM) this write is refused as a varchar.
        deedPoll.setCurrentReferralState(ReferralState.Decided);
        deedPoll.setReferencenumber(41);
        deedPoll.setDisplayReference("KBDR2026-41");
        deedPoll.setNewForename("Alex");
        deedPoll.setIShall(new Integer[]{1, 4, 5});
        deedPoll.setIHaveSubscribed(new Integer[]{2});
        deedPoll.setAndAlsoMyOld(new Integer[]{});
        deedPoll.setRefundAmount(new BigDecimal("1234.56"));
        deedPoll.setSignatureReceivedDate(LocalDateTime.of(2026, 3, 4, 9, 30));
        deedPoll.setMigratedRecord(true);

        entityManager.persist(deedPoll);
        entityManager.flush();
        entityManager.clear();

        DeedPoll reloaded = entityManager.find(DeedPoll.class, deedPoll.getId());
        assertThat(reloaded.getId()).as("id is allocated by the application, not the database").isNotBlank();
        assertThat(reloaded.getType()).isEqualTo(DeedPollType.DeedOfRelinquishment);
        assertThat(reloaded.getState()).isEqualTo(DeedPollState.AwaitingSignature);
        assertThat(reloaded.getPrivacy()).isEqualTo(Privacy.Private);
        assertThat(reloaded.getUrgency()).isEqualTo(Urgency.Urgent);
        assertThat(reloaded.getPostCounter()).isEqualTo(PostCounter.Post);
        assertThat(reloaded.getMaritalStatus()).isEqualTo(MaritalStatus.MarriedCivilPartnership);
        assertThat(reloaded.getNationality()).isEqualTo(Nationality.CommonwealthCitizen);
        assertThat(reloaded.getNationalitySection()).isEqualTo(NationalitySection.Section11);
        assertThat(reloaded.getReferToMasterChoice()).isEqualTo(ReferToMaster.Yes);
        assertThat(reloaded.getMasterDecision()).isEqualTo(MasterDecision.Approved);
        assertThat(reloaded.getCurrentReferralState()).isEqualTo(ReferralState.Decided);
        assertThat(reloaded.getIShall()).containsExactly(1, 4, 5);
        assertThat(reloaded.getIHaveSubscribed()).containsExactly(2);
        assertThat(reloaded.getAndAlsoMyOld()).isEmpty();
        // The scale matters: numeric(12,2) means a refund of 1234.56 comes back as 1234.56 and not
        // as 1234.5600 or 1234.6.
        assertThat(reloaded.getRefundAmount()).isEqualTo(new BigDecimal("1234.56"));
        assertThat(reloaded.getSignatureReceivedDate())
            .as("read back from the misspelled source column")
            .isEqualTo(LocalDateTime.of(2026, 3, 4, 9, 30));
        assertThat(reloaded.isMigratedRecord()).isTrue();
        assertThat(reloaded.isPublishToLondonGazette()).as("the reference defaults this to true").isTrue();
        assertThat(reloaded.getCreatedOn())
            .as("written by @PrePersist, as the reference's @default(now())").isNotNull();
        assertThat(reloaded.getModifiedOn()).as("NOT NULL with no database default").isNotNull();
    }

    @Test
    @DisplayName("the children round trip, including their parent relations")
    void theChildrenRoundTrip() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        entityManager.persist(deedPoll);

        DeedPollNote note = new DeedPollNote();
        note.setDeedPoll(deedPoll);
        note.setNote("Called the applicant to confirm the spelling.");
        note.setCreatedBy("A Caseworker");
        entityManager.persist(note);

        DeedPollWitness witness = new DeedPollWitness();
        witness.setDeedPoll(deedPoll);
        witness.setWitnessName("W Witness");
        witness.setWitnessAddress("1 Test Street, Testtown");
        witness.setWitnessOccupation("Solicitor");
        witness.setDeclarant(true);
        entityManager.persist(witness);

        DeedPollSupportingDocumentType documentType = new DeedPollSupportingDocumentType();
        documentType.setName("Birth certificate");
        documentType.setApplicableMaritalStatuses(new Integer[]{1, 2});
        documentType.setApplicableTypes(new Integer[]{3, 4});
        entityManager.persist(documentType);

        DeedPollSupportingDocument document = new DeedPollSupportingDocument();
        document.setDeedPoll(deedPoll);
        document.setDocumentType(documentType);
        document.setDirection(DocumentDirection.Incoming);
        document.setName("Birth certificate");
        document.setStorageKey("local/2026/birth-certificate.pdf");
        document.setOriginalName("birth certificate.pdf");
        document.setMimeType("application/pdf");
        document.setSizeBytes(20_481);
        entityManager.persist(document);

        ReferenceCounter counter = new ReferenceCounter();
        counter.setYear(2026);
        counter.setLastSerial(41);
        entityManager.persist(counter);

        entityManager.flush();
        entityManager.clear();

        DeedPoll reloaded = entityManager.find(DeedPoll.class, deedPoll.getId());
        assertThat(reloaded.getNotes()).extracting(DeedPollNote::getNote)
            .containsExactly("Called the applicant to confirm the spelling.");
        assertThat(reloaded.getWitnesses()).extracting(DeedPollWitness::isDeclarant).containsExactly(true);
        assertThat(reloaded.getDocuments()).singleElement().satisfies(reloadedDocument -> {
            assertThat(reloadedDocument.getDirection()).isEqualTo(DocumentDirection.Incoming);
            assertThat(reloadedDocument.getSizeBytes()).isEqualTo(20_481);
            assertThat(reloadedDocument.getDocumentType().getName()).isEqualTo("Birth certificate");
            assertThat(reloadedDocument.getDocumentType().getApplicableMaritalStatuses()).containsExactly(1, 2);
            assertThat(reloadedDocument.getDocumentType().getApplicableTypes()).containsExactly(3, 4);
            // V25's columns, as a row written by the filesystem store leaves them.
            assertThat(reloadedDocument.getStorageState()).isEqualTo(DocumentStorageState.Local);
            assertThat(reloadedDocument.getStorageUploadAttempts()).isZero();
            assertThat(reloadedDocument.getCdamDocumentId()).isNull();
            assertThat(reloadedDocument.getCdamHashToken()).isNull();
            assertThat(reloadedDocument.getStorageAttemptedOn()).isNull();
        });

        assertThat(entityManager.find(ReferenceCounter.class, 2026).getLastSerial()).isEqualTo(41);
    }

    @Test
    @DisplayName("a saved query round trips, conditions included, and its selections cascade on delete")
    void savedQueryRoundTrips() {
        SavedQuery query = new SavedQuery();
        query.setName("Enroled this month -- Ops");
        query.setDescription("What the Ops team watches");
        query.setShared(true);
        query.setOwnerId("subject-of-a-token");
        query.setOwnerName("An Owner");
        query.setFilter("enroled");
        // The wire strings the list URL carried, stored as they arrived -- which is what lets
        // CaseQuery.parse validate a stored query with no adapter. Two of them, so a mapping that
        // silently kept only the first would fail here.
        query.setConditions(List.of("state:in:Enroled,Submitted", "newSurname:contains:Smith"));
        query.setSortField("createdOn");
        query.setSortDirection("desc");
        query.setSearchTerm("Smith");
        entityManager.persist(query);

        entityManager.persist(new SavedQuerySelection(query.getId(), "user-one"));
        entityManager.persist(new SavedQuerySelection(query.getId(), "user-two"));

        entityManager.flush();
        entityManager.clear();

        SavedQuery reloaded = entityManager.find(SavedQuery.class, query.getId());
        assertThat(reloaded.getId()).as("a uuid column, generated by the application").isNotNull();
        assertThat(reloaded.getName()).isEqualTo("Enroled this month -- Ops");
        assertThat(reloaded.getDescription()).isEqualTo("What the Ops team watches");
        assertThat(reloaded.isShared()).isTrue();
        assertThat(reloaded.getOwnerId()).isEqualTo("subject-of-a-token");
        assertThat(reloaded.getOwnerName()).isEqualTo("An Owner");
        assertThat(reloaded.getFilter()).isEqualTo("enroled");
        // Order as well as contents: `where` is a repeated parameter and CaseQuery.parse addresses its
        // errors to where.{index}, so a jsonb mapping that reordered would misattribute them.
        assertThat(reloaded.getConditions())
            .as("read back out of jsonb, in the order they were stored")
            .containsExactly("state:in:Enroled,Submitted", "newSurname:contains:Smith");
        assertThat(reloaded.getSortField()).isEqualTo("createdOn");
        assertThat(reloaded.getSortDirection()).isEqualTo("desc");
        assertThat(reloaded.getSearchTerm()).isEqualTo("Smith");
        assertThat(reloaded.getCreatedOn()).as("written by @PrePersist").isNotNull();
        assertThat(reloaded.getModifiedOn()).as("NOT NULL with no database default").isNotNull();

        // Per user, and one user's selections are not the other's -- the composite key is what says so.
        assertThat(selectionsOf("user-one")).containsExactly(query.getId());
        assertThat(selectionsOf("user-two")).containsExactly(query.getId());

        // Decision 2 lets an owner delete a shared query others have selected, so the rows have to go with
        // it: an orphan would resurface as a broken navigation entry. The cascade is the database's, so
        // this needs the delete to actually reach it.
        entityManager.remove(reloaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(selectionsOf("user-one")).isEmpty();
        assertThat(selectionsOf("user-two")).isEmpty();
    }

    @Test
    @DisplayName("a saved query stores no conditions as an empty array rather than null")
    void savedQueryStoresNoConditionsAsAnEmptyArray() {
        // The commonest saved query of all: a filter and a sort with no conditions. The column is NOT NULL,
        // so a mapping that wrote null for an empty list would fail on the first one a caseworker saved.
        SavedQuery query = new SavedQuery();
        query.setName("Everything, newest first");
        query.setOwnerId("subject-of-a-token");
        query.setOwnerName("An Owner");

        entityManager.persist(query);
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(SavedQuery.class, query.getId()).getConditions()).isEmpty();
    }

    @Test
    @DisplayName("a referral round trips, reasons included, and its parent relation holds")
    void referralRoundTrips() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 2, 10, 0));
        // The case's own urgency, which the referral's is deliberately free to differ from.
        deedPoll.setUrgency(Urgency.Normal);
        entityManager.persist(deedPoll);

        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Withdrawn);
        // Two of them, in this order, so a jsonb mapping that kept only the first or reordered fails here.
        referral.setReasons(List.of(ReferralReason.Minor.name(), ReferralReason.Other.name()));
        referral.setNotes("Applicant is 15; guardian consent is contested.");
        referral.setUrgency(Urgency.Urgent);
        referral.setUrgencyReason("Hearing listed on Friday.");
        referral.setCreatedBy("A Caseworker");
        referral.setModifiedBy("A Caseworker");
        entityManager.persist(referral);

        entityManager.flush();
        entityManager.clear();

        Referral reloaded = entityManager.find(Referral.class, referral.getId());
        assertThat(reloaded.getId()).as("a uuid column, generated by the application").isNotNull();
        assertThat(reloaded.getDeedPoll().getId())
            .as("scoped to its parent case, which every finder requires")
            .isEqualTo(deedPoll.getId());
        // The two native enum columns. Without @JdbcTypeCode(NAMED_ENUM) Hibernate sends an ordinal and
        // Postgres refuses it, so these two assertions are what prove the annotation is there.
        assertThat(reloaded.getState()).isEqualTo(ReferralState.Withdrawn);
        assertThat(reloaded.getUrgency())
            .as("the referral's own urgency, not the case's")
            .isEqualTo(Urgency.Urgent);
        assertThat(reloaded.getReasons())
            .as("read back out of jsonb, in the order they were stored")
            .containsExactly("Minor", "Other");
        assertThat(reloaded.getNotes()).isEqualTo("Applicant is 15; guardian consent is contested.");
        // "urgencyReason" is mixed case, which is validation's blind spot -- Hibernate compares column
        // names case-insensitively, so only a statement that quotes the name and runs finds a mis-cased
        // mapping.
        assertThat(reloaded.getUrgencyReason()).isEqualTo("Hearing listed on Friday.");
        assertThat(reloaded.isMigrated()).as("raised by a caseworker, not backfilled").isFalse();
        assertThat(reloaded.getCreatedBy()).isEqualTo("A Caseworker");
        assertThat(reloaded.getCreatedOn()).as("written by @PrePersist").isNotNull();
        assertThat(reloaded.getModifiedOn()).as("NOT NULL with no database default").isNotNull();
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getUrgency())
            .as("the case's urgency is untouched by the referral's")
            .isEqualTo(Urgency.Normal);
    }

    @Test
    @DisplayName("a referral defaults to Open with no reasons, and goes when its case goes")
    void referralDefaultsToOpenAndCascadesWithItsCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 3, 10, 0));
        entityManager.persist(deedPoll);

        // The shape create produces before any transition: Open, normal urgency, and -- for a backfilled
        // one -- no reasons at all. The column is NOT NULL, so a mapping that wrote null for an empty list
        // would fail on the first one.
        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        entityManager.persist(referral);
        entityManager.flush();
        entityManager.clear();

        UUID id = referral.getId();
        Referral reloaded = entityManager.find(Referral.class, id);
        assertThat(reloaded.getState()).isEqualTo(ReferralState.Open);
        assertThat(reloaded.getUrgency()).isEqualTo(Urgency.Normal);
        assertThat(reloaded.getReasons()).isEmpty();
        assertThat(reloaded.getNotes()).isNull();
        // V8's eight columns, all null on a referral nobody has picked up: an Open referral has no claim
        // and no direction, and neither does a backfilled one. Asserted rather than assumed, because a
        // NOT NULL or a default on any of them would have to invent a value for both cases -- and
        // publishToGazette is the one where an invented false would read as a real judicial answer.
        assertThat(reloaded.getClaimedBy()).isNull();
        assertThat(reloaded.getClaimedAt()).isNull();
        assertThat(reloaded.getLastTouchedAt()).isNull();
        assertThat(reloaded.getDecision()).isNull();
        assertThat(reloaded.getDirectionsText()).isNull();
        assertThat(reloaded.getPublishToGazette()).isNull();
        assertThat(reloaded.getDecisionDate()).isNull();
        assertThat(reloaded.getDecidedBy()).isNull();
        // And V14's two owner columns, for a stronger reason than the eight above: a NOT NULL or a default on
        // either would have to invent an owner, and an invented owner is a person who may direct a referral and
        // see the case behind it. Null is "nobody owns this", which is what every ownership check fails closed on.
        assertThat(reloaded.getClaimedById()).isNull();
        assertThat(reloaded.getDecidedById()).isNull();
        // And V16's four confirmation columns, which is the shape of EVERY referral that predates that
        // migration: none of them was ever asked to confirm anything, so a NOT NULL or a default on any of
        // them would have had to invent both a request date and an applicant's answer for all of them.
        // Asserted as a readability test as much as a mapping one -- a referral written before V16 must
        // still load, and this is one.
        assertThat(reloaded.getConfirmationRequestedAt())
            .as("never asked, which is what makes recording an answer refusable rather than valid")
            .isNull();
        assertThat(reloaded.getConfirmationOutcome())
            .as("null is outstanding, and the only thing null here means")
            .isNull();
        assertThat(reloaded.getConfirmationReceivedAt()).isNull();
        assertThat(reloaded.getConfirmationRecordedBy()).isNull();

        // And V9's column defaults to null rather than to a state, which is what makes "no active referral"
        // expressible at all. Null here even though the referral above is Open, because persisting a
        // referral row is not what maintains the case's copy -- the transitions are, and this test performs
        // none. A DEFAULT on the column would have had to name a state every case starts in, and there is
        // none: the great majority of cases are never referred.
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getCurrentReferralState())
            .as("a case with no transition behind it has no current referral state")
            .isNull();

        // A referral has no meaning without its case, and V6's ON DELETE CASCADE is the database's, so
        // this needs the delete to actually reach it. An orphan would be a referral in the judicial pool
        // pointing at a case that no longer exists.
        entityManager.remove(entityManager.find(DeedPoll.class, deedPoll.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Referral.class, id)).isNull();
    }

    @Test
    @DisplayName("a claimed and decided referral round trips its claim and its direction")
    void referralClaimAndDirectionRoundTrip() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 4, 10, 0));
        // The case's own snapshot of a historical decision, set to the other value so that a mapping which
        // somehow read the case's column instead of the referral's would disagree with the assertion below.
        deedPoll.setMasterDecision(MasterDecision.Rejected);
        deedPoll.setPublishToLondonGazette(false);
        entityManager.persist(deedPoll);

        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Decided);
        referral.setClaimedBy("District Judge Example");
        // V14's owner columns. Deliberately unlike the display names beside them, because that is the whole point
        // of the pair: a subject is not a name, and a mapping that had crossed the two would read as correct if
        // the fixture used the same value for both.
        referral.setClaimedById("idam-subject-9f3");
        referral.setClaimedAt(LocalDateTime.of(2026, 4, 1, 9, 30));
        referral.setLastTouchedAt(LocalDateTime.of(2026, 4, 1, 11, 15));
        referral.setDecision(MasterDecision.Approved);
        referral.setDirectionsText("Approved. Publish in the usual form; no further evidence required.");
        referral.setPublishToGazette(true);
        referral.setDecisionDate(LocalDateTime.of(2026, 4, 1, 11, 15));
        referral.setDecidedBy("District Judge Example");
        referral.setDecidedById("idam-subject-9f3");
        entityManager.persist(referral);

        entityManager.flush();
        entityManager.clear();

        Referral reloaded = entityManager.find(Referral.class, referral.getId());
        // 'Decided' and 'Approved': the third and fourth native enum columns on this entity, and the two
        // that only a transition reaches. Without @JdbcTypeCode(NAMED_ENUM) on "decision" Hibernate sends
        // an integer ordinal and Postgres refuses it, so this assertion is what proves the annotation.
        assertThat(reloaded.getState()).isEqualTo(ReferralState.Decided);
        assertThat(reloaded.getDecision())
            .as("the referral's direction, not the case's ported snapshot")
            .isEqualTo(MasterDecision.Approved);
        // Every one of these column names is mixed case, which is validation's blind spot: Hibernate
        // compares them case-insensitively, so only a statement that quotes the name and runs finds a
        // mis-cased mapping.
        assertThat(reloaded.getClaimedBy()).isEqualTo("District Judge Example");
        // "claimedById" and "decidedById" are mixed case too, and they are the two columns where a mis-cased
        // mapping would be a security failure rather than a missing field: an owner that did not persist reads
        // back as null, and a null owner is a referral its own judge can no longer see or direct.
        assertThat(reloaded.getClaimedById()).isEqualTo("idam-subject-9f3");
        assertThat(reloaded.getDecidedById()).isEqualTo("idam-subject-9f3");
        assertThat(reloaded.getClaimedAt()).isEqualTo(LocalDateTime.of(2026, 4, 1, 9, 30));
        assertThat(reloaded.getLastTouchedAt())
            .as("the sweep's input, later than the claim because the judge kept working")
            .isEqualTo(LocalDateTime.of(2026, 4, 1, 11, 15));
        assertThat(reloaded.getDirectionsText())
            .isEqualTo("Approved. Publish in the usual form; no further evidence required.");
        assertThat(reloaded.getPublishToGazette()).isTrue();
        assertThat(reloaded.getDecisionDate()).isEqualTo(LocalDateTime.of(2026, 4, 1, 11, 15));
        assertThat(reloaded.getDecidedBy()).isEqualTo("District Judge Example");

        // V8 adds columns and touches nothing on the case. The flat block stays the pre-migration
        // snapshot, so recording a direction on the referral leaves it saying what it always said.
        DeedPoll reloadedCase = entityManager.find(DeedPoll.class, deedPoll.getId());
        assertThat(reloadedCase.getMasterDecision()).isEqualTo(MasterDecision.Rejected);
        assertThat(reloadedCase.isPublishToLondonGazette()).isFalse();
    }

    /**
     * Parameterized rather than a {@code @TestFactory} like the enum-member factory above: a dynamic test's
     * executable runs after the factory method has returned, which is outside the class's
     * {@code @Transactional} boundary, so it would have no transaction to persist in.
     */
    @ParameterizedTest(name = "{0}")
    @EnumSource(ConfirmationOutcome.class)
    @DisplayName("every ConfirmationOutcome round trips through V16's native enum column")
    void everyConfirmationOutcomeRoundTrips(ConfirmationOutcome outcome) {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 7, 10, 0));
        // The state a case that got ④ is actually in: Returned, which the cascade derives from this date.
        // Set so that the assertion at the end of this test has something to be untouched.
        deedPoll.setDateReturned(LocalDateTime.of(2026, 5, 3, 14, 0));
        entityManager.persist(deedPoll);

        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Decided);
        referral.setDecision(MasterDecision.Rejected);
        referral.setPublishToGazette(true);
        referral.setConfirmationRequestedAt(LocalDateTime.of(2026, 5, 3, 14, 5));
        referral.setConfirmationOutcome(outcome);
        referral.setConfirmationReceivedAt(LocalDateTime.of(2026, 6, 15, 9, 45));
        referral.setConfirmationRecordedBy("A Caseworker");
        entityManager.persist(referral);

        entityManager.flush();
        entityManager.clear();

        Referral reloaded = entityManager.find(Referral.class, referral.getId());
        // The fifth native enum column in this schema. Without @JdbcTypeCode(NAMED_ENUM) Hibernate sends an
        // integer ordinal and Postgres refuses it, so this assertion is what proves the annotation is there
        // -- and it is made for BOTH members, because a mapping that stored the ordinal of the first member
        // could otherwise pass by accident.
        assertThat(reloaded.getConfirmationOutcome()).isEqualTo(outcome);
        // All four column names are mixed case, which is validation's blind spot: Hibernate compares them
        // case-insensitively, so only a statement that quotes the name and runs finds a mis-cased mapping.
        assertThat(reloaded.getConfirmationRequestedAt())
            .as("when the clock started, stamped with the outbox row rather than on delivery")
            .isEqualTo(LocalDateTime.of(2026, 5, 3, 14, 5));
        assertThat(reloaded.getConfirmationReceivedAt())
            .as("when the caseworker recorded it, not when the applicant replied")
            .isEqualTo(LocalDateTime.of(2026, 6, 15, 9, 45));
        assertThat(reloaded.getConfirmationRecordedBy())
            .as("attribution, with no matching subject column because nothing compares it")
            .isEqualTo("A Caseworker");

        // V16 adds columns and touches nothing on the case. Recording an outcome does not reach into the
        // date cascade -- there is no ninth DeedPollState and no new branch in deriveState -- so a confirmed
        // case is still Returned until a caseworker edits it, which is decision 6.
        assertThat(entityManager.find(DeedPoll.class, deedPoll.getId()).getDateReturned())
            .as("the date the cascade derives the state from, unmoved by the answer")
            .isEqualTo(LocalDateTime.of(2026, 5, 3, 14, 0));
    }

    @Test
    @DisplayName("the referral index covers exactly the referrals still waiting for an answer, and no others")
    void awaitingConfirmationIndexIsPartial() {
        // The whole of decision 7 is that "overdue" is a WHERE clause rather than a sweep, and this index is
        // what makes that clause cheap. Asserted rather than assumed because ddl-auto: validate does not
        // look at indexes at all, so a migration that created it on the wrong column -- or without the
        // predicate, indexing the answered rows nobody queries -- would be silent in every other test.
        String definition = indexDefinition("deedpoll_referral_awaiting_confirmation_idx");

        assertThat(definition).contains("\"confirmationRequestedAt\"");
        // BOTH halves of "outstanding", which is V18's correction of V16: a B-tree stores NULL keys, so the
        // outcome predicate alone admitted every referral nobody ever asked -- which is almost the whole table,
        // and none of them rows this index can return. A predicate that lost either half again would be silent
        // everywhere else.
        assertThat(definition)
            .as("partial, so the index holds only the rows a caseworker can still act on")
            .contains("WHERE ((\"confirmationOutcome\" IS NULL) AND (\"confirmationRequestedAt\" IS NOT NULL))");
    }

    @Test
    @DisplayName("V17's case index covers exactly the cases still waiting, in the order the tab reads them")
    void awaitingConfirmationCaseIndexIsPartial() {
        // The case side of the same fact, asserted for the same reason as the referral side: this is the index
        // the awaitingConfirmation tab's filter and its longest-wait-first ordering both rest on, and an index
        // created on the wrong column or without the predicate degrades the tab to a scan and a sort with
        // nothing failing.
        String definition = indexDefinition("deedpoll_deedpoll_awaiting_confirmation_idx");

        assertThat(definition).contains("\"awaitingConfirmationSince\"");
        assertThat(definition)
            .as("partial, so the index holds only the cases the waiting list shows")
            .contains("WHERE (\"awaitingConfirmationSince\" IS NOT NULL)");
    }

    /** One index's definition as Postgres reports it -- the only way to see a predicate validate never reads. */
    private String indexDefinition(String name) {
        return entityManager
            .createNativeQuery("SELECT indexdef FROM pg_indexes WHERE indexname = :name")
            .setParameter("name", name)
            .getSingleResult()
            .toString();
    }

    @Test
    @DisplayName("a notification round trips its personalisation, its state and both provider ids")
    void notificationRoundTrips() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 5, 10, 0));
        entityManager.persist(deedPoll);

        Referral referral = new Referral();
        referral.setDeedPoll(deedPoll);
        referral.setState(ReferralState.Decided);
        referral.setDecision(MasterDecision.Rejected);
        entityManager.persist(referral);

        UUID providerId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Notification notification = new Notification();
        notification.setDeedPollId(deedPoll.getId());
        notification.setRequestId(requestId);
        notification.setReferralId(referral.getId());
        notification.setTemplate("APPLICATION_REJECTED");
        notification.setExclusionGroup("REFERRAL_OUTCOME");
        notification.setTemplateVersion(7);
        notification.setRecipient("applicant@example.com");
        // A nested value and a non-string one, because jsonb is the point: a mapping that stringified the
        // map or kept only its first entry passes a single-string fixture and fails this.
        notification.setPersonalisation(new LinkedHashMap<>(Map.of(
            "reference_number", "2026/000123",
            "judges_reason", "The evidence of use of the new name is insufficient.",
            "applied_online", "yes")));
        notification.setState(NotificationState.Sent);
        notification.setAttempts(2);
        notification.setNextAttemptAt(LocalDateTime.of(2026, 5, 1, 9, 0));
        notification.setProviderNotificationId(providerId);
        notification.setCreatedBy("A Caseworker");
        entityManager.persist(notification);

        entityManager.flush();
        entityManager.clear();

        Notification reloaded = entityManager.find(Notification.class, notification.getId());
        // The native enum column. Without @JdbcTypeCode(NAMED_ENUM) Hibernate sends an integer ordinal and
        // Postgres refuses it, so this assertion is what proves the annotation is there.
        assertThat(reloaded.getState()).isEqualTo(NotificationState.Sent);
        assertThat(reloaded.getPersonalisation())
            .as("read back out of jsonb, every entry of it")
            .containsEntry("reference_number", "2026/000123")
            .containsEntry("judges_reason", "The evidence of use of the new name is insufficient.")
            .containsEntry("applied_online", "yes");
        // Every one of these column names is mixed case, which is validation's blind spot: Hibernate
        // compares them case-insensitively, so only a statement that quotes the name and runs finds a
        // mis-cased mapping.
        assertThat(reloaded.getDeedPollId())
            .as("the parent is a bare id here, not a mapped relation -- see Notification")
            .isEqualTo(deedPoll.getId());
        assertThat(reloaded.getReferralId()).isEqualTo(referral.getId());
        assertThat(reloaded.getRequestId())
            .as("the caller's own identifier for the send, and half of V19's key")
            .isEqualTo(requestId);
        assertThat(reloaded.getTemplate())
            .as("the EmailTemplate member name, never the Notify UUID")
            .isEqualTo("APPLICATION_REJECTED");
        assertThat(reloaded.getExclusionGroup())
            .as("the EmailExclusionGroup member name, and the other half of V15's unique index")
            .isEqualTo("REFERRAL_OUTCOME");
        assertThat(reloaded.getTemplateVersion()).isEqualTo(7);
        assertThat(reloaded.getRecipient()).isEqualTo("applicant@example.com");
        assertThat(reloaded.getAttempts()).isEqualTo(2);
        assertThat(reloaded.getNextAttemptAt()).isEqualTo(LocalDateTime.of(2026, 5, 1, 9, 0));
        assertThat(reloaded.getProviderNotificationId()).isEqualTo(providerId);
        assertThat(reloaded.getCreatedBy())
            .as("which caseworker sent it, which is content rather than audit noise")
            .isEqualTo("A Caseworker");
        assertThat(reloaded.getCreatedOn()).as("written by @PrePersist").isNotNull();
        assertThat(reloaded.getModifiedOn()).as("NOT NULL with no database default").isNotNull();
    }

    @Test
    @DisplayName("a notification defaults to Pending and claimable, and goes when its case goes")
    void notificationDefaultsToPendingAndCascadesWithItsCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setDateReceived(LocalDateTime.of(2026, 1, 6, 10, 0));
        entityManager.persist(deedPoll);

        // The shape enqueue produces: Pending, no attempts, no referral, and an empty personalisation. The
        // column is NOT NULL, so a mapping that wrote null for an empty map would fail on the first one.
        Notification notification = new Notification();
        notification.setDeedPollId(deedPoll.getId());
        notification.setRequestId(UUID.randomUUID());
        notification.setTemplate("APPLICATION_REJECTED");
        notification.setExclusionGroup("REFERRAL_OUTCOME");
        notification.setRecipient("applicant@example.com");
        notification.setCreatedBy("A Caseworker");
        entityManager.persist(notification);
        entityManager.flush();
        entityManager.clear();

        UUID id = notification.getId();
        Notification reloaded = entityManager.find(Notification.class, id);
        assertThat(reloaded.getState()).isEqualTo(NotificationState.Pending);
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getPersonalisation()).isEmpty();
        assertThat(reloaded.getReferralId()).as("not every template is referral-driven").isNull();
        assertThat(reloaded.getTemplateVersion()).as("nothing previewed it, so no version is claimed").isNull();
        assertThat(reloaded.getProviderNotificationId()).isNull();
        assertThat(reloaded.getFailureReason()).isNull();
        // Claimable the instant it is enqueued, which is what makes "the operation that enqueues also
        // drains" work. @PrePersist writes it as well as the column's own DEFAULT, so the value is readable
        // from the entity without a refresh.
        assertThat(reloaded.getNextAttemptAt())
            .as("due immediately, so the very next drain can take it")
            .isNotNull()
            .isEqualTo(reloaded.getCreatedOn());

        // A notification has no meaning without its case, and V11's ON DELETE CASCADE is the database's, so
        // this needs the delete to actually reach it.
        entityManager.remove(entityManager.find(DeedPoll.class, deedPoll.getId()));
        entityManager.flush();
        entityManager.clear();

        assertThat(entityManager.find(Notification.class, id)).isNull();
    }

    private List<UUID> selectionsOf(String userId) {
        return entityManager
            .createQuery("select s from SavedQuerySelection s where s.id.userId = :userId",
                         SavedQuerySelection.class)
            .setParameter("userId", userId)
            .getResultList()
            .stream()
            .map(selection -> selection.getId().getSavedQueryId())
            .toList();
    }
}
