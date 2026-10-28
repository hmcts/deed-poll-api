package uk.gov.hmcts.reform.deedpoll.domain.masters;

import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralNotes;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.domain.settings.ReferralWindowTimes;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What a Master email says (plan D3, step 5), as the four plain strings its Notify template is given. GOV.UK Notify
 * links a raw URL and renders no HTML, so the list is one line per referral and the links are written out.
 *
 * <p><strong>No applicant name, no address and no email address</strong>: a referral is named by its application's
 * display reference, and described by its reasons, its urgency and the day it was referred. Pure, so the wording is
 * tested without a database.
 */
public final class MasterEmailPersonalisation {

    public static final String MASTER_NAME = "master_name";
    public static final String REFERRAL_COUNT = "referral_count";
    public static final String PERIOD = "period";
    public static final String REFERRAL_LIST = "referral_list";

    /** The dd-mm-yy style Steve chose for the Master's page. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd-MM-yy", Locale.UK);

    private static final String SEPARATOR = " – ";

    private MasterEmailPersonalisation() {
    }

    /**
     * One referral as an email lists it.
     *
     * @param reasons the stored {@code ReferralReason} names
     * @param referredOn when the referral was raised, as UTC wall time ({@code AuditClock}); written as London's day
     */
    public record Line(UUID referralId, String displayReference, List<String> reasons, Urgency urgency,
                       LocalDateTime referredOn) {
    }

    /**
     * The personalisation, keys in a fixed order.
     *
     * @param periodKey the ledger row's period: a digest's window ({@code 2026-10-28:AM}), or a reminder's run
     * @param lines the referrals, in the order the email lists them
     */
    public static Map<String, String> of(String masterName, MasterEmailKind kind, String periodKey, List<Line> lines,
                                         MasterEmailLinks links) {
        Map<String, String> personalisation = new LinkedHashMap<>();
        personalisation.put(MASTER_NAME, masterName);
        personalisation.put(REFERRAL_COUNT, Integer.toString(lines.size()));
        personalisation.put(PERIOD, period(kind, periodKey));
        personalisation.put(REFERRAL_LIST, lines.stream().map(line -> line(line, links))
            .collect(Collectors.joining("\n")));
        return personalisation;
    }

    /**
     * A digest's period key, {@code 2026-10-28:AM}: the window's date and slot, which {@link #period} reads.
     */
    public static String digestPeriod(ReferralWindow window) {
        return window.date() + ":" + window.slot().name();
    }

    /** "the morning of 28-10-26", "the afternoon of 28-10-26", or a reminder's "waiting for your direction". */
    static String period(MasterEmailKind kind, String periodKey) {
        if (kind == MasterEmailKind.REMINDER) {
            return "waiting for your direction";
        }
        int colon = periodKey.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("A digest's period is a window date and slot");
        }
        LocalDate day = LocalDate.parse(periodKey.substring(0, colon));
        WindowSlot slot = WindowSlot.valueOf(periodKey.substring(colon + 1));
        return (slot == WindowSlot.AM ? "the morning of " : "the afternoon of ") + DAY.format(day);
    }

    /**
     * {@code DP2026-000123 – Other – Urgent – referred 27-10-26 – https://…/judicial/referrals/{id}}.
     */
    static String line(Line line, MasterEmailLinks links) {
        String reference = line.displayReference() == null || line.displayReference().isBlank()
            ? "(no reference)" : line.displayReference();
        String reasons = ReferralNotes.reasonsInWords(line.reasons());
        return reference
            + SEPARATOR + (reasons.isEmpty() ? "no reasons recorded" : reasons)
            + SEPARATOR + (line.urgency() == Urgency.Urgent ? "Urgent" : "Standard")
            + SEPARATOR + "referred " + DAY.format(line.referredOn().atOffset(ZoneOffset.UTC)
                                                    .atZoneSameInstant(ReferralWindowTimes.ZONE))
            + SEPARATOR + links.referralLink(line.referralId());
    }
}
