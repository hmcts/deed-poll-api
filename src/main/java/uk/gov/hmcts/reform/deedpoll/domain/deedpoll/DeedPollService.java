package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CRUD for a case, orchestrating every derived rule on the way in.
 *
 * <p>Ported from {@code reference/ts/deed-poll/service.ts}. The write path applies, in this order:
 * create-time defaults, the Gazette gate, the minor gate, privacy authorisation, the cross-field date
 * rules, reference-number allocation and state derivation. The order is the reference's and it matters --
 * see the comment on each step. One rule is not the reference's: the two-witness gate on the enrolment
 * transition, marked as an addition at {@link #requireWitnessesForEnrolment}.
 *
 * <p><strong>Where this port differs, and why.</strong> The reference merges a patch into a plain object
 * ({@code merged = {...existing, ...data}}) and hands the result to Prisma. Here the merge is built as a
 * field map and every rule reads it, exactly as the reference's rules do, but the ENTITY is not touched
 * until every rule has passed. That is deliberate: a managed entity mutated before validation is a
 * rejected write that has already half-happened, and it would depend on the transaction rolling back to
 * be correct. Nothing is written to the row unless the whole write is legitimate.
 *
 * <p>The reference also deletes {@code createdBy} from an update patch, so a caller cannot rewrite who
 * created a record. Here it cannot be in a patch at all -- {@link DeedPollFields} does not make it
 * writable -- so the deletion has nothing to delete.
 */
@Service
public class DeedPollService {

    /**
     * How many witnesses a case needs before it can be sent for enrolment.
     *
     * <p>Not a port -- see {@link #requireWitnessesForEnrolment}. Named rather than inlined because the
     * number is the rule, and the tests assert against the same constant.
     */
    public static final int WITNESSES_REQUIRED_FOR_ENROLMENT = 2;

    /**
     * The opening sentence of the enrolment refusal. Not a port -- see
     * {@link #requireWitnessesForEnrolment}.
     */
    public static final String ENROLMENT_WITNESSES_MESSAGE =
        "A Deed Poll cannot be sent for enrolment until two witnesses have been recorded.";

    /**
     * The list order, which the named finders carry in their own names.
     *
     * <p>Stated here for the specification path, because a {@code Specification} says which rows and not
     * in what order. Newest first is how a caseworker finds the case they have just logged, and it is the
     * reference's {@code orderBy}.
     */
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdOn");

    private final DeedPollRepository deedPolls;
    private final ReferenceNumberService referenceNumbers;

    public DeedPollService(DeedPollRepository deedPolls, ReferenceNumberService referenceNumbers) {
        this.deedPolls = deedPolls;
        this.referenceNumbers = referenceNumbers;
    }

    /** Creates a case as the service account, non-admin. */
    @Transactional
    public DeedPollWithComputed createDeedPoll(DeedPollPatch dto) {
        return createDeedPoll(dto, WriteContext.DEFAULT);
    }

    /**
     * Creates a case, allocating its reference number.
     *
     * <p>Per-type required fields are NOT enforced here: a case may be logged incrementally, so they are
     * surfaced through {@link #getCaseFieldRules} and {@link #validateCaseForSubmit} for the submit flow.
     *
     * @throws InvalidInputError if the dates contradict each other, or if there is no Date Received to
     *     derive the reference year from
     */
    @Transactional
    public DeedPollWithComputed createDeedPoll(DeedPollPatch dto, WriteContext ctx) {
        DeedPollPatch data = dto.copy().coerceDates();

        // Create-time defaults ported from the workflows (minor gives marital status Single;
        // relinquishment gives refer-to-master Yes plus publish-to-Gazette false).
        data.merge(DeedPollValidation.applyCreateDefaults(merged(null, data)));
        // "Show or hide Date Notice Sent to London Gazette": publish = No clears the notice date. Applied
        // after the create defaults so relinquishment, which forces publish = No, also clears any
        // supplied date.
        data.merge(DeedPollValidation.applyGazetteGate(merged(null, data)));
        // "Show hide and mandate fields for Minors": a non-minor type clears the four minor fields, so
        // they cannot be logged against an Adult case in the first place. A create with no type supplied
        // leaves them alone.
        data.merge(DeedPollValidation.applyMinorGate(merged(null, data)));

        DeedPollType type = merged(null, data).type();
        // Discard a privacy value this caller is not entitled to set, BEFORE the auto-default runs -- so
        // what defaultPrivacy() sees as "current" is a permitted value and it falls back to Normal rather
        // than honouring the request.
        applyPrivacyAuthorisation(data, type, ctx, true);
        // Privacy auto-default on create (ported from deedpoll_javascript OnChangeType): depends on type
        // plus admin status. isNew is always true here.
        // Read through DeedPollFields.asType, not narrowed with instanceof, for the reason the `type` read
        // in update() gives: a patch places no type constraint on its values and FormPayload yields a
        // member NAME for an enum field. Narrowing would read "Private" as "no privacy supplied", and
        // because null means "fall back" to defaultPrivacy the failure would be silent -- an admin's
        // explicit Private stored as Normal, with nothing raised.
        data.set("privacy", FormPresentation.defaultPrivacy(
            type,
            DeedPollFields.asType(Privacy.class, data.get("privacy")),
            new PresentationContext(ctx.isAdmin(), true)));

        // The same cross-field date rules as update(). Checked before a serial is allocated, so a
        // rejected create never consumes one. There is no existing record to merge against on create: the
        // payload IS the whole record.
        requireConsistentDates(merged(null, data));

        DeedPoll record = new DeedPoll();
        data.applyTo(record);

        // Allocation and the insert share the caller's transaction, so the per-year serial is never
        // skipped or duplicated under concurrency -- see ReferenceNumberService.
        ReferenceNumberService.AllocatedReference reference =
            referenceNumbers.allocateForCreate(record.getType(), record.getDateReceived());
        record.setReferencenumber(reference.referencenumber());
        record.setDisplayReference(reference.displayReference());
        record.setState(CaseState.deriveState(CaseStateInput.from(record)));
        // Both stamped on create: the source header shows a Modified By from the moment a record exists,
        // and a create IS the first modification.
        record.setCreatedBy(ctx.author());
        record.setModifiedBy(ctx.author());

        return Computed.withComputed(deedPolls.save(record));
    }

    /** Every case, newest first, each with its derived day counts. */
    @Transactional(readOnly = true)
    public List<DeedPollWithComputed> findDeedPolls() {
        return findDeedPolls(ListFilters.LIST_FILTERS.get(0));
    }

    /** The cases one saved-query filter admits, newest first, each with its derived day counts. */
    @Transactional(readOnly = true)
    public List<DeedPollWithComputed> findDeedPolls(ListFilters.ListFilter filter) {
        List<DeedPoll> found = filter.where()
            .map(this::casesMatching)
            .orElseGet(deedPolls::findAllByOrderByCreatedOnDesc);
        return Computed.withComputedAll(found);
    }

    /**
     * The cases a saved-query filter and a field-level query both admit, newest first.
     *
     * <p>The two compose with AND: {@code ?filter=open&where=type:is:Adult} means both, because the tabs
     * are ported system views and a bookmarked {@code ?filter=open} has to keep meaning what it meant.
     *
     * <p><strong>An empty query is today's path, not an empty specification.</strong> A list with no
     * conditions is by far the common request, and it is the one whose behaviour is pinned -- so it goes
     * through the same named finders it always has, with the same SQL. The specification is reached only
     * by a request that could not have been served before.
     *
     * @param query the conditions, already validated; {@link CaseQuery#NONE} for a request with no
     *     {@code where}
     */
    @Transactional(readOnly = true)
    public List<DeedPollWithComputed> findDeedPolls(ListFilters.ListFilter filter, CaseQuery query) {
        if (query.isEmpty()) {
            return findDeedPolls(filter);
        }
        return Computed.withComputedAll(
            deedPolls.findAll(CaseSpecifications.matching(filter, query), NEWEST_FIRST));
    }

    /**
     * One case with its children and derived columns.
     *
     * <p>The three child collections are fetched here rather than left lazy, because every consumer of a
     * single case shows them and a caller outside this transaction cannot fetch them itself.
     *
     * <p>That is three queries: the case with its documents and their types in one, then notes and
     * witnesses separately. Fetching more than one collection in a single query is a multiple-bag fetch,
     * which Hibernate answers with a cartesian product -- hence the split rather than one graph covering
     * all three. The reference's Prisma {@code include} issues one query per relation, so this is a
     * query fewer rather than more.
     *
     * <p><strong>Each document's type comes back with the documents, not per document.</strong> The
     * association is a lazy {@code @ManyToOne}, so touching {@code getDocumentType()} in a loop -- which
     * is what this method used to do -- was one select per document. See
     * {@code DeedPollRepository.findWithDocumentsById}.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public DeedPollWithComputed findDeedPoll(String id) {
        DeedPoll record = deedPolls.findWithDocumentsById(id)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + id + " not found"));
        record.getNotes().size();
        record.getWitnesses().size();
        return Computed.withComputed(record);
    }

    /** Updates a case as the service account, non-admin. */
    @Transactional
    public DeedPollWithComputed updateDeedPoll(String id, DeedPollPatch dto) {
        return updateDeedPoll(id, dto, WriteContext.DEFAULT);
    }

    /**
     * Applies a patch to a case.
     *
     * <p>The case is read under a row lock held to this transaction's commit, because everything below is a
     * read-modify-write: the merge, the gates, the date rules and the derived state all read the record as it
     * was loaded, and the write is the whole row. Two concurrent patches reading the same pre-patch row would
     * otherwise have the second overwrite the first even where they touched different fields.
     * {@link DeedPollRepository#findByIdForUpdate} has the full reasoning and why the alternative was not
     * taken.
     *
     * @throws NotFoundError if there is no such case
     * @throws InvalidInputError if the merged dates contradict each other, or if the write would leave the
     *     case in an enrolment state with fewer than two witnesses
     */
    @Transactional
    public DeedPollWithComputed updateDeedPoll(String id, DeedPollPatch dto, WriteContext ctx) {
        DeedPoll record = requireDeedPollForUpdate(id);
        DeedPollPatch data = dto.copy().coerceDates();

        // If Type is part of this update, recompute the prefix from the new type, preserving the existing
        // year and serial (a DP-to-DR flip re-prefixes; the serial is never re-allocated). A Date Received
        // edit does NOT renumber. Empty when the stored reference cannot be parsed, in which case it is
        // left untouched.
        String reprefixed = null;
        if (data.has("type")) {
            // Read through the same coercion every other reader of a patch uses, rather than cast. A patch
            // places no type constraint on its values -- DeedPollFields' writer for `type` coerces a member
            // name, and FormPayload produces exactly that for an enum field -- so a cast here would be a
            // ClassCastException on a legitimate patch, surfacing as a 500.
            reprefixed = ReferenceNumbers
                .reprefixForUpdate(record.getDisplayReference(),
                                   DeedPollFields.asType(DeedPollType.class, data.get("type")))
                .orElse(null);
        }

        // The existing record overlaid with the patch. Every rule below reads this rather than the patch,
        // because a partial update touching one field of a pair is valid or not depending on the other,
        // which it may never mention.
        ValidationInput merged = merged(record, data);

        // "Show or hide Date Notice Sent to London Gazette": if the merged record has publish = No, clear
        // the notice date -- whether the No or the date came from this patch or already existed.
        data.merge(DeedPollValidation.applyGazetteGate(merged));

        // "Show hide and mandate fields for Minors": clear the four minor fields when the MERGED type is
        // not a minor type, so converting Minor to Adult drops the now-meaningless minor data, while a
        // patch that never mentions type keeps whatever the existing type already permits.
        data.merge(DeedPollValidation.applyMinorGate(merged));

        // This throws, where the privacy lock silently drops a field -- the two are different kinds of
        // problem. Privacy is a field the caller was not entitled to set, and the rest of their save is
        // still legitimate. A contradictory pair of dates makes the record itself incoherent, and the
        // derived state would be a guess: the source shows an error and refuses, so silently keeping one
        // date would invent a decision the caseworker did not make.
        requireConsistentDates(merged);

        // The two-witness rule, checked here rather than in CaseState because it is not one of the source's
        // date rules -- see requireWitnessesForEnrolment.
        requireWitnessesForEnrolment(record, data, merged);

        // Privacy is admin-only on an existing record. Enforced on the write path because the template's
        // disabled attribute is a courtesy to the browser and not a control: until 2026-08-10 a non-admin
        // POSTing privacy, or PATCHing the API, wrote it. The MERGED type is used for the same reason the
        // gates above use it -- the entitlement depends on the type the record will have after this write.
        applyPrivacyAuthorisation(data, merged.type(), ctx, false);

        data.applyTo(record);
        if (reprefixed != null) {
            record.setDisplayReference(reprefixed);
        }
        // Recalculated from the merged dates, so a partial update touching one date still derives
        // correctly -- and so a state a caller sent is never the one that is stored.
        record.setState(CaseState.deriveState(CaseStateInput.from(merged)));
        // Stamp the author of THIS write.
        record.setModifiedBy(ctx.author());
        // And its time, stamped here rather than left to @PreUpdate. Hibernate only fires @PreUpdate when
        // its dirty check finds a changed property, so an empty patch by the case's own last author, with a
        // derived state that does not move, would issue no UPDATE at all and leave the header's "Modified"
        // row on the previous edit. UpdateCaseRequest states an empty body is not a no-op, and the
        // reference's `prisma.update` writes @updatedAt on every call.
        record.setModifiedOn(AuditClock.now());

        return Computed.withComputed(deedPolls.save(record));
    }

    /**
     * Deletes a case, and its notes, witnesses and documents with it.
     *
     * <p>The children go by the database's cascade, which {@code V3__children.sql} declares; nothing
     * here deletes them, so there is one place that decides what a case deletion takes with it.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional
    public void removeDeedPoll(String id) {
        deedPolls.delete(requireDeedPoll(id));
    }

    /**
     * Field show / hide / mandate rules for a Type, for the form to mirror the Dynamics form's per-type
     * behaviour.
     */
    public Map<String, FieldRule> getCaseFieldRules(DeedPollType type, boolean migrated, boolean publishToGazette) {
        return DeedPollValidation.getFieldRules(type, migrated, publishToGazette);
    }

    /**
     * Validates a case for submission -- the point at which the source form's required levels must all be
     * satisfied.
     *
     * <p>Kept separate from {@link #updateDeedPoll} so incremental saves stay unblocked.
     *
     * @throws NotFoundError if there is no such case
     * @throws InvalidInputError with the collected violations if any remain
     */
    @Transactional(readOnly = true)
    public DeedPollWithComputed validateCaseForSubmit(String id) {
        DeedPollWithComputed record = findDeedPoll(id);
        List<FieldError> errors = DeedPollValidation.validate(ValidationInput.from(record.deedPoll()));
        if (!errors.isEmpty()) {
            throw new InvalidInputError("Case is not valid for submission", errors);
        }
        return record;
    }

    /** The cases one state criterion admits, newest first. */
    private List<DeedPoll> casesMatching(ListFilters.StateWhere where) {
        return switch (where) {
            case ListFilters.In in -> deedPolls.findByStateInOrderByCreatedOnDesc(in.states());
            case ListFilters.NotIn notIn -> deedPolls.findOpenOrderByCreatedOnDesc(notIn.states());
        };
    }

    private DeedPoll requireDeedPoll(String id) {
        return deedPolls.findById(id)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + id + " not found"));
    }

    /**
     * As {@link #requireDeedPoll}, but locking the row for the caller's transaction.
     */
    private DeedPoll requireDeedPollForUpdate(String id) {
        return deedPolls.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + id + " not found"));
    }

    /**
     * The reference's {@code merged = &#123;...existing, ...data&#125;}, as a field map.
     *
     * <p>A null {@code record} is a create, where the payload is the whole record.
     */
    private static ValidationInput merged(DeedPoll record, DeedPollPatch data) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (record != null) {
            values.putAll(DeedPollFields.readAll(record));
        }
        values.putAll(data.values());
        return ValidationInput.of(values);
    }

    private static void requireConsistentDates(ValidationInput merged) {
        List<FieldError> dateErrors = CaseState.collectDateRuleErrors(CaseStateInput.from(merged));
        if (!dateErrors.isEmpty()) {
            throw new InvalidInputError("Case dates are inconsistent", dateErrors);
        }
    }

    /**
     * Refuses a write that would leave a case in an enrolment state with fewer than two witnesses.
     *
     * <p><strong>NOT a port -- the source has no such rule.</strong> {@code business_rules.md} mentions
     * witnesses zero times and no Power App artefact we hold enforces a count; this is a rule the business
     * gave us on 2026-09-13, marked the way {@link CaseState#SIGNATURE_ORDER_MESSAGE} is so that a reader
     * comparing this service against the reference can still tell which rules are ported and which are ours.
     *
     * <p><strong>Why here and not in {@link CaseState}.</strong> {@code collectDateRuleErrors} is pure and
     * takes only the eight state dates, and {@code StateCascadeConformanceTest} pins it against the rules
     * file. Widening {@link CaseStateInput} to carry a witness count would put a non-ported concern inside
     * the ported cascade, which is exactly the line the paragraph above is protecting.
     *
     * <p><strong>Why both dates.</strong> Two dates carry the enrolment meaning in the cascade:
     * {@code signatureReceivedDate} gives Awaiting Enrolment ("sent for enrolment") and {@code dateEnroled}
     * gives Enroled. Gating only the second would let a case be queued for an enrolment it can never
     * complete.
     *
     * <p><strong>Fewer than two, not exactly two.</strong> The business said "there must be two witnesses"
     * and "if an application doesn't have 2 witnesses it can't be sent for enrolment" -- a shortfall rather
     * than a cap, so three passes. A maximum would have to be enforced at witness creation, which is a
     * different rule in {@code WitnessService} and was not asked for.
     *
     * <p><strong>A migrated record is subject to this rule too</strong>, confirmed by the business on
     * 2026-09-13: "For existing cases the two witness rule will still apply." That is a deliberate
     * departure from the pattern around it -- the Power App relaxes every per-type mandatory field when
     * {@code Migrated Record = Yes} (see {@code FieldRulesConformanceTest$MigratedRecords}) -- so
     * {@code migratedRecord} is deliberately not consulted below, and this rule must not be "tidied up"
     * into consistency with the others.
     *
     * <p><strong>Checked against the MERGED record</strong>, as every other rule on this path is: a patch
     * touching only a surname on a case that already carries an enrolment date is still a write that leaves
     * the record enrolled. The escape from a case that is short is the same shape as the escape from a
     * contradictory pair of dates -- clear the date, or (here) add the missing witnesses, which is an
     * ungated witness write.
     *
     * <p><strong>Not called from create.</strong> A case's witnesses are children of a row that has to
     * exist before they can, so a create can never carry two of them and gating it would forbid the state
     * outright rather than gate a transition into it. The rule is about sending an application for
     * enrolment, which a logged case does later.
     *
     * <p>The count is taken from the record's LAZY witnesses collection, so this must stay inside the
     * write transaction. It is: the caller is {@code @Transactional}.
     */
    private static void requireWitnessesForEnrolment(DeedPoll record, DeedPollPatch data, ValidationInput merged) {
        CaseStateInput dates = CaseStateInput.from(merged);
        if (dates.dateEnroled() == null && dates.signatureReceivedDate() == null) {
            return; // this write does not leave the case in an enrolment state
        }

        int recorded = record.getWitnesses().size();
        if (recorded >= WITNESSES_REQUIRED_FOR_ENROLMENT) {
            return;
        }

        throw new InvalidInputError(
            "Case cannot be sent for enrolment",
            List.of(new FieldError(enrolmentDateToAddress(data, dates), witnessShortfallMessage(recorded))));
    }

    /**
     * Which of the two enrolment dates the refusal is addressed to.
     *
     * <p>Addressed to a date and never to a witnesses field: there is no witness control on the case form,
     * so an error naming one would have nowhere to render, and {@code deed-poll-admin} builds its error
     * summary by mapping the field name to a control on the tab being edited -- an unmappable name breaks
     * the summary link rather than only its styling.
     *
     * <p>The date THIS write sets is preferred, so the error lands on the box the caseworker just filled in.
     * A write that sets neither -- an edit to some other field of an already-enrolled case -- falls back to
     * the cascade's own precedence, so the error names the date that decides the state the record is in.
     */
    private static String enrolmentDateToAddress(DeedPollPatch data, CaseStateInput dates) {
        if (data.has("dateEnroled") && dates.dateEnroled() != null) {
            return "dateEnroled";
        }
        if (data.has("signatureReceivedDate") && dates.signatureReceivedDate() != null) {
            return "signatureReceivedDate";
        }
        return dates.dateEnroled() != null ? "dateEnroled" : "signatureReceivedDate";
    }

    /**
     * The refusal message, naming the shortfall.
     *
     * <p>A caseworker needs to know what to do next, so it says how many are recorded and how many are
     * missing -- compare {@link CaseState#CONTRADICTION_MESSAGE}, which tells the reader what to remove and
     * why rather than only that something is wrong.
     */
    private static String witnessShortfallMessage(int recorded) {
        // Only reached when the record is short, so recorded is 0 or 1.
        String shortfall = recorded == 1
            ? "This case has one; please add one more witness."
            : "This case has none; please add two witnesses.";
        return ENROLMENT_WITNESSES_MESSAGE + " " + shortfall;
    }

    /**
     * Drops {@code privacy} from a write when the caller is not entitled to set it.
     *
     * <p>Authorisation, so it belongs on the write path and not in the template. The edit form renders the
     * control disabled for a non-admin, but a disabled attribute is a courtesy to a cooperating browser:
     * until 2026-08-10 a hand-rolled POST, or an API PATCH, set privacy freely. On create the lock was
     * accidental rather than absent -- {@code defaultPrivacy()} overwrites the caller's value for types
     * 1-5, but Unknown and a typeless create fall through its default branch to "keep the current value",
     * so a non-admin could create a Private case simply by omitting the type.
     *
     * <p>The entitlement rule is NOT restated here: it is {@link FormPresentation#privacyEditable}, the
     * same function the form consults, so the lock and the input's disabled state cannot drift apart. Note
     * that rule permits one non-admin case -- a NEW relinquishment.
     *
     * <p>Removes the field rather than throwing. The source disables the control instead of erroring, and
     * a form that legitimately omits a disabled input must still be able to save the other fields on its
     * tab; a 403 would block a save the caseworker is entitled to make. Every other field in the patch is
     * untouched.
     */
    private static void applyPrivacyAuthorisation(DeedPollPatch data,
                                                  DeedPollType type,
                                                  WriteContext ctx,
                                                  boolean isNew) {
        if (!data.has("privacy")) {
            return; // not part of this write
        }
        if (FormPresentation.privacyEditable(type, new PresentationContext(ctx.isAdmin(), isNew))) {
            return;
        }
        data.remove("privacy");
    }
}
