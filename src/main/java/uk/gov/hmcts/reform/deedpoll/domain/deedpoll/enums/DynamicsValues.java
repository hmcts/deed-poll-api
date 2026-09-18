package uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums;

import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dynamics option-set value maps.
 *
 * <p>Ported from {@code reference/ts/deed-poll/enums/dynamics-values.ts}. The Java enums use
 * readable names; Dynamics stores integers. These maps are the single source of truth linking the
 * two, so the UI can render the human label, a future Dataverse import/export can round-trip the
 * exact integer, and business logic that mirrors the original ("type === 5") stays legible.
 *
 * <p>Each value map is keyed by the enum member and yields its Dynamics integer; {@link #reverse} builds
 * the import direction. Every map preserves the reference's declaration order, because
 * {@link #LABELS} feeds {@code FormFields.enumOptions} and that order is the order a select renders.
 *
 * <p>The integers are NOT derived from {@link Enum#ordinal()} even though they currently agree with
 * it. The reference states them explicitly, an enum member inserted in the wrong place would silently
 * renumber every later one, and {@code EnumValuesConformanceTest} checks these numbers against the
 * Dataverse exports.
 */
public final class DynamicsValues {

    /** Dynamics integer for each deed-poll type. */
    public static final Map<DeedPollType, Integer> DEED_POLL_TYPE_VALUE = values(
        entry(DeedPollType.Adult, 1),
        entry(DeedPollType.AdultTransgender, 2),
        entry(DeedPollType.Minor, 3),
        entry(DeedPollType.MinorTransgender, 4),
        entry(DeedPollType.DeedOfRelinquishment, 5),
        entry(DeedPollType.Unknown, 6));

    /** Dynamics integer for each case state. */
    public static final Map<DeedPollState, Integer> DEED_POLL_STATE_VALUE = values(
        entry(DeedPollState.Received, 1),
        entry(DeedPollState.ReadyForMaster, 2),
        entry(DeedPollState.ReferredToMaster, 3),
        entry(DeedPollState.Enroled, 4),
        entry(DeedPollState.Returned, 5),
        entry(DeedPollState.AwaitingFurtherDocs, 6),
        entry(DeedPollState.AwaitingSignature, 7),
        entry(DeedPollState.AwaitingEnrolment, 8));

    /** Dynamics integer for each marital status. */
    public static final Map<MaritalStatus, Integer> MARITAL_STATUS_VALUE = values(
        entry(MaritalStatus.Single, 1),
        entry(MaritalStatus.MarriedCivilPartnership, 2),
        entry(MaritalStatus.Widowed, 3),
        entry(MaritalStatus.DivorcedDissolved, 4));

    /** Dynamics integer for each privacy level. */
    public static final Map<Privacy, Integer> PRIVACY_VALUE = values(
        entry(Privacy.Normal, 1),
        entry(Privacy.Private, 2));

    /** Dynamics integer for each urgency level. */
    public static final Map<Urgency, Integer> URGENCY_VALUE = values(
        entry(Urgency.Normal, 1),
        entry(Urgency.Urgent, 2));

    /** Dynamics integer for each intake channel. */
    public static final Map<PostCounter, Integer> POST_COUNTER_VALUE = values(
        entry(PostCounter.Counter, 1),
        entry(PostCounter.Online, 2),
        entry(PostCounter.Post, 3),
        entry(PostCounter.Unknown, 4));

    /** Dynamics integer for each nationality. */
    public static final Map<Nationality, Integer> NATIONALITY_VALUE = values(
        entry(Nationality.BritishCitizen, 1),
        entry(Nationality.BritishDependantTerritoriesCitizen, 2),
        entry(Nationality.CommonwealthCitizen, 3),
        entry(Nationality.Unknown, 4));

    /** Dynamics integer for each nationality section. */
    public static final Map<NationalitySection, Integer> NATIONALITY_SECTION_VALUE = values(
        entry(NationalitySection.Section1, 1),
        entry(NationalitySection.Section11, 2),
        entry(NationalitySection.Section37, 3),
        entry(NationalitySection.Section6, 4),
        entry(NationalitySection.Unknown, 5),
        entry(NationalitySection.NoneOfTheAbove, 6));

    /** Dynamics integer for each master decision. */
    public static final Map<MasterDecision, Integer> MASTER_DECISION_VALUE = values(
        entry(MasterDecision.Approved, 1),
        entry(MasterDecision.Rejected, 2));

    /** Dynamics integer for each refer-to-master choice. */
    public static final Map<ReferToMaster, Integer> REFER_TO_MASTER_VALUE = values(
        entry(ReferToMaster.Yes, 1),
        entry(ReferToMaster.No, 2));

    /** Dynamics integer for each document direction. */
    public static final Map<DocumentDirection, Integer> DOCUMENT_DIRECTION_VALUE = values(
        entry(DocumentDirection.Incoming, 1),
        entry(DocumentDirection.Outgoing, 2));

    /** Human-readable label for each deed-poll type. */
    public static final Map<DeedPollType, String> DEED_POLL_TYPE_LABEL = values(
        entry(DeedPollType.Adult, "Adult"),
        entry(DeedPollType.AdultTransgender, "Adult Transgender"),
        entry(DeedPollType.Minor, "Minor"),
        entry(DeedPollType.MinorTransgender, "Minor Transgender"),
        entry(DeedPollType.DeedOfRelinquishment, "Deed of Relinquishment"),
        entry(DeedPollType.Unknown, "Unknown"));

    /** Human-readable label for each case state. */
    public static final Map<DeedPollState, String> DEED_POLL_STATE_LABEL = values(
        entry(DeedPollState.Received, "Received"),
        entry(DeedPollState.ReadyForMaster, "Ready for Master"),
        entry(DeedPollState.ReferredToMaster, "Referred to Master"),
        entry(DeedPollState.Enroled, "Enroled"),
        entry(DeedPollState.Returned, "Returned"),
        entry(DeedPollState.AwaitingFurtherDocs, "Awaiting Further Docs/Info"),
        entry(DeedPollState.AwaitingSignature, "Awaiting Signature"),
        entry(DeedPollState.AwaitingEnrolment, "Awaiting Enrolment"));

    /** Human-readable label for each marital status. */
    public static final Map<MaritalStatus, String> MARITAL_STATUS_LABEL = values(
        entry(MaritalStatus.Single, "Single"),
        entry(MaritalStatus.MarriedCivilPartnership, "Married / Civil Partnership"),
        entry(MaritalStatus.Widowed, "Widowed"),
        entry(MaritalStatus.DivorcedDissolved, "Divorced / Civil Partnership Dissolved"));

    /** Human-readable label for each privacy level. */
    public static final Map<Privacy, String> PRIVACY_LABEL = values(
        entry(Privacy.Normal, "Normal"),
        entry(Privacy.Private, "Private"));

    /** Human-readable label for each urgency level. */
    public static final Map<Urgency, String> URGENCY_LABEL = values(
        entry(Urgency.Normal, "Normal"),
        entry(Urgency.Urgent, "Urgent"));

    /** Human-readable label for each intake channel. */
    public static final Map<PostCounter, String> POST_COUNTER_LABEL = values(
        entry(PostCounter.Counter, "Counter"),
        entry(PostCounter.Online, "Online"),
        entry(PostCounter.Post, "Post"),
        entry(PostCounter.Unknown, "Unknown"));

    /** Human-readable label for each nationality. */
    public static final Map<Nationality, String> NATIONALITY_LABEL = values(
        entry(Nationality.BritishCitizen, "British Citizen"),
        entry(Nationality.BritishDependantTerritoriesCitizen, "British Dependant Territories Citizen"),
        entry(Nationality.CommonwealthCitizen, "Commonwealth Citizen"),
        entry(Nationality.Unknown, "Unknown"));

    /** Human-readable label for each nationality section. */
    public static final Map<NationalitySection, String> NATIONALITY_SECTION_LABEL = values(
        entry(NationalitySection.Section1, "Section 1"),
        entry(NationalitySection.Section11, "Section 11"),
        entry(NationalitySection.Section37, "Section 37"),
        entry(NationalitySection.Section6, "Section 6"),
        entry(NationalitySection.Unknown, "Unknown"),
        entry(NationalitySection.NoneOfTheAbove, "None of the Above"));

    /** Human-readable label for each master decision. */
    public static final Map<MasterDecision, String> MASTER_DECISION_LABEL = values(
        entry(MasterDecision.Approved, "Approved"),
        entry(MasterDecision.Rejected, "Rejected"));

    /** Human-readable label for each refer-to-master choice. */
    public static final Map<ReferToMaster, String> REFER_TO_MASTER_LABEL = values(
        entry(ReferToMaster.Yes, "Yes"),
        entry(ReferToMaster.No, "No"));

    /** Human-readable label for each document direction. */
    public static final Map<DocumentDirection, String> DOCUMENT_DIRECTION_LABEL = values(
        entry(DocumentDirection.Incoming, "Incoming"),
        entry(DocumentDirection.Outgoing, "Outgoing"));

    /**
     * Human-readable label for each referral state.
     *
     * <p><strong>Labels with no matching {@code _VALUE} map, and deliberately so.</strong> Every other
     * set here pairs a label map with a Dynamics integer map, because the source store held the field.
     * {@link ReferralState} is native to this service ({@code V6__referrals.sql}) and Dynamics never had
     * a referral entity, so there is no integer to state and inventing one would put a number in
     * {@code EnumValuesConformanceTest}'s way that no Dataverse export can confirm. The column stores
     * the member name, which is what {@link #byName} keys on, so the labels work without one.
     *
     * <p>All five members are labelled even though {@code currentReferralState} only ever holds the
     * three non-terminal ones: {@link ReferralState#isTerminal() terminal} is the referral's business,
     * not this map's, and a label set missing a member renders that member as nothing at all if the
     * split ever moves.
     */
    public static final Map<ReferralState, String> REFERRAL_STATE_LABEL = values(
        entry(ReferralState.Open, "Open"),
        entry(ReferralState.Claimed, "Claimed"),
        entry(ReferralState.Decided, "Decided"),
        entry(ReferralState.Closed, "Closed"),
        entry(ReferralState.Withdrawn, "Withdrawn"));

    /**
     * Labels for the {@code iShall} multiselect.
     *
     * <p>The three multiselects are stored as {@code integer[]} on the record, so these maps are
     * keyed by the Dynamics integer rather than by an enum member -- mirroring
     * {@code deedpoll_ishall} and its siblings, which have no Prisma enum at all.
     */
    public static final Map<Integer, String> I_SHALL_LABEL = values(
        entry(1, "and my wife"),
        entry(2, "and my husband"),
        entry(3, "and my civil partner"),
        entry(4, "and my children"),
        entry(5, "and I alone"));

    /**
     * Labels for the {@code iHaveSubscribed} multiselect, keyed by Dynamics integer.
     */
    public static final Map<Integer, String> I_HAVE_SUBSCRIBED_LABEL = values(
        entry(1, "Forename(s)"),
        entry(2, "Middle Name(s)"),
        entry(3, "Surname(s)"));

    /**
     * Labels for the {@code andAlsoMyOld} multiselect, keyed by Dynamics integer.
     */
    public static final Map<Integer, String> AND_ALSO_MY_OLD_LABEL = values(
        entry(1, "Forename(s)"),
        entry(2, "Middle Name(s)"),
        entry(3, "Surname(s)"));

    /**
     * Every label map, keyed by the short set name a field's {@code enumSet} names.
     *
     * <p>The inner maps are keyed by {@code String} rather than by the enum, because a caller
     * formatting a stored value has whatever the record holds and the reference looks it up as
     * {@code map[String(value)]}. Keying by the member name covers both an enum member and its
     * name as text, and it is the only shape the two integer-keyed multiselect maps can share.
     *
     * <p>Single source for the UI: a second opinion about what a Type is called is exactly what
     * {@code list-view.ts} exists not to have.
     */
    public static final Map<String, Map<String, String>> LABELS = labels();

    private DynamicsValues() {
    }

    /**
     * Builds a reverse map (Dynamics integer to enum member) for imports.
     *
     * <p>Ported from the reference's {@code reverse()}. Iteration order follows the forward map's, so
     * a reversed map still reads in declaration order.
     */
    public static <E extends Enum<E>> Map<Integer, E> reverse(Map<E, Integer> map) {
        Map<Integer, E> out = new LinkedHashMap<>();
        map.forEach((member, value) -> out.put(value, member));
        return Collections.unmodifiableMap(out);
    }

    private static Map<String, Map<String, String>> labels() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        out.put("type", byName(DEED_POLL_TYPE_LABEL));
        out.put("state", byName(DEED_POLL_STATE_LABEL));
        out.put("maritalStatus", byName(MARITAL_STATUS_LABEL));
        out.put("privacy", byName(PRIVACY_LABEL));
        out.put("urgency", byName(URGENCY_LABEL));
        out.put("postCounter", byName(POST_COUNTER_LABEL));
        out.put("nationality", byName(NATIONALITY_LABEL));
        out.put("nationalitySection", byName(NATIONALITY_SECTION_LABEL));
        out.put("masterDecision", byName(MASTER_DECISION_LABEL));
        out.put("referToMaster", byName(REFER_TO_MASTER_LABEL));
        out.put("documentDirection", byName(DOCUMENT_DIRECTION_LABEL));
        out.put("currentReferralState", byName(REFERRAL_STATE_LABEL));
        out.put("iShall", byName(I_SHALL_LABEL));
        out.put("iHaveSubscribed", byName(I_HAVE_SUBSCRIBED_LABEL));
        out.put("andAlsoMyOld", byName(AND_ALSO_MY_OLD_LABEL));
        return Collections.unmodifiableMap(out);
    }

    /**
     * Re-keys a label map by {@code String.valueOf(key)}, which is how a stored value arrives.
     */
    private static <K> Map<String, String> byName(Map<K, String> map) {
        Map<String, String> out = new LinkedHashMap<>();
        map.forEach((key, label) -> out.put(String.valueOf(key), label));
        return Collections.unmodifiableMap(out);
    }

    private static <K, V> Map.Entry<K, V> entry(K key, V value) {
        return Map.entry(key, value);
    }

    @SafeVarargs
    private static <K, V> Map<K, V> values(Map.Entry<K, V>... entries) {
        Map<K, V> out = new LinkedHashMap<>();
        for (Map.Entry<K, V> e : entries) {
            out.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(out);
    }
}
