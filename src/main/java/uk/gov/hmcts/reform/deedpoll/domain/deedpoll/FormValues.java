package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reading one value out of a submitted form body.
 *
 * <p>The reference declares the same private {@code str()} helper twice, in {@code form-payload.ts} and
 * {@code form-validation.ts}, and both files carry the same comment about it. They are collected here
 * because the two modules must agree on what a submitted part says: one trimming and one not is exactly
 * how a value passes validation and then stores as something else.
 *
 * <p>A repeated form field arrives as a list, which is why the first element is taken rather than the
 * value stringified -- {@code String.valueOf(List.of("1"))} is {@code "[1]"}, and a duplicated input
 * would then fail validation for looking like garbage.
 */
final class FormValues {

    private FormValues() {
    }

    /**
     * A possibly-list, possibly-absent body value as a trimmed string; {@code ""} when not text.
     */
    static String str(Object value) {
        Object single = value;
        if (single instanceof Collection<?> list) {
            single = list.isEmpty() ? null : list.iterator().next();
        } else if (single instanceof Object[] array) {
            single = array.length == 0 ? null : array[0];
        }
        return single instanceof String text ? text.trim() : "";
    }

    /**
     * A named part of a GOV.UK date input ({@code <name>-day} and friends).
     */
    static String datePart(Map<String, Object> body, String name, String suffix) {
        return str(body.get(name + "-" + suffix));
    }

    /**
     * A multi-checkbox body value as an integer list.
     *
     * <p>Anything that is not a whole number is dropped, matching the reference's
     * {@code .filter(Number.isInteger)}: the values are Dynamics option-set integers, and a non-integer
     * cannot be one.
     */
    static List<Integer> toIntList(Object value) {
        List<Object> raw = asList(value);
        return raw.stream()
            .map(FormValues::toInteger)
            .filter(number -> number != null)
            .toList();
    }

    private static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().map(Object.class::cast).toList();
        }
        if (value instanceof Object[] array) {
            return List.of(array);
        }
        return List.of(value);
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Integer number) {
            return number;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException notAnInteger) {
            return null;
        }
    }
}
