package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MaritalStatus;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Nationality;
import uk.gov.hmcts.reform.deedpoll.entity.enums.NationalitySection;
import uk.gov.hmcts.reform.deedpoll.entity.enums.PostCounter;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Reading and writing a case's fields by the name the reference gives them.
 *
 * <p>The reference has no equivalent, and does not need one: TypeScript's spread operator merges a
 * patch into a record and its index signature reads any field by name. Java has neither, so the
 * name-to-accessor mapping has to be stated -- and stating it explicitly, rather than by reflection,
 * means a field only becomes writable by being listed here.
 *
 * <p><strong>{@link #WRITERS} is the reference's {@code createDeedPollSchema} field list.</strong> Only
 * those 68 fields are writable through a patch, so this registry is what makes {@code referencenumber}
 * and {@code displayReference} unsettable by a caller -- exactly what the reference's "strips unknown
 * keys" test asserts about the schema. The audit columns are unwritable for the same reason, and the
 * public-journey fields ({@code placeOfBirth} and the parent block) are absent because the reference's
 * schema does not accept them either: they belong to the citizen journey, not to this API.
 *
 * <p>Every writer coerces. A patch may have come from the JSON schema (dates as ISO text, multiselects
 * as lists) or from a form body, and a column will not take one shape spelled as the other. Coercing at
 * the single point of write means neither producer has to know what the column is.
 */
public final class DeedPollFields {

    /** Every readable field name, mapped to the accessor that reads it off a case. */
    private static final Map<String, Function<DeedPoll, Object>> READERS = readers();

    /** Every writable field name, mapped to the setter that writes it, coercing on the way. */
    private static final Map<String, BiConsumer<DeedPoll, Object>> WRITERS = writers();

    private DeedPollFields() {
    }

    /** The names a patch may set, in the reference schema's declaration order. */
    public static Set<String> writableFields() {
        return WRITERS.keySet();
    }

    /** Whether a name is one a patch may set -- what "strips unknown keys" means here. */
    public static boolean isWritable(String field) {
        return WRITERS.containsKey(field);
    }

    /**
     * Writes one field onto a case, coercing the value to the column's type.
     *
     * @throws IllegalArgumentException if the name is not writable, which a caller reaching here has
     *     already had the chance to check with {@link #isWritable}
     */
    public static void write(DeedPoll record, String field, Object value) {
        BiConsumer<DeedPoll, Object> writer = WRITERS.get(field);
        if (writer == null) {
            throw new IllegalArgumentException("Not a writable DeedPoll field: " + field);
        }
        writer.accept(record, value);
    }

    /**
     * Every stored field of a case, by name.
     *
     * <p>This is the {@code ValidationInput} and gate input: a merged record read as a field map. The
     * child collections are deliberately absent -- reading them would trigger a lazy load for a caller
     * that only wants to know whether {@code parents} is populated.
     */
    public static Map<String, Object> readAll(DeedPoll record) {
        Map<String, Object> values = new LinkedHashMap<>();
        READERS.forEach((field, reader) -> values.put(field, reader.apply(record)));
        return values;
    }

    /**
     * Every field the detail and edit pages render: the stored ones, the derived day counts and the
     * three child collections.
     *
     * <p>Reading the collections DOES trigger their fetch, which is what a page showing them wants and
     * why this is separate from {@link #readAll}.
     */
    public static Map<String, Object> displayValues(DeedPollWithComputed withComputed) {
        DeedPoll record = withComputed.deedPoll();
        Map<String, Object> values = readAll(record);
        values.put("daysReceivedToEnroled", withComputed.computed().daysReceivedToEnroled());
        values.put("daysReceivedToReturned", withComputed.computed().daysReceivedToReturned());
        values.put("notes", record.getNotes());
        values.put("witnesses", record.getWitnesses());
        values.put("documents", record.getDocuments());
        return values;
    }

    private static Map<String, Function<DeedPoll, Object>> readers() {
        Map<String, Function<DeedPoll, Object>> readers = new LinkedHashMap<>();

        // --- Identity, allocated rather than submitted ---
        readers.put("id", DeedPoll::getId);
        readers.put("referencenumber", DeedPoll::getReferencenumber);
        readers.put("displayReference", DeedPoll::getDisplayReference);

        // --- General / classification ---
        readers.put("type", DeedPoll::getType);
        readers.put("state", DeedPoll::getState);
        readers.put("privacy", DeedPoll::getPrivacy);
        readers.put("urgency", DeedPoll::getUrgency);
        readers.put("urgencyreason", DeedPoll::getUrgencyreason);
        readers.put("postCounter", DeedPoll::getPostCounter);
        readers.put("inPerson", DeedPoll::getInPerson);
        readers.put("migratedRecord", DeedPoll::isMigratedRecord);
        readers.put("retainPermanently", DeedPoll::isRetainPermanently);
        readers.put("maritalStatus", DeedPoll::getMaritalStatus);
        readers.put("feeReference", DeedPoll::getFeeReference);
        readers.put("onlineApplicationReference", DeedPoll::getOnlineApplicationReference);

        // --- Applicant name ---
        readers.put("newForename", DeedPoll::getNewForename);
        readers.put("newMiddleName", DeedPoll::getNewMiddleName);
        readers.put("newSurname", DeedPoll::getNewSurname);
        readers.put("oldForename", DeedPoll::getOldForename);
        readers.put("oldMiddleName", DeedPoll::getOldMiddleName);
        readers.put("oldSurname", DeedPoll::getOldSurname);

        // --- Applicant address and contact ---
        readers.put("applicantBuildingNoName", DeedPoll::getApplicantBuildingNoName);
        readers.put("applicantEmail", DeedPoll::getApplicantEmail);
        readers.put("applicantTelephone", DeedPoll::getApplicantTelephone);
        readers.put("applicantAddress1", DeedPoll::getApplicantAddress1);
        readers.put("applicantAddress2", DeedPoll::getApplicantAddress2);
        readers.put("applicantTownCity", DeedPoll::getApplicantTownCity);
        readers.put("applicantPostcode", DeedPoll::getApplicantPostcode);

        // --- Representative address and contact ---
        readers.put("representativeName", DeedPoll::getRepresentativeName);
        readers.put("representativeBuildingNoName", DeedPoll::getRepresentativeBuildingNoName);
        readers.put("representativeCompany", DeedPoll::getRepresentativeCompany);
        readers.put("representativeAddress1", DeedPoll::getRepresentativeAddress1);
        readers.put("representativeEmail", DeedPoll::getRepresentativeEmail);
        readers.put("representativeAddress2", DeedPoll::getRepresentativeAddress2);
        readers.put("representativeTelephone", DeedPoll::getRepresentativeTelephone);
        readers.put("representativeTownCity", DeedPoll::getRepresentativeTownCity);
        readers.put("representativePostcode", DeedPoll::getRepresentativePostcode);

        // --- Minor ---
        readers.put("ageOfMinor", DeedPoll::getAgeOfMinor);
        readers.put("ageOfMinorMonths", DeedPoll::getAgeOfMinorMonths);
        readers.put("minorDateOfBirth", DeedPoll::getMinorDateOfBirth);
        readers.put("parents", DeedPoll::getParents);

        // --- Nationality ---
        readers.put("nationality", DeedPoll::getNationality);
        readers.put("nationalitySection", DeedPoll::getNationalitySection);
        readers.put("nationalitySectionOther", DeedPoll::getNationalitySectionOther);

        // --- Declarations ---
        readers.put("iShall", DeedPoll::getIShall);
        readers.put("iHaveSubscribed", DeedPoll::getIHaveSubscribed);
        readers.put("andAlsoMyOld", DeedPoll::getAndAlsoMyOld);
        readers.put("dateOfDeclaration", DeedPoll::getDateOfDeclaration);

        // --- Dates driving state ---
        readers.put("dateOfApplication", DeedPoll::getDateOfApplication);
        readers.put("dateReceived", DeedPoll::getDateReceived);
        readers.put("awaitingFurtherDocsInfo", DeedPoll::getAwaitingFurtherDocsInfo);
        readers.put("signatureRequestedDate", DeedPoll::getSignatureRequestedDate);
        readers.put("signatureReceivedDate", DeedPoll::getSignatureReceivedDate);
        readers.put("dateReadyForMaster", DeedPoll::getDateReadyForMaster);
        readers.put("dateReferred", DeedPoll::getDateReferred);
        readers.put("dateEnroled", DeedPoll::getDateEnroled);
        readers.put("dateReturned", DeedPoll::getDateReturned);
        readers.put("dateNoticeSentToLondonGazette", DeedPoll::getDateNoticeSentToLondonGazette);

        // --- Master / referral ---
        readers.put("referToMasterChoice", DeedPoll::getReferToMasterChoice);
        readers.put("masterName", DeedPoll::getMasterName);
        readers.put("masterDirections", DeedPoll::getMasterDirections);
        readers.put("masterDecision", DeedPoll::getMasterDecision);
        readers.put("masterDateOfDecision", DeedPoll::getMasterDateOfDecision);
        readers.put("dateDecisionDirectionsReceived", DeedPoll::getDateDecisionDirectionsReceived);
        readers.put("referralNotes", DeedPoll::getReferralNotes);
        readers.put("publishToLondonGazette", DeedPoll::isPublishToLondonGazette);

        // --- Return / refund ---
        readers.put("returnReason", DeedPoll::getReturnReason);
        readers.put("refundAmount", DeedPoll::getRefundAmount);
        readers.put("dateOfRefund", DeedPoll::getDateOfRefund);
        readers.put("refundReference", DeedPoll::getRefundReference);

        // --- Audit ---
        readers.put("createdOn", DeedPoll::getCreatedOn);
        readers.put("modifiedOn", DeedPoll::getModifiedOn);
        readers.put("createdBy", DeedPoll::getCreatedBy);
        readers.put("modifiedBy", DeedPoll::getModifiedBy);

        return Collections.unmodifiableMap(readers);
    }

    private static Map<String, BiConsumer<DeedPoll, Object>> writers() {
        Map<String, BiConsumer<DeedPoll, Object>> writers = new LinkedHashMap<>();

        // --- General / classification ---
        writers.put("type", (e, v) -> e.setType(enumOf(DeedPollType.class, v)));
        writers.put("privacy", (e, v) -> e.setPrivacy(enumOf(Privacy.class, v)));
        writers.put("urgency", (e, v) -> e.setUrgency(enumOf(Urgency.class, v)));
        writers.put("urgencyreason", (e, v) -> e.setUrgencyreason(str(v)));
        writers.put("postCounter", (e, v) -> e.setPostCounter(enumOf(PostCounter.class, v)));
        writers.put("inPerson", (e, v) -> e.setInPerson(bool(v)));
        writers.put("migratedRecord", (e, v) -> e.setMigratedRecord(Boolean.TRUE.equals(bool(v))));
        writers.put("retainPermanently", (e, v) -> e.setRetainPermanently(Boolean.TRUE.equals(bool(v))));
        writers.put("maritalStatus", (e, v) -> e.setMaritalStatus(enumOf(MaritalStatus.class, v)));
        writers.put("feeReference", (e, v) -> e.setFeeReference(str(v)));
        writers.put("onlineApplicationReference", (e, v) -> e.setOnlineApplicationReference(str(v)));

        // --- Applicant name ---
        writers.put("newForename", (e, v) -> e.setNewForename(str(v)));
        writers.put("newMiddleName", (e, v) -> e.setNewMiddleName(str(v)));
        writers.put("newSurname", (e, v) -> e.setNewSurname(str(v)));
        writers.put("oldForename", (e, v) -> e.setOldForename(str(v)));
        writers.put("oldMiddleName", (e, v) -> e.setOldMiddleName(str(v)));
        writers.put("oldSurname", (e, v) -> e.setOldSurname(str(v)));

        // --- Applicant address and contact ---
        writers.put("applicantBuildingNoName", (e, v) -> e.setApplicantBuildingNoName(str(v)));
        writers.put("applicantEmail", (e, v) -> e.setApplicantEmail(str(v)));
        writers.put("applicantTelephone", (e, v) -> e.setApplicantTelephone(str(v)));
        writers.put("applicantAddress1", (e, v) -> e.setApplicantAddress1(str(v)));
        writers.put("applicantAddress2", (e, v) -> e.setApplicantAddress2(str(v)));
        writers.put("applicantTownCity", (e, v) -> e.setApplicantTownCity(str(v)));
        writers.put("applicantPostcode", (e, v) -> e.setApplicantPostcode(str(v)));

        // --- Representative address and contact ---
        writers.put("representativeName", (e, v) -> e.setRepresentativeName(str(v)));
        writers.put("representativeBuildingNoName", (e, v) -> e.setRepresentativeBuildingNoName(str(v)));
        writers.put("representativeCompany", (e, v) -> e.setRepresentativeCompany(str(v)));
        writers.put("representativeAddress1", (e, v) -> e.setRepresentativeAddress1(str(v)));
        writers.put("representativeEmail", (e, v) -> e.setRepresentativeEmail(str(v)));
        writers.put("representativeAddress2", (e, v) -> e.setRepresentativeAddress2(str(v)));
        writers.put("representativeTelephone", (e, v) -> e.setRepresentativeTelephone(str(v)));
        writers.put("representativeTownCity", (e, v) -> e.setRepresentativeTownCity(str(v)));
        writers.put("representativePostcode", (e, v) -> e.setRepresentativePostcode(str(v)));

        // --- Minor ---
        writers.put("ageOfMinor", (e, v) -> e.setAgeOfMinor(integer(v)));
        writers.put("ageOfMinorMonths", (e, v) -> e.setAgeOfMinorMonths(integer(v)));
        writers.put("minorDateOfBirth", (e, v) -> e.setMinorDateOfBirth(dateTime(v)));
        writers.put("parents", (e, v) -> e.setParents(str(v)));

        // --- Nationality ---
        writers.put("nationality", (e, v) -> e.setNationality(enumOf(Nationality.class, v)));
        writers.put("nationalitySection", (e, v) -> e.setNationalitySection(enumOf(NationalitySection.class, v)));
        writers.put("nationalitySectionOther", (e, v) -> e.setNationalitySectionOther(str(v)));

        // --- Declarations ---
        writers.put("iShall", (e, v) -> e.setIShall(intArray(v)));
        writers.put("iHaveSubscribed", (e, v) -> e.setIHaveSubscribed(intArray(v)));
        writers.put("andAlsoMyOld", (e, v) -> e.setAndAlsoMyOld(intArray(v)));
        writers.put("dateOfDeclaration", (e, v) -> e.setDateOfDeclaration(dateTime(v)));

        // --- Dates driving state ---
        writers.put("dateOfApplication", (e, v) -> e.setDateOfApplication(dateTime(v)));
        writers.put("dateReceived", (e, v) -> e.setDateReceived(dateTime(v)));
        writers.put("awaitingFurtherDocsInfo", (e, v) -> e.setAwaitingFurtherDocsInfo(dateTime(v)));
        writers.put("signatureRequestedDate", (e, v) -> e.setSignatureRequestedDate(dateTime(v)));
        writers.put("signatureReceivedDate", (e, v) -> e.setSignatureReceivedDate(dateTime(v)));
        writers.put("dateReadyForMaster", (e, v) -> e.setDateReadyForMaster(dateTime(v)));
        writers.put("dateReferred", (e, v) -> e.setDateReferred(dateTime(v)));
        writers.put("dateEnroled", (e, v) -> e.setDateEnroled(dateTime(v)));
        writers.put("dateReturned", (e, v) -> e.setDateReturned(dateTime(v)));
        writers.put("dateNoticeSentToLondonGazette", (e, v) -> e.setDateNoticeSentToLondonGazette(dateTime(v)));

        // --- Master / referral ---
        writers.put("referToMasterChoice", (e, v) -> e.setReferToMasterChoice(enumOf(ReferToMaster.class, v)));
        writers.put("masterName", (e, v) -> e.setMasterName(str(v)));
        writers.put("masterDirections", (e, v) -> e.setMasterDirections(str(v)));
        writers.put("masterDecision", (e, v) -> e.setMasterDecision(enumOf(MasterDecision.class, v)));
        writers.put("masterDateOfDecision", (e, v) -> e.setMasterDateOfDecision(dateTime(v)));
        writers.put("dateDecisionDirectionsReceived", (e, v) -> e.setDateDecisionDirectionsReceived(dateTime(v)));
        writers.put("referralNotes", (e, v) -> e.setReferralNotes(str(v)));
        writers.put("publishToLondonGazette", (e, v) -> e.setPublishToLondonGazette(Boolean.TRUE.equals(bool(v))));

        // --- Return / refund ---
        writers.put("returnReason", (e, v) -> e.setReturnReason(str(v)));
        writers.put("refundAmount", (e, v) -> e.setRefundAmount(decimal(v)));
        writers.put("dateOfRefund", (e, v) -> e.setDateOfRefund(dateTime(v)));
        writers.put("refundReference", (e, v) -> e.setRefundReference(str(v)));

        // Accepted only for seeding and import; normally derived from the populated dates, which is why
        // DeedPollService overwrites whatever a caller sent before the row is written.
        writers.put("state", (e, v) -> e.setState(enumOf(DeedPollState.class, v)));

        return Collections.unmodifiableMap(writers);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Boolean bool(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.valueOf(String.valueOf(value));
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(String.valueOf(value).trim());
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal amount) {
            return amount;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private static LocalDateTime dateTime(Object value) {
        return Dates.toDateTime(value);
    }

    /**
     * A multiselect value as the {@code integer[]} column takes it.
     *
     * <p>Null becomes an empty array rather than null. The column is nullable, but the reference
     * defaults it to {@code []} and the schema refuses a null for it, so a null arriving here is a
     * caller mistake that an empty selection represents honestly.
     */
    private static Integer[] intArray(Object value) {
        if (value == null) {
            return new Integer[0];
        }
        if (value instanceof Integer[] array) {
            return array;
        }
        if (value instanceof Collection<?> || value instanceof Object[]) {
            List<Integer> numbers = FormValues.toIntList(value);
            return numbers.toArray(new Integer[0]);
        }
        return new Integer[] {integer(value)};
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, Object value) {
        if (value == null) {
            return null;
        }
        if (type.isInstance(value)) {
            return type.cast(value);
        }
        return Enum.valueOf(type, String.valueOf(value));
    }
}
