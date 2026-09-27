package uk.gov.hmcts.reform.deedpoll.ccd;

import java.util.regex.Pattern;

/**
 * The format of a CCD case reference as this service stores it: sixteen digits, held as a string.
 *
 * <p>A string at every boundary (plan 02, decision 7), because a reference treated as a number loses its
 * leading zeros and, in JSON, its precision. {@code V21__ccd_case_reference.sql}'s {@code CHECK} states the
 * same rule in SQL, and {@code CcdCaseReferenceTest} holds the two together by reading that file.
 *
 * <p>Format only. CCD also makes the last digit a Luhn check digit, and deliberately that is not re-checked
 * here: CCD is the authority on its own references, and a stricter rule than the database's would make a
 * reference Postgres accepts unbindable for a reason nothing else in the service agrees with.
 */
public final class CcdCaseReference {

    /**
     * Sixteen ASCII digits and nothing else -- the {@code CHECK} in V21, as a Java pattern.
     */
    public static final Pattern PATTERN = Pattern.compile("^[0-9]{16}$");

    private CcdCaseReference() {
    }

    /** Whether a value is a reference this service can store. */
    public static boolean isValid(String reference) {
        return reference != null && PATTERN.matcher(reference).matches();
    }

    /**
     * The value, if it is a reference this service can store.
     *
     * @throws IllegalArgumentException naming the rule, and not the value, which came from outside
     */
    public static String require(String reference) {
        if (!isValid(reference)) {
            throw new IllegalArgumentException("A CCD case reference is sixteen digits");
        }
        return reference;
    }
}
