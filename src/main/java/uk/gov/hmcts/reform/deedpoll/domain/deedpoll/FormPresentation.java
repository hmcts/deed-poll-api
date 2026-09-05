package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;

/**
 * Server-driven view model replacing the client-side {@code deedpoll_javascript}.
 *
 * <p>Ported from {@code reference/ts/deed-poll/form-presentation.ts}, which ports that web resource to
 * a server-computed model: the UI is server-rendered, so show / hide / mandate is decided here rather
 * than in the browser.
 *
 * <p>Privacy auto-default (create only), editability, the PRIVATE banner and the three conditional
 * tabs all mirror the source functions {@code OnChangeType}, {@code OnChangePrivacy} and
 * {@code ShowHideTab} exactly. See the notes on each method.
 *
 * <p>Two of these are consulted by the WRITE path as well as by the form:
 * {@link #privacyEditable} is the authorisation rule {@code DeedPollService} enforces, and
 * {@link #defaultPrivacy} is the create-time default it applies. That is the point of them being here
 * rather than in a template -- the lock and the input's disabled state cannot drift apart if both read
 * the same function.
 */
public record FormPresentation(PrivacyPresentation privacy, TabVisibility tabs) {

    /** The banner text shown above a private record. */
    public static final String PRIVATE_BANNER = "This record is PRIVATE!";

    /**
     * How the Privacy control renders.
     *
     * <p>{@code bannerText} is null rather than {@link java.util.Optional} when there is no banner:
     * it is a value a template prints, like the placeholders in {@code DetailView}, not an absence the
     * caller has to branch on -- {@code showBanner} is the branch.
     */
    public record PrivacyPresentation(Privacy value, boolean editable, boolean showBanner, String bannerText) {
    }

    /**
     * Which of the three conditional tabs to show.
     *
     * <p>The reference's third member is called {@code return}, which Java reserves, so it is
     * {@code returnTab} here. It is the only renamed member in the port.
     */
    public record TabVisibility(boolean referralToMaster, boolean enrolment, boolean returnTab) {
    }

    /**
     * Auto-defaults privacy on CREATE, matching {@code OnChangeType}.
     *
     * <ul>
     *   <li>Admin plus Minor Transgender (4) gives Private (2); other types unchanged.</li>
     *   <li>Non-admin plus Adult / AdultTG / Minor (1, 2, 3) gives Normal (1).</li>
     *   <li>Non-admin plus Minor Transgender (4) gives Private (2).</li>
     *   <li>Non-admin plus Deed of Relinquishment (5) gives Normal (1).</li>
     *   <li>Unknown (6) and null are unchanged (no branch in the source).</li>
     * </ul>
     *
     * <p>On an existing record the source never changes the stored value.
     */
    public static Privacy defaultPrivacy(DeedPollType type, Privacy current, PresentationContext ctx) {
        Privacy fallback = current == null ? Privacy.Normal : current;
        if (!ctx.isNew()) {
            return fallback; // existing record: the value is never auto-set
        }

        if (ctx.isAdmin()) {
            return type == DeedPollType.MinorTransgender ? Privacy.Private : fallback;
        }
        // Non-admin, new record:
        if (type == null) {
            return fallback;
        }
        return switch (type) {
            case Adult, AdultTransgender, Minor, DeedOfRelinquishment -> Privacy.Normal;
            case MinorTransgender -> Privacy.Private;
            case Unknown -> fallback; // no branch in the source
        };
    }

    /**
     * Whether the Privacy control is editable, matching {@code OnLoadPrivacyControl} /
     * {@code OnChangeType}.
     *
     * <ul>
     *   <li>Admin: always editable.</li>
     *   <li>Non-admin, new, Deed of Relinquishment (5): editable.</li>
     *   <li>Non-admin, new, any other type: locked.</li>
     *   <li>Non-admin, existing: locked.</li>
     * </ul>
     */
    public static boolean privacyEditable(DeedPollType type, PresentationContext ctx) {
        if (ctx.isAdmin()) {
            return true;
        }
        if (ctx.isNew()) {
            return type == DeedPollType.DeedOfRelinquishment;
        }
        return false;
    }

    /**
     * Tab visibility, matching {@code ShowHideTab}.
     */
    public static TabVisibility tabVisibility(PresentationInput input) {
        // Referral-to-Master hidden only when the choice is explicitly No (2); visible for Yes (1) or
        // when unset.
        boolean referralToMaster = input.referToMasterChoice() != ReferToMaster.No;

        // Enrolment/Return keyed on state: Enroled(4) shows Enrolment only, Returned(5) shows Return
        // only, everything else shows both.
        boolean enrolment = true;
        boolean returnTab = true;
        if (input.state() == DeedPollState.Enroled) {
            returnTab = false;
        } else if (input.state() == DeedPollState.Returned) {
            enrolment = false;
        }

        return new TabVisibility(referralToMaster, enrolment, returnTab);
    }

    /** Builds the full presentation model for a record and a viewer context. */
    public static FormPresentation build(PresentationInput input, PresentationContext ctx) {
        Privacy value = defaultPrivacy(input.type(), input.privacy(), ctx);
        boolean showBanner = value == Privacy.Private;
        return new FormPresentation(
            new PrivacyPresentation(
                value,
                privacyEditable(input.type(), ctx),
                showBanner,
                showBanner ? PRIVATE_BANNER : null),
            tabVisibility(input));
    }
}
