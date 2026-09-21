package uk.gov.hmcts.reform.deedpoll.notify;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * The conversions a template's resolved personalisation makes from a column to a value Notify accepts.
 *
 * <p><strong>Shared rather than copied, and that is the whole reason this class exists.</strong> Two templates
 * now branch on how the applicant paid, and two emails that disagreed about who gets an automatic refund would
 * be a worse defect than either of them getting it wrong on its own -- one applicant would be promised a refund
 * by one email and asked for their bank details by the next. So each rule is stated once and every
 * personalisation record reads it from here.
 *
 * <p>Package-private: these are the resolvers' own vocabulary, not a general-purpose date or enum utility.
 * Nothing outside {@code notify} has any business asking whether a case is a minor's <em>for an email</em>.
 */
final class ResolvedValues {

    /**
     * The one date format a member of the public sees from this service.
     *
     * <p>{@code d MMMM yyyy}, as {@code CaseHeader} and {@code DetailView} render a date, and
     * {@link Locale#UK} so a month is named in English regardless of the JVM's default locale -- which is a
     * property of whichever pod happens to run the drain, and must not decide what an applicant reads.
     */
    private static final DateTimeFormatter APPLICANT_DATE =
        DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

    private ResolvedValues() {
    }

    /**
     * Which refund branch a template renders, from how the application reached the office.
     *
     * <p>{@link PostCounter#Online} is the only value that means the applicant paid online, so it is the only
     * one that gets the "we will refund the account you paid from" paragraph. {@link PostCounter#Counter},
     * {@link PostCounter#Post}, {@link PostCounter#Unknown} and an unset channel all get the paper paragraph,
     * <strong>and that asymmetry is the safe direction rather than an oversight</strong>: the paper paragraph
     * asks the applicant how they paid and where to send the refund, which recovers from a wrong guess, whereas
     * promising an automatic refund to a card nobody paid with leaves an applicant waiting for money that never
     * arrives.
     */
    static boolean appliedOnline(PostCounter postCounter) {
        return postCounter == PostCounter.Online;
    }

    /**
     * Whether a template addresses the applicant about a child's change of name rather than their own.
     *
     * <p>A {@code switch} with no {@code default}, so a member added to {@link DeedPollType} fails to compile
     * until somebody says which side of this sentence it falls on. The alternative -- {@code type == Minor} --
     * reads correctly and is wrong: {@link DeedPollType#MinorTransgender} is a child's application too, and
     * that spelling would address a parent as though the deed were their own.
     *
     * <p><strong>Everything that is not demonstrably a child's application takes the adult wording.</strong>
     * {@link DeedPollType#DeedOfRelinquishment}, {@link DeedPollType#Unknown} and an unset type are all cases
     * where this service does not know that a child is involved, and the adult wording addresses the applicant
     * about their own deed -- which is the reading that is merely impersonal if wrong, where the child wording
     * would refer an adult applicant to a child who does not exist.
     */
    static boolean isMinor(DeedPollType type) {
        if (type == null) {
            return false;
        }
        return switch (type) {
            case Minor, MinorTransgender -> true;
            case Adult, AdultTransgender, DeedOfRelinquishment, Unknown -> false;
        };
    }

    /**
     * A timestamp as the date an applicant reads, or nothing at all when there is none.
     *
     * <p>Formatted here rather than in the template because Notify has no date formatting: a placeholder
     * renders whatever string it is given, so an ISO timestamp would reach an applicant as
     * {@code 2026-09-18T14:32:07}.
     *
     * <p>Empty rather than absent for a null, for the reason {@link #text} is: Notify refuses a send whose
     * personalisation omits a placeholder the template uses.
     */
    static String date(LocalDateTime value) {
        return value == null ? "" : APPLICANT_DATE.format(value);
    }

    /** A nullable column as a value Notify accepts: the text, or nothing at all. */
    static String text(String value) {
        return value == null ? "" : value;
    }
}
