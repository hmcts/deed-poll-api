package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.IncompleteDataError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
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
 * it would succeed unchanged once a Master has refused the application. 422 when the case has no applicant email
 * address, which is a fact about the record that no retry will change (decision 7, and
 * {@link IncompleteDataError} has the argument). 400 for a slot payload the schema refuses, with every offending
 * field named at once. The recipient is checked <em>before</em> the payload, deliberately: a caseworker whose
 * case has no address cannot fix that by editing what they typed, so making them correct a payload first would
 * be two refusals where one was useful.
 *
 * <p><strong>Nothing here sends.</strong> A send resolves, enqueues in the caller's transaction, and returns;
 * the drain is what talks to Notify, and it is called after this transaction commits -- see
 * {@link NotificationOutbox}, and {@code CaseworkerEmailController} for why the order is not an accident.
 */
@Service
public class CaseEmailService {

    private static final Logger LOG = LoggerFactory.getLogger(CaseEmailService.class);

    /**
     * Why <em>Application Rejected</em> does not apply to a case, in words a caseworker can act on.
     *
     * <p>The condition is a referral of this case whose {@code decision} is {@link MasterDecision#Rejected}:
     * this email quotes a judge refusing enrolment, so without one there is nothing to quote and nobody has
     * decided anything to tell the applicant about.
     */
    static final String NO_REFUSAL = "This case has no referral that a Master has refused, so there is no "
        + "refusal to tell the applicant about";

    private final DeedPollRepository deedPolls;
    private final ReferralRepository referrals;
    private final NotificationRepository notifications;
    private final DeedPollSupportingDocumentRepository documents;
    private final NotificationClient notify;
    private final NotifyProperties properties;

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
                           ObjectMapper json) {

        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notifications = notifications;
        this.documents = documents;
        this.notify = notify;
        this.properties = properties;
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
            LOG.warn("Notify would not render {} for case {} ({}, status {})",
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
     * <p><strong>A second send for the same referral is a no-op that answers with the first row.</strong> The
     * insert is {@code ON CONFLICT DO NOTHING} on the partial unique index, so a double-click or two replicas
     * racing cannot tell an applicant twice that their application was refused -- and the caseworker is not shown
     * a failure for a request that was merely redundant.
     *
     * <p>{@code templateVersion} is left null: this row was enqueued without asking Notify to render anything, so
     * there is no version to record and inventing one would claim knowledge of the wording. The sender records
     * the version Notify reports when it accepts the send.
     *
     * @param caller the caseworker sending it, who becomes {@code createdBy} -- not audit noise here, because
     *     they authored part of what the applicant reads
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case is in no state to send this email
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

        int enqueued = notifications.enqueue(
            id,
            deedPollId,
            referralId,
            template.name(),
            recipient,
            asJson(composed.personalisation()),
            AuditClock.now(),
            caller.name());

        if (enqueued == 1) {
            return SendReceipt.enqueued(id, recipient);
        }

        // Zero rows: the unique index refused it, which is the mechanism working rather than a failure. Logged
        // because a caseworker pressing send twice and a replica racing look identical from here, and the
        // frequency is worth knowing.
        LOG.info("Case {} already owes {} for referral {}, so nothing was enqueued",
                 deedPollId, template.key(), referralId);
        return notifications.findByDeedPollIdAndReferralIdAndTemplate(deedPollId, referralId, template.name())
            .map(SendReceipt::alreadyOwed)
            // Unreachable in practice: the insert was refused, so the row it collided with exists. A conflict
            // with a row that then could not be read would mean the collision was with a row of another case,
            // which the partial index cannot produce -- so this says so rather than answering with a null id.
            .orElseThrow(() -> new IllegalStateException(
                "Enqueueing " + template.key() + " for referral " + referralId + " of case " + deedPollId
                    + " was refused by the unique index, but no such row is readable"));
    }

    /**
     * One template's answer for one case.
     *
     * <p>{@code provisioned} is asked of configuration rather than of the case, so it is the same answer for
     * every case in this environment -- and it is on each row rather than on the response as a whole because a
     * later template may be provisioned while another is not.
     */
    private EmailAvailability availability(EmailTemplate template, String deedPollId) {
        boolean provisioned = properties.isProvisioned(template.key());
        return cause(template, deedPollId).isPresent()
            ? EmailAvailability.applicable(template, provisioned)
            : EmailAvailability.unavailable(template, reasonNotApplicable(template), provisioned);
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
     */
    private Optional<Referral> cause(EmailTemplate template, String deedPollId) {
        return switch (template) {
            case APPLICATION_REJECTED -> referrals.findByDeedPollIdOrderByCreatedOnDesc(deedPollId).stream()
                .filter(referral -> referral.getDecision() == MasterDecision.Rejected)
                .findFirst();
        };
    }

    /** The sentence that says why a template does not apply, which is per template for the reason its text is. */
    private String reasonNotApplicable(EmailTemplate template) {
        return switch (template) {
            case APPLICATION_REJECTED -> NO_REFUSAL;
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
        Referral referral = cause(template, deedPollId)
            .orElseThrow(() -> new ConflictError(reasonNotApplicable(template)));

        // The referral reaches the email only through this slot, pre-populated and editable. resolve() below is
        // deliberately not given the referral: a second, un-edited copy of the judge's words in the same email
        // would make "what was this applicant told" a question with two answers.
        Map<EmailSlot, String> accepted = EmailSlots.parse(template, slots, referral.getDirectionsText());
        Map<String, Object> personalisation = switch (template) {
            case APPLICATION_REJECTED ->
                ApplicationRejectedPersonalisation.resolve(deedPoll, accepted).toMap();
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
