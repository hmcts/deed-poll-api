package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * The conversions a bound deed field makes from a column to the text the deed prints.
 *
 * <p>Four rules, and they are the same four the email personalisation applies -- see
 * {@code ResolvedValues}, which is package-private in {@code notify} on the stated ground that its
 * vocabulary is the resolvers' own. This is a second surface with the same obligations and no shared
 * abstraction: {@code DeedValuesTest} pins the date pattern to the one an applicant reads elsewhere, so the
 * two cannot drift into printing a date one way in an email and another way on the deed the email carries.
 *
 * <p><strong>A null column becomes an empty string, never the text {@code null} and never a substitute
 * value.</strong> The deed is signed in wet ink on paper, so a blank is a line the applicant can complete by
 * hand -- which is what the paper process always was -- whereas a plausible-looking wrong value on a legal
 * instrument is not recoverable by the person holding it.
 */
final class DeedValues {

    /**
     * The one date format a member of the public sees from this service.
     *
     * <p>{@code d MMMM yyyy}, as {@code CaseHeader}, {@code DetailView} and the email personalisation all
     * render a date, and {@link Locale#UK} so the month is named in English whatever the JVM default locale of
     * the pod that happened to generate the file.
     */
    private static final DateTimeFormatter DEED_DATE = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private DeedValues() {
    }

    /** A nullable text column as the deed prints it: the text, or nothing at all. */
    static String text(String value) {
        return value == null ? "" : value;
    }

    /**
     * A nullable timestamp as the date the deed prints, or nothing at all when the column is empty.
     *
     * <p><strong>No fallback column and no clock.</strong> {@code dateReceived} is the only date this service
     * holds that answers "when was this application made", and the state cascade treats it as optional, so a
     * case can reach a send without one. The alternatives were all worse: {@code createdOn} is an audit column
     * and for a migrated case it is the migration date, and {@code now()} would assert a fact about the case
     * that nobody recorded. A blank line on a deed the applicant completes in wet ink is the honest answer, and
     * it is what LOC020 was designed for.
     */
    static String date(LocalDateTime value) {
        return value == null ? "" : DEED_DATE.format(value);
    }

    /** A nullable whole-number column as text, or nothing at all. */
    static String number(Integer value) {
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * An option-set column as its human label, or nothing at all when it is unset.
     *
     * <p>The label rather than {@link Enum#name()}, because the deed is read by the applicant and by the court:
     * Dataverse bound these controls to the option's label, so {@code MarriedCivilPartnership} printed as
     * "Married / Civil Partnership" and must go on doing so. A member with no label is an
     * {@link IllegalStateException} rather than a fallback to the name, because a deed that printed
     * {@code DivorcedDissolved} would be wrong in a way nobody would notice until it was sealed.
     */
    static <E extends Enum<E>> String label(E member, Map<E, String> labels) {
        if (member == null) {
            return "";
        }
        String label = labels.get(member);
        if (label == null) {
            throw new IllegalStateException(
                "No label for " + member.getDeclaringClass().getSimpleName() + "." + member.name()
                    + ", so the deed would print its member name");
        }
        return label;
    }
}
