package uk.gov.hmcts.reform.deedpoll.entity;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.reference.PrismaSchema;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
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
 * vacuous for an entity Hibernate never saw, the first test asserts all six are in the metamodel.
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
    @DisplayName("all six reference models are mapped, so Hibernate validated all six")
    void allSixModelsAreMapped() {
        Set<Class<?>> mapped = entityManager.getMetamodel().getEntities().stream()
            .map(EntityType::getJavaType)
            .collect(Collectors.toSet());

        assertThat(mapped).containsExactlyInAnyOrder(
            DeedPoll.class,
            DeedPollNote.class,
            DeedPollWitness.class,
            DeedPollSupportingDocumentType.class,
            DeedPollSupportingDocument.class,
            ReferenceCounter.class);
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
        });

        assertThat(entityManager.find(ReferenceCounter.class, 2026).getLastSerial()).isEqualTo(41);
    }
}
