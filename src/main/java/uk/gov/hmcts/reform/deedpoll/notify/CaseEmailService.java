package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.IncompleteDataError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The emails a caseworker may send about one case: which ones apply, what one will look like, and owing it.
 *
 * <p>The three operations the Email tab calls, and the seam between the content model
 * ({@link EmailTemplate}, {@link EmailSlots}, {@link ApplicationRejectedPersonalisation}) and the outbox
 * ({@link NotificationOutbox}). Nothing in here decides wording, and nothing in here talks to Notify except
 * {@link #preview}.
 *
 * <p><strong>Why there is a tab at all, rather than an email sent automatically on a state change.</strong>
 * Three of the four templates the business supplied need text a human writes. So the caseworker picks the email,
 * is shown it populated from the case and the referral, fills the slots and sends -- and the send is a
 * deliberate act by somebody who has read what is going out.
 *
 * <p><strong>Resolved values are not client input, and that is enforced rather than documented.</strong> A
 * request carries only the slots; the reference number, the applicant's former names and the refund branch are
 * read off the case here. {@link EmailSlots#parse} refuses a request that tries to supply one of them by name.
 * A caseworker altering the reference an applicant is told, or flipping the refund branch, is a correctness and
 * a fraud problem rather than a convenience -- so the payload has no way to express it and is told so if it
 * tries.
 *
 * <p><strong>Every operation is scoped to its case, and the referral is reached through it.</strong> A case id
 * that names nothing is a 404 before anything else happens, and the referral is found by
 * {@code ReferralRepository.findByDeedPollIdOrderByCreatedOnDesc} -- the case-scoped finder -- so an email
 * cannot be composed from another case's refusal. That is {@code NoteService}'s rule and
 * {@code ReferralService}'s, and it is the same rule here.
 *
 * <p><strong>The four answers a send can give, and why each is the status it is.</strong> 404 for a case that
 * does not exist. 409 when the case is in no state to send this email -- nothing about the request is wrong and
 * it would succeed unchanged once a Master has refused the application, or it would have succeeded had the
 * email that contradicts it not already been asked for. 422 when the case has no applicant email
 * address, which is a fact about the record that no retry will change (decision 7, and
 * {@link IncompleteDataError} has the argument). 400 for a slot payload the schema refuses, with every offending
 * field named at once. The recipient is checked <em>before</em> the payload, deliberately: a caseworker whose
 * case has no address cannot fix that by editing what they typed, so making them correct a payload first would
 * be two refusals where one was useful.
 *
 * <p><strong>Nothing here sends.</strong> A send resolves, enqueues in the caller's transaction -- stamping the
 * referral's {@code confirmationRequestedAt} in that same transaction where the email is the one that asks the
 * applicant to reply, which is the only thing a send writes outside the outbox -- and returns;
 * the drain is what talks to Notify, and it is called after this transaction commits -- see
 * {@link NotificationOutbox}, and {@code CaseworkerEmailController} for why the order is not an accident.
 */
@Slf4j
@Service
public class CaseEmailService {

    /**
     * Why <em>Application Rejected</em> does not apply to a case, in words a caseworker can act on.
     *
     * <p>The condition is a referral of this case whose {@code decision} is {@link MasterDecision#Rejected}:
     * this email quotes a judge refusing enrolment, so without one there is nothing to quote and nobody has
     * decided anything to tell the applicant about.
     */
    static final String NO_REFUSAL = "This case has no referral that a Master has refused, so there is no "
        + "refusal to tell the applicant about";

    /**
     * Why <em>Request confirmation to proceed</em> does not apply to a case that has had nothing refused.
     *
     * <p>The first of that email's two conditions, and its own sentence rather than half of a combined one:
     * a caseworker can act on the difference between "nobody has decided this yet" and "what was decided was
     * not about publication", and the second would send them looking for a decision that exists.
     */
    static final String NO_REFUSAL_TO_CONFIRM = "This case has no referral that a Master has refused, so there "
        + "is no refused request to ask the applicant to confirm";

    /**
     * Why <em>Request confirmation to proceed</em> does not apply to a case whose refusal was about something
     * else.
     *
     * <p>The second condition: the newest refused referral must have been raised for
     * {@link ReferralReason#NonPublication}. A refusal on any other reason cannot be a refusal of a
     * non-publication request, so there is nothing to ask the applicant to confirm -- and the newest refusal is
     * the only one either email may speak for, so an older non-publication refusal does not revive this.
     */
    static final String REFUSAL_NOT_ABOUT_NON_PUBLICATION = "This case's most recent refusal was not raised for "
        + "a non-publication request, so there is no refused request to ask the applicant about";

    private final DeedPollRepository deedPolls;
    private final ReferralRepository referrals;
    private final NotificationRepository notifications;
    private final DeedPollSupportingDocumentRepository documents;
    private final NotificationClient notify;
    private final NotifyProperties properties;

    /**
     * Only to bring the case's {@code awaitingConfirmationSince} into step when ④ starts the clock -- see
     * {@link #startConfirmationClock}.
     *
     * <p>The service and not the two repositories it would take, so there is one implementation of the
     * recompute rather than a copy of it here: {@code ReferralService.refreshAwaitingConfirmation} says why it
     * is public. No cycle -- nothing in the referral service knows this package exists.
     */
    private final ReferralService referralService;

    /**
     * Only to write the {@code personalisation} column, which {@link NotificationRepository#enqueue} takes as
     * JSON text because its statement is native.
     */
    private final ObjectMapper json;

    public CaseEmailService(DeedPollRepository deedPolls,
                           ReferralRepository referrals,
                           NotificationRepository notifications,
                           DeedPollSupportingDocumentRepository documents,
                           NotificationClient notify,
                           NotifyProperties properties,
                           ReferralService referralService,
                           ObjectMapper json) {

        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notifications = notifications;
        this.documents = documents;
        this.notify = notify;
        this.properties = properties;
        this.referralService = referralService;
        this.json = json;
    }

    /**
     * Every email this service knows about, and whether this case may have each one sent.
     *
     * <p>All of them, applicable or not -- {@link EmailAvailability} says why.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<EmailAvailability> available(String deedPollId) {
        requireCase(deedPollId);
        return Arrays.stream(EmailTemplate.values()).map(template -> availability(template, deedPollId)).toList();
    }

    /**
     * Everything the Email tab needs, resolved without asking Notify to render anything.
     *
     * <p>Defaults come from {@link #compose}, the same path preview and send use, so a compose form opens with
     * exactly the values an empty preview request would use without spending a Notify API call merely to discover
     * them.
     */
    @Transactional(readOnly = true)
    public CaseEmailOverview overview(String deedPollId) {
        List<EmailAvailability> availability = available(deedPollId);
        List<EmailSlotDefaults> defaults = availability.stream()
            .filter(EmailAvailability::applicable)
            .map(entry -> slotDefaults(deedPollId, entry.template()))
            .toList();
        return new CaseEmailOverview(availability, correspondence(deedPollId), defaults);
    }

    /**
     * Every email attempt recorded for one case, newest first.
     *
     * <p>The repository supplies the order. Document ids are read only from keys declared by the stored template
     * and resolved only among this case's documents, so neither raw personalisation nor another case's document
     * can cross this boundary. An obsolete template remains visible with no documents rather than making the
     * entire history unreadable.
     *
     * @throws NotFoundError if there is no such case
     */
    @Transactional(readOnly = true)
    public List<CaseCorrespondence> correspondence(String deedPollId) {
        requireCase(deedPollId);
        List<Notification> history = notifications.findByDeedPollIdOrderByCreatedOnDesc(deedPollId);
        if (history.isEmpty()) {
            return List.of();
        }

        Map<String, DeedPollSupportingDocument> caseDocuments =
            documents.findByDeedPollIdOrderByCreatedOnAsc(deedPollId).stream()
                .collect(Collectors.toMap(DeedPollSupportingDocument::getId, Function.identity()));

        return history.stream().map(notification -> correspondence(notification, caseDocuments)).toList();
    }

    private static CaseCorrespondence correspondence(
        Notification notification,
        Map<String, DeedPollSupportingDocument> caseDocuments
    ) {
        Optional<EmailTemplate> knownTemplate = EmailTemplate.byName(notification.getTemplate());
        if (knownTemplate.isEmpty()) {
            return new CaseCorrespondence(
                notification.getId(),
                notification.getTemplate(),
                notification.getTemplate(),
                notification.getCreatedOn(),
                notification.getCreatedBy(),
                notification.getRecipient(),
                notification.getState(),
                notification.getFailureReason(),
                notification.getSubject(),
                notification.getBody(),
                List.of());
        }

        EmailTemplate template = knownTemplate.get();
        List<CorrespondenceDocument> carried =
            CorrespondenceDocument.from(notification, template.documentKeys(), caseDocuments);
        return CaseCorrespondence.from(notification, template, carried);
    }

    private EmailSlotDefaults slotDefaults(String deedPollId, EmailTemplate template) {
        Map<String, Object> personalisation = compose(deedPollId, template, Map.of()).personalisation();
        Map<String, String> fields = new LinkedHashMap<>();
        for (EmailSlot slot : template.slots()) {
            fields.put(slot.placeholder(), (String) personalisation.getOrDefault(slot.placeholder(), ""));
        }
        return new EmailSlotDefaults(template, fields);
    }

    /**
     * What Notify says this email will look like, rendered from the caseworker's current slot text.
     *
     * <p><strong>No outbox row is written, and no state changes.</strong> A preview is a read that happens to
     * cost an outbound call -- which is why a tab should preview on an explicit action rather than on every
     * keystroke: each one counts against Notify's rate limit, and previewing per keystroke would reach the 429
     * branch in normal use.
     *
     * <p><strong>With an unprovisioned template ID this answers 502, and that is correct.</strong> Until notify-0
     * creates the Notify service there is no template to render, so Notify answers 400 -- the taxonomy's
     * permanent branch -- and this reports that its dependency refused rather than pretending to have rendered
     * something. {@link EmailAvailability#provisioned()} is how the tab knows in advance and says so instead.
     *
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case is in no state to send this email
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError if the slots are not acceptable
     * @throws UpstreamFailureError if Notify would not render it
     */
    @Transactional(readOnly = true)
    public EmailPreview preview(String deedPollId, EmailTemplate template, Map<String, Object> slots) {
        Composed composed = compose(deedPollId, template, slots);
        String templateId = template.templateId(properties);
        try {
            return EmailPreview.from(notify.generateTemplatePreview(templateId, composed.personalisation()));
        } catch (NotificationClientException refused) {
            NotificationFailure verdict = NotificationFailure.classify(refused);
            // Logged in full here and summarised in the answer: the client's message contains Notify's response,
            // and this body is rendered by whatever is on the other end.
            log.warn("Notify would not render {} for case {} ({}, status {})",
                     template.key(), deedPollId, verdict, refused.getHttpResult(), refused);
            throw new UpstreamFailureError("GOV.UK Notify could not render the " + template.key()
                + " template (" + verdict + ", status " + refused.getHttpResult() + ")");
        }
    }

    /**
     * Owes the applicant this email: resolves it, freezes it into an outbox row, and returns the row.
     *
     * <p><strong>The recipient and the personalisation are frozen here rather than recomputed later.</strong> A
     * later edit to the case cannot redirect an email already owed, and "what was this applicant told" stays
     * answerable from the row even though part of the content is text the caseworker wrote and may have edited.
     *
     * <p><strong>A second send of the same email is a no-op that answers with the first row.</strong> The
     * insert is {@code ON CONFLICT DO NOTHING} on the partial unique index, so a double-click or two replicas
     * racing cannot tell an applicant twice that their application was refused -- and the caseworker is not shown
     * a failure for a request that was merely redundant.
     *
     * <p><strong>A send of the email that contradicts one already asked for is a 409 naming it.</strong> The key
     * is the template's exclusion group, so a referral gets one email of the group; the second one is refused by
     * the same {@code DO NOTHING} clause, which is what keeps the caseworker's transaction usable instead of
     * handing them a {@code DataIntegrityViolationException}. Which of the two happened is read off the blocking
     * row rather than guessed.
     *
     * <p>{@code templateVersion} is left null: this row was enqueued without asking Notify to render anything, so
     * there is no version to record and inventing one would claim knowledge of the wording. The sender records
     * the version Notify reports when it accepts the send.
     *
     * <p><strong>Sending ④ also starts the clock on the reply it asks for</strong>, in this transaction and with
     * the row's own instant -- see {@link #startConfirmationClock}. That is the one thing a send does to anything
     * other than the outbox, and it is why the two commit or roll back together.
     *
     * @param caller the caseworker sending it, who becomes {@code createdBy} -- not audit noise here, because
     *     they authored part of what the applicant reads
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case is in no state to send this email, or the email that contradicts this one
     *     has already been asked for on the same referral
     * @throws IncompleteDataError if the case has no applicant email address
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError if the slots are not acceptable
     */
    @Transactional
    public SendReceipt send(String deedPollId,
                           EmailTemplate template,
                           Map<String, Object> slots,
                           CurrentUser caller) {

        Composed composed = compose(deedPollId, template, slots);
        String recipient = requireRecipient(composed.deedPoll());
        UUID referralId = composed.referral().getId();
        UUID id = UUID.randomUUID();
        // One instant for the row and for the clock the next paragraph starts, read once: two calls could
        // straddle a tick and make the outbox row and the referral disagree about when the applicant was asked.
        LocalDateTime now = AuditClock.now();

        int enqueued = notifications.enqueue(
            id,
            deedPollId,
            referralId,
            template.name(),
            template.exclusionGroup().name(),
            recipient,
            asJson(composed.personalisation()),
            now,
            caller.name());

        if (enqueued == 1) {
            startConfirmationClock(template, deedPollId, composed.referral(), now, caller);
            return SendReceipt.enqueued(id, recipient);
        }

        // Zero rows: the unique index refused it, which is the mechanism working rather than a failure. Since the
        // key is the exclusion group, that has two meanings and they get two different answers, so the row it
        // collided with has to be read before either can be given.
        Notification blocking = notifications
            .findBlocking(deedPollId, referralId, template.exclusionGroup().name())
            // Unreachable in practice: the insert was refused, so the row it collided with exists and satisfies
            // the same predicate this read applies. A conflict with a row that then could not be read would mean
            // the collision was with another case's row, which the partial index cannot produce -- so this says
            // so rather than answering with a null id.
            .orElseThrow(() -> new IllegalStateException(
                "Enqueueing " + template.key() + " for referral " + referralId + " of case " + deedPollId
                    + " was refused by the unique index, but no such row is readable"));

        if (!template.name().equals(blocking.getTemplate())) {
            // The other email of the group. A 409 rather than a duplicate, because sending this one would tell
            // the applicant the opposite of what they have already been told -- and because nothing the
            // caseworker typed is wrong, so there is no field to correct.
            log.info("Case {} already owes {} for referral {}, so {} was refused",
                     deedPollId, blocking.getTemplate(), referralId, template.key());
            throw new ConflictError(alreadyRequested(blocking));
        }

        // The same email again: a caseworker pressing send twice, or a replica racing. Logged because those look
        // identical from here and the frequency is worth knowing.
        log.info("Case {} already owes {} for referral {}, so nothing was enqueued",
                 deedPollId, template.key(), referralId);
        return SendReceipt.alreadyOwed(blocking);
    }

    /**
     * Starts the clock on the confirmation this send asks for, where the send is the email that asks for one.
     *
     * <p><strong>Here rather than on delivery, and in the caller's transaction rather than the drain's.</strong>
     * The outbox's whole design is that the row is written inside the caseworker's transaction, and hanging a
     * case's process state off an asynchronous outcome that may still be retrying would make "is this case waiting
     * for a reply?" unanswerable for as long as the retries last. So the stamp commits with the row: either the
     * applicant is owed the email and the case is recorded as waiting, or neither happened.
     *
     * <p><strong>The consequence is real and named rather than hidden:</strong> if the send then fails terminally,
     * the case sits in the waiting list expecting a reply to an email nobody received. That is notify-1's flag 8
     * and is not fixed here; the two should be closed together.
     *
     * <p>Only on a fresh enqueue. A second send of the same email writes no row -- the unique index refuses it --
     * and must not move a clock that is already running, or a caseworker double-clicking would reset the wait they
     * are being reminded of. A replacement after a terminal failure <em>does</em> re-stamp, which is correct: the
     * applicant has been asked again, so the wait starts from the asking.
     *
     * <p><strong>Two rows, one transaction:</strong> the referral's stamp, and the case's
     * {@code awaitingConfirmationSince} -- the read-model the caseworker waiting list filters, sorts and counts
     * days from. The second is recomputed from the referral table rather than set to {@code now}, because the
     * case's column is the earliest outstanding request on it; {@code ReferralService.refreshAwaitingConfirmation}
     * is the one implementation of that rule.
     *
     * <p>An exhaustive switch rather than an {@code if}, so a template added to the enum has to say whether it
     * starts a clock instead of silently inheriting this one's answer.
     */
    private void startConfirmationClock(EmailTemplate template,
                                        String deedPollId,
                                        Referral referral,
                                        LocalDateTime now,
                                        CurrentUser caller) {
        switch (template) {
            case REQUEST_CONFIRMATION_TO_PROCEED -> {
                referral.setConfirmationRequestedAt(now);
                // The caseworker into both audit columns, not just the timestamp: the row did change, and moving
                // modifiedOn while modifiedBy still named the judge who directed it would attribute this to them.
                referral.setModifiedBy(caller.name());
                referral.setModifiedOn(now);
                referrals.save(referral);
                // And the case's read-model of the same fact, in this transaction, so the case reaches the
                // waiting list with the email it is waiting on. A recompute from the referral table rather than
                // a copy of `now`: the case's column is the EARLIEST outstanding request on it, which this
                // method cannot know and ConfirmationWait can.
                // The case id the outbox row was written with, not one navigated off the referral: the two are
                // the same case, and asking the row's own id keeps the read-model and the row talking about it.
                referralService.refreshAwaitingConfirmation(deedPollId);
            }
            // Tells the applicant a decision and starts a refund. It asks them for nothing, so there is nothing
            // to wait for and no clock to start.
            case APPLICATION_REJECTED -> {
            }
        }
    }

    /**
     * One template's answer for one case.
     *
     * <p>{@code provisioned} is asked of configuration rather than of the case, so it is the same answer for
     * every case in this environment -- and it is on each row rather than on the response as a whole because one
     * template may be provisioned while another is not. That is not hypothetical: a template that invites a reply
     * needs the monitored inbox as well as a UUID, so ④ can read unprovisioned in an environment where ③ does
     * not. See {@link NotifyProperties#isProvisioned}.
     *
     * <p><strong>Two reasons a template does not apply, and the second is a courtesy rather than the
     * enforcement.</strong> Either the case has no referral this email may speak for, or it has one that has
     * already had the email this one contradicts -- and the unique index is what actually refuses the second
     * case. Reporting it here is so the Email tab can grey the option out rather than let a caseworker compose
     * an email that will be refused on submit, which is a bad way to find out.
     */
    private EmailAvailability availability(EmailTemplate template, String deedPollId) {
        boolean provisioned = properties.isProvisioned(template);
        Optional<Referral> newestRefusal = newestRefusal(deedPollId);
        Optional<Referral> cause = cause(template, newestRefusal);
        if (cause.isEmpty()) {
            return EmailAvailability.unavailable(
                template, reasonNotApplicable(template, newestRefusal), provisioned);
        }
        return counterpart(deedPollId, cause.get(), template)
            .map(blocking -> EmailAvailability.unavailable(template, alreadyRequested(blocking), provisioned))
            .orElseGet(() -> EmailAvailability.applicable(template, provisioned));
    }

    /**
     * The row that has taken this referral's slot for some <em>other</em> email of this template's exclusion
     * group, or empty if nothing has.
     *
     * <p><strong>Blocking is the index's definition of it, read through the statement that repeats the index's
     * predicate.</strong> A {@code Failed} row with a {@code PERMANENT} verdict does not occupy the index --
     * Notify definitely sent nothing, and notify-1 deliberately permits a replacement -- so it must not hide
     * either template. Everything else does block, including a verdict-less failure written by an older
     * instance, because any of those may already have reached Notify.
     *
     * <p>A row for <em>this</em> template is not a counterpart and is not reported here: a second send of the
     * same email is the idempotent no-op {@link #send} answers with the original row, not something to hide the
     * template over.
     */
    private Optional<Notification> counterpart(String deedPollId, Referral cause, EmailTemplate template) {
        return notifications
            .findBlocking(deedPollId, cause.getId(), template.exclusionGroup().name())
            .filter(blocking -> !template.name().equals(blocking.getTemplate()));
    }

    /**
     * The sentence that says another email of this group has already been asked for, and when.
     *
     * <p><strong>"Requested", not "delivered", deliberately.</strong> The blocking row may be {@code Pending},
     * {@code Sent}, or a {@code Failed} row whose outcome is uncertain -- all this service knows is that the
     * email was asked for and recorded, so that is what it says.
     *
     * <p>The stored member name is turned back into a display name where it still names a template, and left as
     * it stands where it does not: a template renamed after rows were written must still produce a readable
     * sentence rather than nothing at all. The date is formatted as {@link ResolvedValues#date}, which is the
     * format an applicant sees -- a caseworker reading this is reading about an email an applicant has.
     */
    private static String alreadyRequested(Notification blocking) {
        String email = EmailTemplate.byName(blocking.getTemplate())
            .map(EmailTemplate::displayName)
            .orElse(blocking.getTemplate());
        return "The " + email + " email was already requested for this referral on "
            + ResolvedValues.date(blocking.getCreatedOn())
            + ", and one referral gets one of these emails: the two would tell the applicant opposite things "
            + "about enrolment";
    }

    /**
     * The referral that entitles this case to this email, or empty if there is none.
     *
     * <p>A {@code switch} with no {@code default}, so a template added to the enum does not silently inherit
     * another one's rule: it fails to compile until somebody says what makes it applicable.
     *
     * <p>Newest first, because the case-scoped finder is ordered that way and a case referred twice has been
     * refused on whichever referral was most recent -- an older refusal is history, and quoting it would tell the
     * applicant about a decision that has since been superseded.
     *
     * <p><strong>Both referral-driven templates start from the same referral -- the newest refused one -- and
     * only then ask whether it is the kind this template is about.</strong> The tempting shape for ④ is "the
     * newest refused non-publication referral", and it is wrong in a way that is hard to see: a case refused on
     * non-publication and then referred again and refused for something else would revive the older outcome that
     * ③'s rule has already treated as superseded. Filtering after the {@code findFirst} rather than before it
     * also guarantees that when both templates apply they name the same {@code referralId}, which is what lets
     * one database key make them mutually exclusive.
     *
     * <p>The newest refusal is a parameter rather than read here, so one caller reads it once and then asks both
     * what it entitles the case to and, if it does not, which of the two sentences to say.
     */
    private static Optional<Referral> cause(EmailTemplate template, Optional<Referral> newestRefusal) {
        return switch (template) {
            case APPLICATION_REJECTED -> newestRefusal;
            case REQUEST_CONFIRMATION_TO_PROCEED -> newestRefusal.filter(CaseEmailService::aboutNonPublication);
        };
    }

    /** The case's most recent refused referral, which is the only one either email may speak for. */
    private Optional<Referral> newestRefusal(String deedPollId) {
        return referrals.findByDeedPollIdOrderByCreatedOnDesc(deedPollId).stream()
            .filter(referral -> referral.getDecision() == MasterDecision.Rejected)
            .findFirst();
    }

    /**
     * Whether this referral was raised for a request not to publish in The Gazette.
     *
     * <p>{@code reasons} holds {@link ReferralReason} member names as a JSON array, so the test is against
     * {@link ReferralReason#NonPublication}{@code .name()} rather than against free text -- a referral whose
     * notes happen to mention publication is not a non-publication request.
     *
     * <p><strong>Necessary for ④ without being sufficient to send it.</strong> Availability is derivable only in
     * this direction: a refusal on no non-publication reason certainly is not about one, but a referral raised
     * for several reasons gets one decision and one direction covering all of them, which cannot be decomposed
     * into a per-reason outcome. So this offers ④ <em>alongside</em> ③ and leaves the choice to the caseworker,
     * who is reading the direction in order to action it either way.
     */
    private static boolean aboutNonPublication(Referral referral) {
        List<String> reasons = referral.getReasons();
        return reasons != null && reasons.contains(ReferralReason.NonPublication.name());
    }

    /**
     * The sentence that says why a template does not apply, which is per template for the reason its text is.
     *
     * <p>④ has two sentences because it has two conditions, and a caseworker can act on the difference: a case
     * with no refusal at all is waiting on a judge, while a case whose newest refusal was about something else
     * is one where this email is simply the wrong email. Told which, they do two different things.
     */
    private static String reasonNotApplicable(EmailTemplate template, Optional<Referral> newestRefusal) {
        return switch (template) {
            case APPLICATION_REJECTED -> NO_REFUSAL;
            case REQUEST_CONFIRMATION_TO_PROCEED -> newestRefusal.isPresent()
                ? REFUSAL_NOT_ABOUT_NON_PUBLICATION
                : NO_REFUSAL_TO_CONFIRM;
        };
    }

    /**
     * The case, the referral behind the email, and the personalisation the two of them plus the slots resolve to.
     *
     * <p>Shared by the preview and the send so that what a caseworker approved is what is stored: two resolvers
     * would be two ways of answering the same question, and the one place they could differ is the content of a
     * letter about a refusal.
     */
    private Composed compose(String deedPollId, EmailTemplate template, Map<String, Object> slots) {
        DeedPoll deedPoll = requireCase(deedPollId);
        Optional<Referral> newestRefusal = newestRefusal(deedPollId);
        Referral referral = cause(template, newestRefusal)
            .orElseThrow(() -> new ConflictError(reasonNotApplicable(template, newestRefusal)));

        // The judge's words reach the email only through this slot, pre-populated and editable. ③'s resolve() is
        // deliberately not given the referral at all, and ④'s is given it only for the decision date and
        // documents that it never reads getDirectionsText() -- a second, un-edited copy of the direction in the
        // same email would make "what was this applicant told" a question with two answers.
        Map<EmailSlot, String> accepted = EmailSlots.parse(template, slots, referral.getDirectionsText());
        Map<String, Object> personalisation = switch (template) {
            case APPLICATION_REJECTED ->
                ApplicationRejectedPersonalisation.resolve(deedPoll, accepted).toMap();
            case REQUEST_CONFIRMATION_TO_PROCEED ->
                RequestConfirmationToProceedPersonalisation.resolve(deedPoll, referral, accepted).toMap();
        };
        return new Composed(deedPoll, referral, personalisation);
    }

    /**
     * The address this email is owed to, refused if the case has none.
     *
     * <p><strong>Refused at send rather than skipped, which is the opposite of what {@code pcs-api} does, and
     * the difference is the point.</strong> pcs sends automatically, so skipping a blank address is its only
     * option. Here a caseworker has chosen to send, and silence would leave them believing an applicant had been
     * told something. So the operation answers 422 naming the column, and the Email tab can say "this applicant
     * has no email address".
     *
     * <p>Blank counts as absent: {@code applicantEmail} is nullable text with no format constraint, and a row
     * holding a space would otherwise be enqueued and then fail permanently on the drain, where nobody is
     * waiting to read the failure.
     */
    private String requireRecipient(DeedPoll deedPoll) {
        String recipient = deedPoll.getApplicantEmail();
        if (recipient == null || recipient.isBlank()) {
            throw new IncompleteDataError(
                "This applicant has no email address, so this email cannot be sent",
                List.of(new FieldError("applicantEmail",
                                       "is empty on this case, and is where this email would be sent")));
        }
        return recipient.trim();
    }

    private DeedPoll requireCase(String deedPollId) {
        return deedPolls.findById(deedPollId)
            .orElseThrow(() -> new NotFoundError("DeedPoll " + deedPollId + " not found"));
    }

    /**
     * The personalisation as the {@code jsonb} column's text.
     *
     * <p>Serialised here because {@link NotificationRepository#enqueue} is a native statement and binds the
     * value as text with a cast. A failure is not a caller's mistake and not recoverable -- the map is this
     * service's own resolved values -- so it is an {@link IllegalStateException} rather than one of the domain's
     * refusals.
     */
    private String asJson(Map<String, Object> personalisation) {
        try {
            return json.writeValueAsString(personalisation);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Resolved personalisation could not be written as JSON", impossible);
        }
    }

    /**
     * One request's resolved email: the case it is about, the referral that caused it, and what Notify is given.
     *
     * <p>A record rather than three returns, so the preview and the send cannot resolve two of the three and
     * disagree about the fourth.
     */
    private record Composed(DeedPoll deedPoll, Referral referral, Map<String, Object> personalisation) {
    }
}
