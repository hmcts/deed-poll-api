package uk.gov.hmcts.reform.deedpoll.domain.masters;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a Master email says (plan F1, step 8, decision A-1), as the five plain strings its Notify template is given:
 * the Master's name, how many referrals the email is about, one link to their page of referrals, and the Delivery
 * Manager's processing window and reminder interval in words. The same five for the {@code SENT} email and the
 * reminder.
 *
 * <p>The two settings are placeholders rather than words in the template (Steve, 2026-11-02), so an email never
 * states a period the Delivery Manager has since changed. Notify refuses a send that lacks a placeholder its template
 * uses, so <strong>this must be deployed before a template uses either</strong>; a key the template does not use is
 * ignored.
 *
 * <p><strong>No applicant name, no reference, no address and no email address</strong>: the email names nothing
 * about a case, and the Master signs in to see them. Pure, so the wording is tested without a database.
 */
public final class MasterEmailPersonalisation {

    public static final String MASTER_NAME = "master_name";
    public static final String REFERRAL_COUNT = "referral_count";
    public static final String MY_REFERRALS_LINK = "my_referrals_link";
    public static final String PROCESSING_WINDOW = "processing_window";
    public static final String REMINDER_INTERVAL = "reminder_interval";

    private MasterEmailPersonalisation() {
    }

    /**
     * The personalisation, keys in a fixed order.
     *
     * @param referralCount the referrals still waiting for this Master's direction among those the row lists
     * @param processingWindow how long a Master has to give a direction, the setting as it is now
     * @param reminderInterval how often a Master is reminded, the setting as it is now
     */
    public static Map<String, String> of(String masterName, int referralCount, MasterEmailLinks links,
                                         Duration processingWindow, Duration reminderInterval) {
        Map<String, String> personalisation = new LinkedHashMap<>();
        personalisation.put(MASTER_NAME, masterName);
        personalisation.put(REFERRAL_COUNT, Integer.toString(referralCount));
        personalisation.put(MY_REFERRALS_LINK, links.myReferralsLink());
        personalisation.put(PROCESSING_WINDOW, windowInWords(processingWindow));
        personalisation.put(REMINDER_INTERVAL, count(reminderInterval.toHours(), "hour"));
        return personalisation;
    }

    /**
     * The processing window as a Delivery Manager sets it, in days ("7 days", "1 day"), else hours when whole hours,
     * else minutes: only api's own PUT can store a window that is not whole days. The reminder interval is set in
     * whole hours (1 to 168), so it is always written in hours ("48 hours"), never turned into days.
     */
    private static String windowInWords(Duration period) {
        if (period.toMinutes() % Duration.ofDays(1).toMinutes() == 0) {
            return count(period.toDays(), "day");
        }
        if (period.toMinutes() % Duration.ofHours(1).toMinutes() == 0) {
            return count(period.toHours(), "hour");
        }
        return count(period.toMinutes(), "minute");
    }

    private static String count(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
