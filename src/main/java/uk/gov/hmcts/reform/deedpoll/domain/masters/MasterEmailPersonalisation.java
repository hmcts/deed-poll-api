package uk.gov.hmcts.reform.deedpoll.domain.masters;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a Master email says (plan F1, step 8, decision A-1), as the three plain strings its Notify template is given:
 * the Master's name, how many referrals the email is about, and one link to their page of referrals. The same three
 * for the {@code SENT} email and the reminder.
 *
 * <p><strong>No applicant name, no reference, no address and no email address</strong>: the email names nothing
 * about a case, and the Master signs in to see them. Pure, so the wording is tested without a database.
 */
public final class MasterEmailPersonalisation {

    public static final String MASTER_NAME = "master_name";
    public static final String REFERRAL_COUNT = "referral_count";
    public static final String MY_REFERRALS_LINK = "my_referrals_link";

    private MasterEmailPersonalisation() {
    }

    /**
     * The personalisation, keys in a fixed order.
     *
     * @param referralCount the referrals still waiting for this Master's direction among those the row lists
     */
    public static Map<String, String> of(String masterName, int referralCount, MasterEmailLinks links) {
        Map<String, String> personalisation = new LinkedHashMap<>();
        personalisation.put(MASTER_NAME, masterName);
        personalisation.put(REFERRAL_COUNT, Integer.toString(referralCount));
        personalisation.put(MY_REFERRALS_LINK, links.myReferralsLink());
        return personalisation;
    }
}
