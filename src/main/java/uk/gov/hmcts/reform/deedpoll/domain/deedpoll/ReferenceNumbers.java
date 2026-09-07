package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference number's shape: composing it, parsing it and deriving its parts.
 *
 * <p>Ported from {@code reference/ts/deed-poll/reference-number.ts}, which itself ports the
 * {@code AddDeedPollReferenceNumber} Power Automate flow. The composite display reference is
 * {@code {prefix}{year}-{serial}}, e.g. {@code KBDP2026-00001}:
 *
 * <ul>
 *   <li>prefix: {@code KBDR} for Deed of Relinquishment (type 5), else {@code KBDP}.</li>
 *   <li>year: 4-digit year taken from Date Received.</li>
 *   <li>serial: 5-digit, zero-padded, from a counter shared per-year across DP and DR.</li>
 * </ul>
 *
 * <p>On create the next serial for the year is allocated; on update the existing year and serial are
 * kept and only the prefix is recomputed, so a DP&#8596;DR type flip re-prefixes without renumbering.
 * A Date Received edit does NOT renumber.
 *
 * <p>Everything here is pure. Allocation, which needs a database and a transaction, is
 * {@link ReferenceNumberService}.
 *
 * <p><strong>One deliberate deviation from the reference.</strong> {@code parse} and
 * {@code reprefixForUpdate} return {@code null} in TypeScript, where the compiler's strict null
 * checks force the caller to handle it. Java has no such check, so both return
 * {@link Optional} here -- the same "you must handle absence" property, kept by the type rather
 * than by the caller's memory. The values and the decisions are otherwise the reference's.
 */
public final class ReferenceNumbers {

    /** Prefix for a Deed of Relinquishment. */
    public static final String RELINQUISHMENT_PREFIX = "KBDR";

    /** Prefix for every other deed-poll type, and for an absent type. */
    public static final String DEED_POLL_PREFIX = "KBDP";

    /** The composed shape: prefix, 4-digit year, hyphen, 5-digit serial. */
    private static final Pattern REFERENCE = Pattern.compile("^(KBD[PR])(\\d{4})-(\\d{5})$");

    /**
     * 5 digits, zero-padded, matching the source flow's {@code formatNumber(…, '00000')}.
     */
    private static final String SERIAL_FORMAT = "%05d";

    /**
     * The locale the serial is formatted in.
     *
     * <p>{@code Locale.ROOT} rather than the default, and it is load-bearing. {@code "%05d".formatted(n)}
     * is {@code String.format(this, n)}, which for {@code %d} substitutes the zero digit of
     * {@code Locale.getDefault(FORMAT)} -- so under a default locale whose numbering system is not Latin
     * ({@code ar-EG}, {@code fa-IR}, {@code ne-NP}, set by {@code user.language}/{@code LANG} in an image)
     * serial 7 formats as {@code ٠٠٠٠٧}. That would be persisted as {@code displayReference}, which is
     * external-facing and permanent; worse, {@link #REFERENCE} uses {@code \d}, which is ASCII-only, so
     * {@link #parse} could no longer read back what {@link #compose} had just written and a DP&#8596;DR flip
     * would silently stop re-prefixing. The reference is locale-independent by construction:
     * {@code String(serial).padStart(5, "0")}.
     */
    private static final Locale SERIAL_LOCALE = Locale.ROOT;

    private ReferenceNumbers() {
    }

    /** The three parts of a composed reference. */
    public record Parts(String prefix, int year, int serial) {
    }

    /**
     * Prefix from Type: type 5 (Deed of Relinquishment) gives {@code KBDR}, every other type and an
     * absent type give {@code KBDP}.
     */
    public static String prefixForType(DeedPollType type) {
        return type == DeedPollType.DeedOfRelinquishment ? RELINQUISHMENT_PREFIX : DEED_POLL_PREFIX;
    }

    /** Builds the composite display reference from its three parts. */
    public static String compose(String prefix, int year, int serial) {
        return prefix + year + "-" + formatSerial(serial);
    }

    /** 5-digit zero-padded serial. */
    public static String formatSerial(int serial) {
        return String.format(SERIAL_LOCALE, SERIAL_FORMAT, serial);
    }

    /**
     * Parses a composite reference into its parts, or empty if it does not match the
     * {@code KBDP2026-00001} shape -- a legacy or hand-entered value, say. Used on update to recover
     * the year and serial while re-deriving the prefix.
     */
    public static Optional<Parts> parse(String reference) {
        if (reference == null || reference.isEmpty()) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(reference);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Parts(
            matcher.group(1),
            Integer.parseInt(matcher.group(2)),
            Integer.parseInt(matcher.group(3))));
    }

    /**
     * The year to use for a new record, taken from Date Received.
     *
     * <p>There is no fallback. The source read the year from {@code datereceived}, and inventing one
     * from the clock would put a case in the wrong year's serial sequence -- a reference number is
     * external-facing and permanent, so a plausible-looking wrong one is worse than a refusal.
     *
     * <p>{@link InvalidInputError} rather than a bare exception, because an absent Date Received is a
     * caller omission and not a fault: it is the whole of what a create needs and does not have. The
     * reference records that thrown as a bare error it reached {@code apps/api}'s error middleware
     * unrecognised and became a generic 500, so {@code POST /deed-polls} with no Date Received --
     * the most obvious request anyone makes of this API -- answered "Internal server error". It is
     * attributed to the field so a form can render it against the Date Received input rather than as
     * an unattributed banner.
     *
     * <p>The reference reads {@code getUTCFullYear()}. The column is a {@code timestamp} holding a
     * UTC instant, so the mapped {@link LocalDateTime} is already UTC wall time and its year is that
     * same UTC year. The reference also rejects an invalid {@code Date}, which has no equivalent
     * here: a {@link LocalDateTime} cannot hold a non-date, so null is the only rejection left.
     */
    public static int yearFromDateReceived(LocalDateTime dateReceived) {
        if (dateReceived == null) {
            throw new InvalidInputError(
                "Cannot allocate a reference number without a Date Received to derive the year",
                List.of(new FieldError("dateReceived", "Enter the date the application was received")));
        }
        return dateReceived.getYear();
    }

    /**
     * On update: keeps the existing year and serial and recomputes only the prefix from the
     * (possibly changed) Type. Returns the new display reference, or empty if the existing reference
     * cannot be parsed -- in which case leave it untouched.
     */
    public static Optional<String> reprefixForUpdate(String existingReference, DeedPollType type) {
        return parse(existingReference)
            .map(parts -> compose(prefixForType(type), parts.year(), parts.serial()));
    }
}
