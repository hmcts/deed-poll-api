package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Populating a case so that every field of a response DTO carries a value no other field could have.
 *
 * <p>Shared by {@link CaseDetailResponseFieldsTest} and {@link SavedCaseResponseFieldsTest}, which ask the same
 * question of two records whose canonical constructors take seventy-nine and seventy-six positional arguments:
 * two same-typed arguments transposed by a careless edit compile silently and swap two fields on every response.
 * One fixture rather than two copies, because a divergence between them would be a difference in how carefully
 * the two DTOs are checked, which is not a difference anybody means to make.
 *
 * <p>Values are read off each component's declared type rather than listed per field, so a new column is covered
 * by being published -- the habit task 11 established, for the reason it gives: a hand-copied expectation agrees
 * with its source on the day it is written.
 */
final class CaseResponseFields {

    /** Derived on read, so not among the stored fields a coverage assertion compares against. */
    static final Set<String> DERIVED = Set.of("daysReceivedToEnroled", "daysReceivedToReturned");

    /** Fetched with the case, and mapped from the child entities rather than from a case column. */
    static final Set<String> CHILDREN = Set.of("notes", "witnesses", "documents");

    /**
     * Set directly rather than through a patch: {@link DeedPollFields} deliberately cannot write them.
     */
    static final Set<String> NOT_WRITABLE_BY_A_CALLER = Set.of(
        "id", "referencenumber", "displayReference", "createdOn", "modifiedOn", "createdBy", "modifiedBy");

    private static final LocalDateTime BASE = LocalDateTime.parse("2026-01-01T09:00:00");

    private CaseResponseFields() {
    }

    /** A case populated for one response type, and what each of that type's fields should then answer. */
    record Populated(DeedPoll record, Map<String, Object> expected) {
    }

    /** The component names a response record declares, in declaration order. */
    static List<String> componentNames(Class<? extends Record> responseType) {
        return Arrays.stream(responseType.getRecordComponents())
            .map(RecordComponent::getName)
            .toList();
    }

    /**
     * A case whose every stored field holds a value unique to it, for the given response type.
     *
     * <p>The four booleans are the awkward case -- four fields and two possible values cannot all differ in one
     * run -- so the vector for the run is supplied by the caller, which is what makes two runs able to give each
     * flag a distinct pair. Every element must be consumed, or the vector and the DTO have drifted apart and the
     * uniqueness the correspondence assertion rests on is no longer there.
     */
    static Populated populate(Class<? extends Record> responseType, boolean... flagVector) {
        Deque<Boolean> flags = new ArrayDeque<>();
        for (boolean flag : flagVector) {
            flags.addLast(flag);
        }

        DeedPoll record = new DeedPoll();
        Map<String, Object> expected = new LinkedHashMap<>();

        int ordinal = 0;
        for (RecordComponent component : responseType.getRecordComponents()) {
            String name = component.getName();
            if (DERIVED.contains(name) || CHILDREN.contains(name)) {
                continue;
            }
            ordinal++;
            Object value = valueFor(component, name, ordinal, flags);
            expected.put(name, value);
            set(record, name, value);
        }

        assertThat(flags).as("every flag vector element consumed by " + responseType.getSimpleName()).isEmpty();
        return new Populated(record, expected);
    }

    /**
     * Every field of a published response answers the value the case was populated with.
     */
    static void assertCarriesItsOwnValue(Class<? extends Record> responseType,
                                        Object published,
                                        Map<String, Object> expected) throws Exception {
        for (RecordComponent component : responseType.getRecordComponents()) {
            if (expected.containsKey(component.getName())) {
                assertThat(component.getAccessor().invoke(published))
                    .as(component.getName())
                    .isEqualTo(expected.get(component.getName()));
            }
        }
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
