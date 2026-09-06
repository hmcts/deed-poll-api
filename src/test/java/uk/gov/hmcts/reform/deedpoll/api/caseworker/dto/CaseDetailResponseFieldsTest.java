package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ComputedFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * What {@link CaseDetailResponse} publishes, and that each field carries its own value.
 *
 * <p>Two questions, and both are read off the code rather than transcribed from it -- the habit task 11
 * established, because a hand-copied field list only agrees with its source on the day it is written.
 *
 * <ul>
 *   <li><strong>Coverage, in both directions.</strong> The published set is exactly
 *       {@link DeedPollFields}' readable names plus the derived counts and the three child collections. A
 *       column added to the case therefore has to be published deliberately or omitted deliberately; neither
 *       can happen by not noticing. And a field that should not be on the caseworker surface cannot appear
 *       here without the test saying so, which is the disclosure direction.</li>
 *   <li><strong>Correspondence.</strong> The canonical constructor takes seventy-nine positional arguments, of
 *       which thirty-five are {@code String}: two transposed by a careless edit compile silently and swap two
 *       fields on every response. So every field is populated with a value unique to it and the response is
 *       compared field by field.</li>
 * </ul>
 *
 * <p>Uniqueness is what makes the second assertion work, and the four flags are the awkward case -- four
 * fields and two possible values cannot all differ in one run. Hence two runs whose vectors give each flag a
 * distinct pair: a swap between any two of them changes the answer in at least one of the runs.
 */
class CaseDetailResponseFieldsTest {

    /** Derived on read, so not among the stored fields the coverage assertion compares against. */
    private static final Set<String> DERIVED = Set.of("daysReceivedToEnroled", "daysReceivedToReturned");

    /** Fetched with the case, and mapped from the child entities rather than from a case column. */
    private static final Set<String> CHILDREN = Set.of("notes", "witnesses", "documents");

    /**
     * Set directly rather than through a patch: {@link DeedPollFields} deliberately cannot write them.
     */
    private static final Set<String> NOT_WRITABLE_BY_A_CALLER = Set.of(
        "id", "referencenumber", "displayReference", "createdOn", "modifiedOn", "createdBy", "modifiedBy");

    private static final LocalDateTime BASE = LocalDateTime.parse("2026-01-01T09:00:00");

    @Test
    @DisplayName("publishes exactly the case's readable fields, its derived counts and its children")
    void publishesExactlyTheReadableFields() {
        Set<String> expected = new LinkedHashSet<>(DeedPollFields.readAll(new DeedPoll()).keySet());
        expected.addAll(DERIVED);
        expected.addAll(CHILDREN);

        assertThat(componentNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("does not publish the citizen-journey columns, which no caseworker form carries")
    void doesNotPublishTheCitizenJourneyColumns() {
        // Real columns on deedpoll_deedpoll, and deliberately not readable through DeedPollFields: they
        // belong to the public application journey, and the reference's case schema does not accept them
        // either. Named explicitly rather than left to the coverage assertion above, because "the DTO does
        // not leak the applicant's place of birth to a caseworker screen that never shows it" is the claim
        // worth being able to point at.
        assertThat(componentNames()).doesNotContain(
            "placeOfBirth",
            "nationalitySubject",
            "nationalitySectionProvenance",
            "parentRelationship",
            "parentAddress1",
            "parentAddress2",
            "parentTownCity",
            "parentPostcode",
            "parentCountry",
            "secondParentName",
            "secondParentRelationship");
    }

    @Test
    @DisplayName("the seven fields a caller cannot patch are exactly the ones this test sets directly")
    void theUnwritableFieldsAreTheExpectedSeven() {
        // Pins the split the correspondence test relies on. Were a field to become writable, or another to
        // stop being, the setter branch below would go stale and the coverage it claims would shrink quietly.
        Set<String> unwritable = new LinkedHashSet<>(DeedPollFields.readAll(new DeedPoll()).keySet());
        unwritable.removeIf(DeedPollFields::isWritable);

        assertThat(unwritable).containsExactlyInAnyOrderElementsOf(NOT_WRITABLE_BY_A_CALLER);
    }

    @ParameterizedTest(name = "flags {0} {1} {2} {3}")
    @CsvSource({"true,true,false,false", "true,false,true,false"})
    @DisplayName("maps every field to its own value, so no two are transposed")
    void mapsEveryFieldToItsOwnValue(boolean first, boolean second, boolean third, boolean fourth)
        throws Exception {

        Deque<Boolean> flags = new ArrayDeque<>(List.of(first, second, third, fourth));
        DeedPoll record = new DeedPoll();
        Map<String, Object> expected = new LinkedHashMap<>();

        int ordinal = 0;
        for (RecordComponent component : CaseDetailResponse.class.getRecordComponents()) {
            String name = component.getName();
            if (DERIVED.contains(name) || CHILDREN.contains(name)) {
                continue;
            }
            ordinal++;
            Object value = valueFor(component, name, ordinal, flags);
            expected.put(name, value);
            set(record, name, value);
        }
        assertThat(flags).as("every flag vector element consumed").isEmpty();

        CaseDetailResponse published =
            CaseDetailResponse.from(new DeedPollWithComputed(record, new ComputedFields(11, 22)));

        for (RecordComponent component : CaseDetailResponse.class.getRecordComponents()) {
            if (expected.containsKey(component.getName())) {
                assertThat(component.getAccessor().invoke(published))
                    .as(component.getName())
                    .isEqualTo(expected.get(component.getName()));
            }
        }

        assertThat(published.daysReceivedToEnroled()).isEqualTo(11);
        assertThat(published.daysReceivedToReturned()).isEqualTo(22);
        assertThat(published.notes()).isEmpty();
        assertThat(published.witnesses()).isEmpty();
        assertThat(published.documents()).isEmpty();
    }

    private static List<String> componentNames() {
        return Arrays.stream(CaseDetailResponse.class.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    /**
     * A value of the component's type that no other component is given.
     *
     * <p>Strings take the field's own name, everything countable takes its position, and the flags come from
     * the run's vector. The enum types are all distinct, so a transposition between two of them would not
     * compile and any member will do -- the last is chosen because the first is the default for two of them,
     * which a mapping that dropped the field would still produce.
     */
    private static Object valueFor(RecordComponent component, String name, int ordinal, Deque<Boolean> flags) {
        Class<?> type = component.getType();
        if (type == String.class) {
            return name;
        }
        if (type == Integer.class) {
            return ordinal;
        }
        if (type == boolean.class || type == Boolean.class) {
            return flags.removeFirst();
        }
        if (type == LocalDateTime.class) {
            return BASE.plusDays(ordinal);
        }
        if (type == BigDecimal.class) {
            return new BigDecimal("1234.56");
        }
        if (type == List.class) {
            return List.of(ordinal);
        }
        if (type.isEnum()) {
            Object[] members = type.getEnumConstants();
            return members[members.length - 1];
        }
        return fail("no test value generator for " + name + " of type " + type);
    }

    /**
     * Puts the value on the entity the way the application would.
     *
     * <p>Through {@link DeedPollFields} wherever a caller could, so the coercion a real request goes through
     * is exercised too, and by the setter only for the seven fields the registry deliberately does not expose.
     */
    private static void set(DeedPoll record, String name, Object value) {
        if (DeedPollFields.isWritable(name)) {
            DeedPollFields.write(record, name, value);
            return;
        }
        switch (name) {
            case "id" -> record.setId((String) value);
            case "referencenumber" -> record.setReferencenumber((Integer) value);
            case "displayReference" -> record.setDisplayReference((String) value);
            case "createdOn" -> record.setCreatedOn((LocalDateTime) value);
            case "modifiedOn" -> record.setModifiedOn((LocalDateTime) value);
            case "createdBy" -> record.setCreatedBy((String) value);
            case "modifiedBy" -> record.setModifiedBy((String) value);
            default -> fail(name + " is neither writable through DeedPollFields nor set here");
        }
    }
}
