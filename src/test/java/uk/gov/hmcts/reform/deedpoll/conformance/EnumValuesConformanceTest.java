package uk.gov.hmcts.reform.deedpoll.conformance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 3 -- enum-value conformance.
 *
 * <p>Ported from {@code reference/ts/conformance/enum-values.conformance.test.ts}. The migration's
 * promise (the vendored {@code deed-poll.prisma} header, and {@code DynamicsValues}) is that every
 * option-set member keeps its exact Dynamics integer, so data stays interoperable with a future
 * Dataverse re-import. This pins those integers: the expected values are transcribed from the
 * reference schema's enum comments, which are the documented source values. A drift in either the
 * value map or the schema comment trips this.
 *
 * <p>The transcription is then checked against the schema comments themselves -- see
 * {@link #everyMapAgreesWithTheReferenceSchemasComments()}, which is an addition. The reference
 * cannot read its own schema file from a unit test; this build already vendors it, so the two halves
 * of "the documented source value" can be held against each other instead of trusted.
 */
class EnumValuesConformanceTest {

    /** The eleven enums, kept as a reminder of what the table below must cover. */
    private static final List<Class<? extends Enum<?>>> COVERED_ENUMS = List.of(
        DeedPollType.class,
        DeedPollState.class,
        MaritalStatus.class,
        Privacy.class,
        Urgency.class,
        PostCounter.class,
        Nationality.class,
        NationalitySection.class,
        MasterDecision.class,
        ReferToMaster.class,
        DocumentDirection.class);

    private static PrismaSchema reference;

    /**
     * One option set: the port's value map, the integers the reference test expects, and the reverse
     * map as {@link DynamicsValues#reverse} builds it.
     *
     * <p>{@code actual} and {@code expected} are keyed by member NAME rather than by the enum, which
     * is what lets eleven differently-typed maps share one table. {@code reversed} is a supplier so
     * that {@code reverse} is still called at its concrete type -- the method under test takes
     * {@code Map<E, Integer>} and a wildcard would not compile.
     */
    private record EnumCase(String name,
                            Map<String, Integer> actual,
                            Map<String, Integer> expected,
                            Supplier<Map<Integer, ? extends Enum<?>>> reversed) {
    }

    @BeforeAll
    static void loadReference() {
        reference = PrismaSchema.loadReference();
    }

    private static List<EnumCase> cases() {
        return List.of(
            new EnumCase("DeedPollType",
                byName(DynamicsValues.DEED_POLL_TYPE_VALUE),
                Map.of(
                    "Adult", 1,
                    "AdultTransgender", 2,
                    "Minor", 3,
                    "MinorTransgender", 4,
                    "DeedOfRelinquishment", 5,
                    "Unknown", 6),
                () -> DynamicsValues.reverse(DynamicsValues.DEED_POLL_TYPE_VALUE)),
            new EnumCase("DeedPollState",
                byName(DynamicsValues.DEED_POLL_STATE_VALUE),
                Map.of(
                    "Received", 1,
                    "ReadyForMaster", 2,
                    "ReferredToMaster", 3,
                    "Enroled", 4,
                    "Returned", 5,
                    "AwaitingFurtherDocs", 6,
                    "AwaitingSignature", 7,
                    "AwaitingEnrolment", 8),
                () -> DynamicsValues.reverse(DynamicsValues.DEED_POLL_STATE_VALUE)),
            new EnumCase("MaritalStatus",
                byName(DynamicsValues.MARITAL_STATUS_VALUE),
                Map.of(
                    "Single", 1,
                    "MarriedCivilPartnership", 2,
                    "Widowed", 3,
                    "DivorcedDissolved", 4),
                () -> DynamicsValues.reverse(DynamicsValues.MARITAL_STATUS_VALUE)),
            new EnumCase("Privacy",
                byName(DynamicsValues.PRIVACY_VALUE),
                Map.of("Normal", 1, "Private", 2),
                () -> DynamicsValues.reverse(DynamicsValues.PRIVACY_VALUE)),
            new EnumCase("Urgency",
                byName(DynamicsValues.URGENCY_VALUE),
                Map.of("Normal", 1, "Urgent", 2),
                () -> DynamicsValues.reverse(DynamicsValues.URGENCY_VALUE)),
            new EnumCase("PostCounter",
                byName(DynamicsValues.POST_COUNTER_VALUE),
                Map.of("Counter", 1, "Online", 2, "Post", 3, "Unknown", 4),
                () -> DynamicsValues.reverse(DynamicsValues.POST_COUNTER_VALUE)),
            new EnumCase("Nationality",
                byName(DynamicsValues.NATIONALITY_VALUE),
                Map.of(
                    "BritishCitizen", 1,
                    "BritishDependantTerritoriesCitizen", 2,
                    "CommonwealthCitizen", 3,
                    "Unknown", 4),
                () -> DynamicsValues.reverse(DynamicsValues.NATIONALITY_VALUE)),
            new EnumCase("NationalitySection",
                byName(DynamicsValues.NATIONALITY_SECTION_VALUE),
                Map.of(
                    "Section1", 1,
                    "Section11", 2,
                    "Section37", 3,
                    "Section6", 4,
                    "Unknown", 5,
                    "NoneOfTheAbove", 6),
                () -> DynamicsValues.reverse(DynamicsValues.NATIONALITY_SECTION_VALUE)),
            new EnumCase("MasterDecision",
                byName(DynamicsValues.MASTER_DECISION_VALUE),
                Map.of("Approved", 1, "Rejected", 2),
                () -> DynamicsValues.reverse(DynamicsValues.MASTER_DECISION_VALUE)),
            new EnumCase("ReferToMaster",
                byName(DynamicsValues.REFER_TO_MASTER_VALUE),
                Map.of("Yes", 1, "No", 2),
                () -> DynamicsValues.reverse(DynamicsValues.REFER_TO_MASTER_VALUE)),
            new EnumCase("DocumentDirection",
                byName(DynamicsValues.DOCUMENT_DIRECTION_VALUE),
                Map.of("Incoming", 1, "Outgoing", 2),
                () -> DynamicsValues.reverse(DynamicsValues.DOCUMENT_DIRECTION_VALUE)));
    }

    @TestFactory
    @DisplayName("Dynamics integers preserved")
    List<DynamicTest> dynamicsIntegersPreserved() {
        List<DynamicTest> tests = new ArrayList<>();
        for (EnumCase one : cases()) {
            tests.add(DynamicTest.dynamicTest(
                "%s maps every member to its documented Dynamics integer".formatted(one.name()),
                () -> assertThat(one.actual())
                    .containsExactlyInAnyOrderEntriesOf(one.expected())));

            tests.add(DynamicTest.dynamicTest(
                "%s integers are unique (reverse map is lossless)".formatted(one.name()),
                () -> assertThat(one.reversed().get()).hasSameSizeAs(one.actual())));
        }
        return tests;
    }

    /**
     * Not a port -- an addition. The reference's expected integers are, by its own note, transcribed
     * from the schema's enum comments; nothing checks the transcription against them. The schema is
     * vendored here, and its header states "Comment on each member = the Dynamics integer value", so
     * the comments are specification and can be read directly. Without this, a member added to both
     * the schema and the Java enum but forgotten in the value map would leave the reference's
     * hand-written expectation as the only record of the integer -- and it would agree with the map,
     * because they were written together.
     */
    @TestFactory
    @DisplayName("every map agrees with the reference schema's comments")
    List<DynamicTest> everyMapAgreesWithTheReferenceSchemasComments() {
        return cases().stream()
            .map(one -> DynamicTest.dynamicTest(
                "%s matches the integers deed-poll.prisma documents".formatted(one.name()),
                () -> assertThat(one.actual())
                    .as("the value map for %s and the reference schema's comments disagree",
                        one.name())
                    .containsExactlyInAnyOrderEntriesOf(
                        reference.enumDynamicsValues().get(one.name()))))
            .toList();
    }

    /**
     * Re-keys a value map by member name, so maps of eleven different enum types can be compared in
     * one table.
     */
    private static Map<String, Integer> byName(Map<? extends Enum<?>, Integer> map) {
        Map<String, Integer> byName = new LinkedHashMap<>();
        map.forEach((member, value) -> byName.put(member.name(), value));
        return Map.copyOf(byName);
    }

    /**
     * Not a port -- an addition, and the reason {@link #COVERED_ENUMS} exists. The reference's table is
     * hand-written, so an option set added to the schema and to the port could simply be left out of
     * it and nothing would notice. This asserts the table covers every enum the reference schema
     * declares, by name.
     */
    @TestFactory
    @DisplayName("every reference enum is covered by the table")
    List<DynamicTest> everyReferenceEnumIsCoveredByTheTable() {
        List<String> covered = cases().stream().map(EnumCase::name).toList();
        List<String> ported = COVERED_ENUMS.stream().map(Class::getSimpleName).toList();

        return reference.enums().keySet().stream()
            .map(name -> DynamicTest.dynamicTest(
                "%s is covered".formatted(name),
                () -> {
                    assertThat(covered).as("no conformance case for enum %s", name).contains(name);
                    assertThat(ported).as("no ported Java enum for %s", name).contains(name);
                }))
            .toList();
    }
}
