package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedPreparations;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.PreparedDeed;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentUpload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.UploadedFile;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;
import uk.gov.hmcts.reform.deedpoll.storage.CdamDocumentUploads;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.IncompleteDataError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.UpstreamFailureError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedDocumentService;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedRouting;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralReason;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollSupportingDocumentRepository;
import uk.gov.hmcts.reform.deedpoll.repository.NotificationRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * <p><strong>Two of the three templates are entitled by a referral and one by the case's own state</strong>, and
 * {@link EmailCause} is what says which. ⑤ is offered when {@code DeedPoll.state} is
 * {@link DeedPollState#AwaitingFurtherDocs} -- the cascade's own answer to what the case is waiting for -- so
 * composing it reads no referral at all and its outbox row stores a null {@code referralId}. Nothing about a
 * chase is derived from referrals, correspondence history or document rows: those would be a second derivation
 * to drift from the state the case already publishes.
 *
 * <p><strong>The four answers a send can give, and why each is the status it is.</strong> 404 for a case that
 * does not exist. 409 when the case is in no state to send this email -- nothing about the request is wrong and
 * it would succeed unchanged once a Master has refused the application, or it would have succeeded had the
 * email that contradicts it not already been asked for. 422 when the case has no applicant email
 * address, which is a fact about the record that no retry will change (decision 7, and
 * {@link IncompleteDataError} has the argument). 400 for a slot payload the schema refuses, with every offending
 * field named at once. The recipient is checked <em>before</em> the payload, deliberately: a caseworker whose
 * case has no address cannot fix that by editing what they typed, so making them correct a payload first would
 * be two refusals where one was useful. ⑤ is what makes that order matter rather than theoretical --
 * {@code documents_required} is required of the request and pre-populated from nothing, so an empty payload on a
 * case with no address really would be refused twice. A <em>preview</em> checks the address not at all: reading
 * what would go out before anybody has chased the applicant for an address is legitimate.
 *
 * <p><strong>Nothing here sends.</strong> A send resolves, enqueues in the caller's transaction -- stamping the
 * referral's {@code confirmationRequestedAt} in that same transaction where the email is the one that asks the
 * applicant to reply, and writing one case note so the email appears on the only timeline this domain has, which
 * are the two things a send writes outside the outbox -- and returns;
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
     * <p>The first of that email's three conditions, and its own sentence rather than half of a combined one:
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

    /**
     * Why <em>Request confirmation to proceed</em> does not apply to a case whose applicant has already answered.
     *
     * <p>The third condition, and the one that is about the exchange rather than about the refusal: once an
     * outcome is recorded there is nothing left to ask, and asking anyway would put the case in a state nothing
     * could correct. {@link #awaitingAnAnswer} has that argument.
     *
     * <p>It says the answer is recorded without saying what it was. A caseworker who needs that reads the
     * referrals tab or the case's timeline, both of which name it; repeating it in an availability response would
     * publish the applicant's decision on a screen that is asking a different question.
     */
    static final String CONFIRMATION_ALREADY_ANSWERED = "The applicant has already answered the confirmation for "
        + "this case's most recent refusal, so there is nothing left to ask them: see the referral for what they "
        + "said";

    /**
     * Why <em>Chase missing documents</em> does not apply to a case that is not waiting for any.
     *
     * <p>The condition is the case's own derived state -- {@link DeedPollState#AwaitingFurtherDocs} -- and this
     * sentence names the field that produces it, because that is the one thing a caseworker can act on: the
     * state is derived by the cascade from {@code awaitingFurtherDocsInfo} and is not settable directly, so
     * "put the case in that state" is a date on the case details rather than a button on the Email tab.
     *
     * <p><strong>It says the date has to be the case's latest, not merely that it has to be recorded.</strong>
     * {@code awaitingFurtherDocsInfo} is the last date {@code CaseState.deriveState} consults, so every later
     * one -- ready for the Master, referred, signature requested or received, enrolled, returned -- outranks it:
     * a case can carry a date awaited and still be {@code ReadyForMaster}. A sentence that only said "record the
     * date" would then tell a caseworker to do something they had already done, and they would record it twice
     * before finding out that was never the obstacle.
     *
     * <p>It does not quote the state the case is actually in. The Email tab shows that on the same screen, and a
     * reason that repeated it would be a second copy going stale in whichever of the two was refreshed less.
     */
    static final String NOT_AWAITING_FURTHER_DOCS = "This case is not waiting for further documents or "
        + "information, so there is nothing to chase the applicant for: the case waits for them only while the "
        + "date awaited is the latest date recorded on it, so record that date -- and clear any later date that "
        + "has moved the case past it -- before sending this email";

    /**
     * Why neither <em>Forms for completion</em> email applies to a case whose type nobody has established.
     *
     * <p>{@link uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType#Unknown} and an unset type alike -- they
     * are the same position, which is that this service cannot tell which deed the applicant has to swear.
     *
     * <p><strong>Offering nothing is a different judgement from the wording default this service already
     * makes</strong>, and deliberately so. {@code ResolvedValues.isMinor} gives an unknown type the adult
     * <em>wording</em>, on the ground that it is merely impersonal if wrong. Forms are not a sentence: the wrong
     * deed means an applicant completes, witnesses and swears the wrong legal instrument, and there is no
     * recovering from that by rereading the email. So there is no safe default and the honest answer is to offer
     * none and say why.
     */
    static final String TYPE_NOT_ESTABLISHED = "This case's type has not been established, so we cannot tell "
        + "which deed the applicant has to sign: set the case type before sending either forms email, because "
        + "sending the wrong deed has an applicant swear the wrong legal instrument";

    /**
     * Why neither forms email applies to a deed of relinquishment.
     *
     * <p>Its own sentence rather than {@link #TYPE_NOT_ESTABLISHED}'s, because a caseworker does something
     * different about it: the type <em>is</em> established, and what is missing is the document set. A
     * relinquishment follows the same journey as a change of name -- a caseworker sends a forms email with links
     * to the relevant documents -- but a relinquishment is a different instrument, so LOC020 is very unlikely to
     * be right and nobody has said what is. That is {@code notify-4a} flag 5, and until it is answered this case
     * has no forms email at all rather than one of these two.
     */
    static final String RELINQUISHMENT_HAS_ITS_OWN_FORMS = "This case is a deed of relinquishment, which needs "
        + "its own forms and its own email: nobody has yet identified which documents it carries, so neither "
        + "change of name forms email applies to it";

    /**
     * Why the applicant has already been asked to sign, keyed on the column that says so.
     *
     * <p>Decision 9: sending one of these emails <em>is</em> the request for a signature, and
     * {@code AwaitingSignature} is derived from {@code signatureRequestedDate} -- so the email is offered while
     * that date is null and withheld once it is set. The sentence names the column for the reason
     * {@link #NOT_AWAITING_FURTHER_DOCS} does: the state is derived and not settable, so what a caseworker acts on
     * is the date on the case details.
     *
     * <p><strong>It says "clear the date" rather than offering to send anyway</strong>, because the deed is the
     * thing being sent. An applicant who already has one and is sent another has two documents that look like the
     * deed the court will seal, and only one of them is.
     */
    static final String SIGNATURE_ALREADY_REQUESTED = "This case already has a signature requested date "
        + "recorded, so the applicant has already been sent their deed to sign: clear that date on the case "
        + "details if the forms genuinely have to go out again";

    /**
     * Why a send is refused when its {@code Idempotency-Key} has already been used on this case for something
     * else.
     *
     * <p>The one answer a request id can give that is neither success nor a business conflict: the key says "this
     * is the send I already made" and the payload says it is a different email. Both cannot be true, and guessing
     * either way is worse than refusing -- taking the key's word would silently discard the caseworker's edits
     * and answer with an email they did not write, and taking the payload's word would write the second row the
     * key exists to prevent.
     *
     * <p>409 rather than 400, because nothing in the payload is wrong: the same body under a request of its own
     * would be accepted. So the sentence says what to do about it -- start the email again -- rather than naming
     * a field to correct. It deliberately quotes neither the key nor either email's content: the key is transport
     * and the content is the applicant's.
     */
    static final String REQUEST_ALREADY_USED = "This send was already recorded for this case as a different "
        + "email, so nothing was sent: start composing the email again, which gives it a request of its own";

    private final DeedPollRepository deedPolls;
    private final ReferralRepository referrals;
    private final NotificationRepository notifications;
    private final DeedPollSupportingDocumentRepository documents;
    private final NotificationClient notify;
    private final NotifyProperties properties;

    /**
     * Only to put a marker on the case's timeline when an email is owed -- see {@link #send} and
     * {@link EmailNotes}.
     *
     * <p>The service and not the note repository, so the note is written through the one path that scopes a note
     * to its parent case and refuses a case that does not exist. {@code createNote} takes a {@code NoteAuthor}
     * rather than a {@link CurrentUser}, which is what lets the drain author the failure note too.
     */
    private final NoteService notes;

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
     * Only for the {@code personalisation} column, which {@link NotificationRepository#enqueue} takes as JSON
     * text because its statement is native -- and, on the one path that compares a stored row with a resolved
     * one, to read that text back so both sides have been through the same deserialiser.
     */
    private final ObjectMapper json;

    /**
     * Only so that a forms email has a deed to carry -- see {@link #deedDocumentId}.
     *
     * <p>The service and not the generator, because what a send needs is the stored document's id and decision 6's
     * generate-once rule, both of which are {@code DeedDocumentService}'s. Calling the generator here would produce
     * fresh bytes on every retry of one journey and store none of them.
     */
    private final DeedDocumentService deeds;
    private final DeedPreparations preparations;
    private final Optional<CdamDocumentUploads> remote;
    private final TransactionTemplate transaction;

    public CaseEmailService(DeedPollRepository deedPolls,
                           ReferralRepository referrals,
                           NotificationRepository notifications,
                           DeedPollSupportingDocumentRepository documents,
                           NotificationClient notify,
                           NotifyProperties properties,
                           ReferralService referralService,
                           NoteService notes,
                           DeedDocumentService deeds,
                           ObjectMapper json,
                           PlatformTransactionManager transactions,
                           DeedPreparations preparations,
                           Optional<CdamDocumentUploads> remote) {

        this.deedPolls = deedPolls;
        this.referrals = referrals;
        this.notifications = notifications;
        this.documents = documents;
        this.notify = notify;
        this.properties = properties;
        this.referralService = referralService;
        this.notes = notes;
        this.deeds = deeds;
        this.json = json;
        this.transaction = new TransactionTemplate(transactions);
        this.preparations = preparations;
        this.remote = remote;
    }

    /**
     * Every template's answer for one case, from one read of the case and one of its referrals.
     *
     * <p>Every email this service knows about, applicable or not -- {@link EmailAvailability} says why. Reached
     * only through {@link #overview}, which is the one operation that publishes it: a second public read of the
     * same thing would be a second entry point for a rule to drift in, exercised by tests and by nothing else.
     *
     * <p>The refusal is read once for the whole answer rather than per template: both referral-driven templates
     * speak for the same newest refusal, so one read gives them one answer -- which is also what guarantees they
     * name the same {@code referralId} when both apply. The case-driven template does not consult it at all
     * (decision 1); that is a property of {@link #cause}, not of when the read happens.
     */
    private List<EmailAvailability> everyAvailability(DeedPoll deedPoll, Optional<Referral> newestRefusal) {
        return Arrays.stream(EmailTemplate.values())
            .map(template -> availability(template, deedPoll, newestRefusal))
            .toList();
    }

    /**
     * Everything the Email tab needs, resolved without asking Notify to render anything.
     *
     * <p>Defaults come from {@link EmailSlots#defaults}, which is the pre-population {@link EmailSlots#parse}
     * applies to an empty payload -- one rule, so a compose form opens with the values a preview would use
     * without spending a Notify API call to discover them.
     *
     * <p><strong>Not from {@link #compose}, which is the change ⑤ forced.</strong> A template whose required
     * slot nothing pre-populates refuses an empty payload -- correctly, at preview and at send -- so composing
     * defaults through the validator would answer 400 for the whole tab on exactly the cases the chase exists
     * for. Defaults are what a form starts with; requiredness is what a send is judged by.
     */
    @Transactional(readOnly = true)
    public CaseEmailOverview overview(String deedPollId) {
        DeedPoll deedPoll = requireCase(deedPollId);
        Optional<Referral> newestRefusal = newestRefusal(deedPollId);
        List<EmailAvailability> availability = everyAvailability(deedPoll, newestRefusal);
        List<EmailSlotDefaults> defaults = availability.stream()
            .filter(EmailAvailability::applicable)
            .map(entry -> slotDefaults(entry.template(), deedPoll, newestRefusal))
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

    /**
     * One applicable template's starting values, keyed by placeholder.
     *
     * <p>The direction comes from this template's own cause, so a template that is not about a referral is given
     * none rather than one belonging to a refusal it has nothing to do with. The cause is present by
     * construction -- only an applicable template reaches here -- and an absent one would leave every slot empty,
     * which is what a form would have shown anyway.
     */
    private EmailSlotDefaults slotDefaults(
        EmailTemplate template, DeedPoll deedPoll, Optional<Referral> newestRefusal) {

        String directionsText = cause(template, deedPoll, newestRefusal)
            .map(EmailCause::directionsText)
            .orElse(null);
        return new EmailSlotDefaults(template, EmailSlots.defaults(template, directionsText));
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
     * <p><strong>A template with a file attached in the Notify portal is rendered on a second call.</strong> Notify
     * refuses to preview it without a value for the file's placeholder, which a real send fills in itself, so a
     * refusal naming only such files is retried once with {@link AttachmentStandIns#STAND_IN} under each. A
     * template without one makes the single call it always did. {@link AttachmentStandIns} has the argument, and
     * why a key this service owns is never stood in for.
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
            Optional<Map<String, Object>> withStandIns =
                AttachmentStandIns.retryWith(template, composed.personalisation(), refused, json);
            if (withStandIns.isEmpty()) {
                throw notRendered(deedPollId, template, refused);
            }
            try {
                return EmailPreview.from(notify.generateTemplatePreview(templateId, withStandIns.get()));
            } catch (NotificationClientException refusedAgain) {
                throw notRendered(deedPollId, template, refusedAgain);
            }
        }
    }

    /**
     * A preview Notify would not render, as the 502 the caseworker is shown.
     */
    private static UpstreamFailureError notRendered(
        String deedPollId, EmailTemplate template, NotificationClientException refused) {

        NotificationFailure verdict = NotificationFailure.classify(refused);
        // Logged in full here and summarised in the answer: the client's message contains Notify's response,
        // and this body is rendered by whatever is on the other end.
        log.warn("Notify would not render {} for case {} ({}, status {})",
                 template.key(), deedPollId, verdict, refused.getHttpResult(), refused);
        return new UpstreamFailureError("GOV.UK Notify could not render the " + template.key()
            + " template (" + verdict + ", status " + refused.getHttpResult() + ")");
    }

    /**
     * Owes the applicant this email: resolves it, freezes it into an outbox row, and returns the row.
     *
     * <p><strong>The recipient and the personalisation are frozen here rather than recomputed later.</strong> A
     * later edit to the case cannot redirect an email already owed, and "what was this applicant told" stays
     * answerable from the row even though part of the content is text the caseworker wrote and may have edited.
     *
     * <p><strong>A second send of the same request is a no-op that answers with the first row.</strong> The
     * insert is {@code ON CONFLICT DO NOTHING}, so a double-click or two replicas racing cannot send one applicant
     * two copies of one email -- and the caseworker is not shown a failure for a request that was merely
     * redundant.
     *
     * <p><strong>What "the same" means is the caller's own {@code requestId}, and that is the whole of decision
     * 6.</strong> Neither payload equality nor a time window can express intent: the same text is a legitimate
     * reminder next week, and two clicks milliseconds apart carry different timestamps. So one compose journey
     * creates one UUID and reuses it for every retry of its final POST, and a later journey gets another -- which
     * is what lets a case be chased twice on purpose while a double-click still writes one row. Scoped to the
     * case by V19's index, so a caller cannot make one case's chosen UUID collide with another case's row.
     *
     * <p><strong>A send of the email that contradicts one already asked for is a 409 naming it.</strong> That key
     * is the template's exclusion group, scoped to the referral for the two outcome emails and -- since V20 -- to
     * the <em>case</em> for the two forms emails, which have no referral to be keyed on. Either way the second one
     * is refused by the same {@code DO NOTHING} clause, which is what keeps the caseworker's transaction usable
     * instead of handing them a {@code DataIntegrityViolationException}.
     *
     * <p><strong>Which of the four things happened is read off the row that refused it, in a defined
     * order</strong> -- see {@link #resolveRefusedInsert}. Zero rows means one of three unique indexes refused the
     * insert, and the answers are a 200, two different 409s and a fault, so it cannot be guessed.
     *
     * <p><strong>The request id is consulted only where the insert reached the database, and that bounds what it
     * can promise.</strong> Everything before the statement -- the case's entitlement to this email, an applicant
     * address to send it to, the slots being acceptable -- is judged afresh on every attempt, so a retry that
     * arrives after the case has moved on is refused on that rather than answered with the receipt it is retrying:
     * an applicant whose documents arrived is no longer {@link DeedPollState#AwaitingFurtherDocs}, and a chase
     * already sent under this key then earns {@link #NOT_AWAITING_FURTHER_DOCS}. That is the deliberate direction
     * to fail in -- nothing is sent twice, and a caseworker reading the Emails tab sees the send that happened --
     * but it means the idempotent answer holds for a retry of an unchanged case rather than for all time. Moving
     * the lookup in front of the composition would buy the stronger promise at the cost of a read on every send
     * and a second place where "what does this key mean" is decided; decision 7 chose this one.
     *
     * <p>{@code templateVersion} is left null: this row was enqueued without asking Notify to render anything, so
     * there is no version to record and inventing one would claim knowledge of the wording. The sender records
     * the version Notify reports when it accepts the send.
     *
     * <p><strong>Sending ④ also starts the clock on the reply it asks for</strong>, in this transaction and with
     * the row's own instant -- see {@link #startConfirmationClock}.
     *
     * <p><strong>Every fresh enqueue also writes one case note</strong>, in this transaction and as the
     * caseworker -- see {@link #note}. Those two are the whole of what a send does to anything other than the
     * outbox, and doing them here is why they commit or roll back with the row.
     *
     * @param requestId the caller's own identifier for this send -- the {@code Idempotency-Key} header. One
     *     compose journey's UUID, reused for every retry of that POST and never for a later journey
     * @param caller the caseworker sending it, who becomes {@code createdBy} -- not audit noise here, because
     *     they authored part of what the applicant reads
     * @throws NotFoundError if there is no such case
     * @throws ConflictError if the case is in no state to send this email, this request id has already been used
     *     on this case for a different email, the email that contradicts this one has already been asked for on
     *     the same referral or on the same case, the case already holds the other deed, or the applicant answered
     *     ④ while it was being sent
     * @throws IncompleteDataError if the case has no applicant email address
     * @throws uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError if the slots are not acceptable
     */
    public SendReceipt send(String deedPollId,
                           EmailTemplate template,
                           Map<String, Object> slots,
                           UUID requestId,
                           CurrentUser caller) {

        if (remote.isEmpty() || FormsForCompletion.deedOf(template).isEmpty()) {
            return transaction.execute(status -> sendLocal(deedPollId, template, slots, requestId, caller));
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("CDAM forms correspondence must not start inside a transaction");
        }
        FormsSnapshot before = transaction.execute(status -> inspectForms(deedPollId, template, slots, requestId));
        if (before.receipt() != null) {
            return before.receipt();
        }
        PreparedDeed prepared = preparations.prepare(deedPollId, requireDeed(template));
        // Attached preparations need no payload (Task 4 releases it after verified read-back).
        boolean attached = transaction.execute(status -> documents.findByIdAndDeedPollId(
            prepared.documentId(), deedPollId).orElseThrow().getStorageState()
            == uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentStorageState.Attached);
        if (!attached) {
            byte[] bytes = preparations.content(deedPollId, prepared.documentId());
            remote.orElseThrow().continuePrepared(deedPollId, prepared.documentId(), new DocumentUpload(
                deedPollId, null, new UploadedFile(bytes, prepared.originalName(), prepared.mimeType(), bytes.length),
                prepared.originalName(), DocumentDirection.Outgoing));
        }
        return transaction.execute(status -> {
            deedPolls.lockForDeedPreparation(deedPollId).orElseThrow(() -> new NotFoundError("DeedPoll not found"));
            FormsSnapshot after = inspectForms(deedPollId, template, slots, requestId);
            if (after.receipt() != null) {
                return after.receipt();
            }
            if (!before.equals(after)) {
                throw new ConflictError("This case changed while its deed was being prepared. "
                    + "Review the case before sending this email.");
            }
            preparations.requireCurrent(deedPollId, prepared);
            Entitled entitled = entitle(deedPollId, template);
            Composed composed = compose(entitled, template, slots, deed -> prepared.documentId());
            return enqueue(deedPollId, template, requestId, caller, after.recipient(), composed);
        });
    }

    /** Only immutable values cross the remote boundary; matching requests resolve before any storage work. */
    private FormsSnapshot inspectForms(String caseId, EmailTemplate template, Map<String, Object> slots, UUID key) {
        Entitled entitled = entitle(caseId, template);
        String recipient = requireRecipient(entitled.deedPoll());
        final Composed preview = compose(entitled, template, slots, deed -> "prepared-deed");
        Optional<Notification> same = notifications.findByDeedPollIdAndRequestId(caseId, key);
        if (same.isPresent()) {
            Notification row = same.get();
            String documentKey = template.documentKeys().iterator().next();
            Composed actual = compose(entitled, template, slots,
                deed -> Objects.toString(row.getPersonalisation().get(documentKey), ""));
            if (!isTheSameSend(row, template, actual.cause().referralId(), recipient,
                asJson(actual.personalisation()))) {
                throw new ConflictError(REQUEST_ALREADY_USED);
            }
            return new FormsSnapshot(null, null, null, null, SendReceipt.alreadyOwed(row));
        }
        Optional<Notification> occupant = occupantOfTheGroupSlot(caseId, null, template);
        if (occupant.isPresent()) {
            if (!template.name().equals(occupant.get().getTemplate())) {
                throw new ConflictError(alreadyRequested(null, occupant.get()));
            }
            return new FormsSnapshot(null, null, null, null, SendReceipt.alreadyOwed(occupant.get()));
        }
        if (!properties.isProvisioned(template)) {
            throw new UpstreamFailureError("This email template is not configured for sending");
        }
        return new FormsSnapshot(recipient, asMap(asJson(preview.personalisation())),
            DeedDocumentService.inputFingerprint(entitled.deedPoll(), requireDeed(template)),
            entitled.deedPoll().getCcdCaseReference(), null);
    }

    private record FormsSnapshot(String recipient, Map<String, Object> personalisation, String fingerprint,
                                 String caseReference, SendReceipt receipt) {
        @Override
        public String toString() {
            return "FormsSnapshot[redacted]";
        }
    }

    private SendReceipt sendLocal(String deedPollId, EmailTemplate template, Map<String, Object> slots,
                                  UUID requestId, CurrentUser caller) {

        // Entitlement, then the address, then the payload -- the order the four answers are documented in, and the
        // reason this is two calls rather than one: a case with no address is told so before a caseworker is asked
        // to correct what they typed.
        Entitled entitled = entitle(deedPollId, template);
        String recipient = requireRecipient(entitled.deedPoll());
        // Composing a forms email generates and stores the case's deed, in this transaction -- see
        // deedDocumentId. Nothing happens here for a template that carries no document.
        Composed composed = compose(entitled, template, slots, deed -> deedDocumentId(deedPollId, template, deed));
        return enqueue(deedPollId, template, requestId, caller, recipient, composed);
    }

    private SendReceipt enqueue(String deedPollId, EmailTemplate template, UUID requestId,
                                CurrentUser caller, String recipient, Composed composed) {
        // Null for a case-driven email, which is the column's own null rather than a stand-in: such a row sits
        // outside the partial unique index, so nothing about it is keyed on a referral it does not have.
        UUID referralId = composed.cause().referralId();
        UUID id = UUID.randomUUID();
        // One instant for the row and for the clock the next paragraph starts, read once: two calls could
        // straddle a tick and make the outbox row and the referral disagree about when the applicant was asked.
        LocalDateTime now = AuditClock.now();
        String personalisation = asJson(composed.personalisation());

        int enqueued = notifications.enqueue(
            id,
            deedPollId,
            requestId,
            referralId,
            template.name(),
            template.exclusionGroup().name(),
            recipient,
            personalisation,
            now,
            caller.name());

        if (enqueued == 1) {
            startConfirmationClock(template, deedPollId, referralId, now, caller);
            note(deedPollId, template, caller);
            return SendReceipt.enqueued(id, recipient);
        }

        return resolveRefusedInsert(deedPollId, template, requestId, referralId, recipient, personalisation);
    }

    /**
     * What a zero-row {@link #enqueue} meant, read off the row that refused it rather than guessed.
     *
     * <p>Zero rows is the mechanism working rather than a failure -- but since V20 there are three unique indexes
     * that can produce it, and the answers are a 200, a 409 and a fault. {@code ON CONFLICT DO NOTHING} names no
     * target and reports no reason, so the reason is established by reading, in this order:
     *
     * <ol>
     *   <li><strong>This case's row with this request id.</strong> The most specific answer, and asked first for
     *       that reason: it names the very send the caller is retrying. A caller retrying its own POST must not be
     *       told that some other email of the same family is in the way.</li>
     *   <li><strong>The row occupying the referral's exclusion group.</strong> V15's business constraint, whose
     *       two meanings {@link #alreadyRequested(Notification)} and {@link SendReceipt#alreadyOwed} split between
     *       them exactly as they did before V19 -- only reached now for a request id that has never been seen.</li>
     *   <li><strong>The row occupying the case's exclusion group.</strong> V20's, for a case-driven email whose
     *       group is {@linkplain EmailExclusionGroup#exclusivePerCase() exclusive per case} -- which is the forms
     *       pair and not the chase. Asked only where the referral is null, because the two indexes are partial on
     *       opposite sides of that column and at most one of them can have refused the row.</li>
     *   <li><strong>None of them.</strong> A database constraint refused a row this service cannot account for,
     *       which is a fault and says so.</li>
     * </ol>
     *
     * <p><strong>A matching row is an idempotent success and a differing one is a 409.</strong> The key asserts
     * "this is the send I already made", so the content has to bear that out: same template, same recipient, same
     * referral, same frozen personalisation. If it does not, one key has been reused for two different emails and
     * neither reading is safe -- see {@link #REQUEST_ALREADY_USED}.
     */
    private SendReceipt resolveRefusedInsert(String deedPollId,
                                             EmailTemplate template,
                                             UUID requestId,
                                             UUID referralId,
                                             String recipient,
                                             String personalisation) {

        Optional<Notification> sameRequest = notifications.findByDeedPollIdAndRequestId(deedPollId, requestId);
        if (sameRequest.isPresent()) {
            Notification existing = sameRequest.get();
            if (!isTheSameSend(existing, template, referralId, recipient, personalisation)) {
                // One compose journey's UUID used for a second, different email. Logged without either email's
                // content and without the key: what is worth knowing is that a client is reusing keys.
                log.info("Case {} already recorded {} under this request, so {} was refused",
                         deedPollId, existing.getTemplate(), template.key());
                throw new ConflictError(REQUEST_ALREADY_USED);
            }
            // A caseworker pressing send twice, a client retrying a timed-out POST, or two replicas racing on one
            // journey. Logged because those look identical from here and the frequency is worth knowing.
            log.info("Case {} has already sent {} under this request, so nothing was enqueued",
                     deedPollId, template.key());
            return SendReceipt.alreadyOwed(existing);
        }

        // A request id never seen on this case, so what refused the insert was one of the two exclusion-group
        // keys. Which of them, and whether either can have, is the one question occupantOfTheGroupSlot answers --
        // here and for the availability check, so the scope rule is stated in Java exactly once.
        String group = template.exclusionGroup().name();
        Notification occupant = occupantOfTheGroupSlot(deedPollId, referralId, template)
            // Unreachable in practice, and named rather than answered with a null id: the insert was refused, so
            // some row collided with it, and the three indexes are the only ones that can. Reaching here means one
            // of them refused a row none of these reads can find -- a schema this service does not understand, not
            // an outcome a caseworker can act on. A fourth unique index added without teaching this method is what
            // turns an ordinary conflict into a 500, and nothing fails the build to say so. Raising rolls the
            // transaction back, which is the safe direction: nothing is owed and nothing was sent.
            .orElseThrow(() -> new IllegalStateException(
                "Enqueueing " + template.key() + " for case " + deedPollId + " was refused by a unique index, "
                    + "but neither this case's request row nor a blocking row for its " + group
                    + " group (referral " + referralId + ") is readable"));

        if (!template.name().equals(occupant.getTemplate())) {
            // The other email of the group. A 409 rather than a duplicate, because sending this one would either
            // tell the applicant the opposite of what they have already been told or send them a second deed to
            // swear -- and because nothing the caseworker typed is wrong, so there is no field to correct.
            log.info("Case {} already owes {} for its {} group (referral {}), so {} was refused",
                     deedPollId, occupant.getTemplate(), group, referralId, template.key());
            throw new ConflictError(alreadyRequested(referralId, occupant));
        }

        // The same email on the same referral, or the same case, from a different compose journey. Still not a
        // second email -- the group gets one -- so the receipt names the row that is already owed.
        log.info("Case {} already owes {} for its {} group (referral {}), so nothing was enqueued",
                 deedPollId, template.key(), group, referralId);
        return SendReceipt.alreadyOwed(occupant);
    }

    /**
     * Whether the row already recorded under this request id is the send the caller is retrying.
     *
     * <p>Template, referral, recipient and frozen personalisation -- the whole of what the row was written with,
     * minus the audit columns and the state the drain has since moved. Anything else differing is either derived
     * from these or is not the caller's to have changed.
     *
     * <p><strong>The referral is part of it even though no personalisation names it.</strong> A refusal's
     * personalisation carries the direction and the applicant's former names and no referral id, so two refusals
     * of one case recorded with the same direction resolve to identical maps. Without this comparison a key
     * reused across those two journeys would answer with the first referral's row -- telling the caseworker the
     * email had been sent while the new refusal had had none, and leaving the exclusion group it keys on empty.
     * Comparing it makes that the 409 it is: the same key for a different email.
     *
     * <p><strong>The personalisation is compared through the JSON both sides would be stored as.</strong> The
     * stored map came back out of {@code jsonb} and the resolved one is still Java, so a direct comparison would
     * be asserting about Jackson's and pgjdbc's type choices rather than about the content: the round trip is what
     * makes {@code true} and {@code "true"}, or an {@code Integer} and a {@code Long}, comparable at all. Key
     * order is deliberately not part of it -- {@code jsonb} does not preserve it -- so this is a map comparison
     * over normalised values and not a text one.
     */
    private boolean isTheSameSend(Notification existing,
                                  EmailTemplate template,
                                  UUID referralId,
                                  String recipient,
                                  String personalisation) {

        return template.name().equals(existing.getTemplate())
            && Objects.equals(referralId, existing.getReferralId())
            && recipient.equals(existing.getRecipient())
            && asMap(personalisation).equals(existing.getPersonalisation());
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
     * <p><strong>The re-stamp is conditional on there being no answer yet, and the condition is in the statement
     * rather than here.</strong> {@link #awaitingAnAnswer} already takes ④ off both the availability list and the
     * send path once the applicant has answered, but that check and this write are two steps of one transaction:
     * on a replacement send after a terminal failure -- the only path that reaches a second enqueue -- an answer
     * recorded in between would be overwritten back to NULL, erasing the applicant's reply and restarting a wait
     * they have already ended. {@link ReferralRepository#startConfirmationClockByIdAndDeedPollId} refuses that by
     * its own {@code WHERE} clause and answers 0.
     *
     * <p><strong>Answering 0 refuses the send, and in the same words the check that let it through would have
     * used.</strong> Not a stamp quietly skipped: the applicant has answered, so {@link #awaitingAnAnswer}'s rule
     * is that they must not be asked again, and the only difference between this caller and one who clicked a
     * second later is which side of the answer they landed on -- so they get the same
     * {@link #CONFIRMATION_ALREADY_ANSWERED} 409. Raising it rolls the outbox row back with everything else, which
     * is the point: nothing has been sent yet, the drain reads committed rows only, and letting the row stand would
     * have an applicant who has already replied receive a fresh request to reply. Zero rows cannot mean anything
     * else here -- the case is in the statement's {@code WHERE} and is the case the outbox row was just written
     * for, and that row's own foreign key holds the referral in place -- so there is no second reading of it to
     * distinguish.
     *
     * <p><strong>A statement over the three columns, never {@code save} on the referral this send composed
     * from.</strong> {@code notifications.enqueue} clears the persistence context, so that referral is detached by
     * the time the clock starts and a {@code save} on it would be a {@code merge} -- Hibernate re-reading the row
     * and writing every column back from the pre-enqueue snapshot, discarding whatever another transaction
     * committed on that referral in the meantime. The statement takes the referral's id rather than the entity so
     * that the detached copy is not reachable from here at all.
     *
     * <p><strong>Two rows, one transaction:</strong> the referral's stamp, and the case's
     * {@code awaitingConfirmationSince} -- the read-model the caseworker waiting list filters, sorts and counts
     * days from. The second is recomputed from the referral table rather than set to {@code now}, because the
     * case's column is the earliest outstanding request on it; {@code ReferralService.refreshAwaitingConfirmation}
     * is the one implementation of that rule.
     *
     * <p><strong>The decision is a switch <em>expression</em> and not a switch statement over the same enum</strong>,
     * because only the expression is checked for exhaustiveness: a switch <em>statement</em> whose selector is an
     * enum and whose labels are all enum constants is not an "enhanced" switch (JLS 14.11.2), so javac lets it fall
     * through every arm in silence. A third template that asks the applicant for a reply would then start no clock
     * and never reach the waiting list -- the one failure this whole feature exists to prevent -- with nothing
     * failing the build. As an expression it does not compile until the new member says which it is.
     */
    private void startConfirmationClock(EmailTemplate template,
                                        String deedPollId,
                                        UUID referralId,
                                        LocalDateTime now,
                                        CurrentUser caller) {
        if (!startsConfirmationClock(template)) {
            return;
        }
        // Three columns, conditionally: the stamp, and the caseworker into both audit columns because the row did
        // change and moving modifiedOn while modifiedBy still named the judge who directed it would attribute this
        // to them. Scoped to the case the outbox row was written for, as every referral write here is.
        if (referrals.startConfirmationClockByIdAndDeedPollId(referralId, deedPollId, now, caller.name()) == 0) {
            // The answer arrived between this send being allowed and this statement running, so the applicant is
            // being asked something they have already answered. Refused rather than stamped-or-not: throwing takes
            // the outbox row with it, and nothing has left for Notify because the drain reads committed rows only.
            log.info("Referral {} of case {} was answered while {} was being sent, so the send was refused",
                     referralId, deedPollId, template.key());
            throw new ConflictError(CONFIRMATION_ALREADY_ANSWERED);
        }
        // And the case's read-model of the same fact, in this transaction, so the case reaches the
        // waiting list with the email it is waiting on. A recompute from the referral table rather than
        // a copy of `now`: the case's column is the EARLIEST outstanding request on it, which this
        // method cannot know and ConfirmationWait can.
        // The case id the outbox row was written with, not one navigated off the referral: the two are
        // the same case, and asking the row's own id keeps the read-model and the row talking about it.
        referralService.refreshAwaitingConfirmation(deedPollId);
    }

    /**
     * Puts this email on the case's timeline, as the caseworker who sent it.
     *
     * <p><strong>Only on a fresh enqueue, and inside the send's own transaction.</strong> So
     * {@code ReferralService}'s invariant transfers exactly: there is no email owed without its note, and no note
     * for a send that was refused or rolled back. An idempotent second send writes no outbox row and must write no
     * second note either -- the applicant is owed one email, so the timeline says it once, and a caseworker
     * double-clicking would otherwise leave a case reading as though the applicant had been written to twice.
     *
     * <p><strong>Here rather than in the drain, and the reason is a duplicate-email hazard rather than
     * taste.</strong> {@link NotificationOutbox#drain(int)} runs a whole batch inside one
     * {@code REQUIRES_NEW}, so a note write that failed there would roll back state changes for rows Notify had
     * already accepted and the next drain would send them again. This transaction holds one send, is the
     * caseworker's own, and the controller drains only after it commits.
     *
     * <p><strong>Last of the three writes, after the outbox row and after the clock.</strong> Not for safety --
     * all three commit or roll back together -- but because both statements before it are
     * {@code clearAutomatically}, and a note saved before one of them would depend on
     * {@code flushAutomatically} to reach the database rather than on the ordering being right.
     *
     * <p>{@code caller.noteAuthor()} rather than the service account: a person chose to send this, and on ③ and ④
     * they wrote part of what the applicant reads. What the note says is {@link EmailNotes}' decision, including
     * why it points at the Emails tab instead of repeating the content.
     */
    private void note(String deedPollId, EmailTemplate template, CurrentUser caller) {
        notes.createNote(deedPollId, new CreateNoteInput(EmailNotes.sent(template)), caller.noteAuthor());
    }

    /** Whether this email asks the applicant for a reply the case then waits on. */
    private static boolean startsConfirmationClock(EmailTemplate template) {
        return switch (template) {
            case REQUEST_CONFIRMATION_TO_PROCEED -> true;
            // Tells the applicant a decision and starts a refund. It asks them for nothing, so there is nothing
            // to wait for and no clock to start.
            case APPLICATION_REJECTED -> false;
            // Asks for documents, and does wait for an answer -- but not this clock. `confirmationRequestedAt`
            // and `awaitingConfirmationSince` are the record of one referral's confirmation exchange, which a
            // chase is not part of: it has no referral to stamp, and the case is already recorded as waiting by
            // `awaitingFurtherDocsInfo`, which is what put it in the state that offers this email at all.
            case CHASE_MISSING_DOCUMENTS -> false;
            // Asks the applicant to print, sign, swear and POST the deed back, which is a wait of weeks and
            // certainly not a reply to this email. `confirmationRequestedAt` and `awaitingConfirmationSince` are
            // the record of one referral's confirmation exchange, which these emails are no part of: they have no
            // referral to stamp, and the wait for a signed deed is what signatureRequestedDate and
            // signatureReceivedDate record on the case. Whether the send should set the first of those is flag 2,
            // a business question this service deliberately leaves alone.
            case FORMS_FOR_COMPLETION_ADULT, FORMS_FOR_COMPLETION_MINOR -> false;
        };
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
     *
     * <p>The first reason is the enforcement as well as the report, and one of its conditions is only enforceable
     * here: an applicant who has already answered ④ has no database key standing between them and a second copy
     * of it, because the index is keyed on the outbox row and the row that asked has long since been drained.
     * {@link #awaitingAnAnswer} is what refuses that, on this path and on the send's through {@link #cause}.
     */
    private EmailAvailability availability(
        EmailTemplate template, DeedPoll deedPoll, Optional<Referral> newestRefusal) {

        boolean provisioned = properties.isProvisioned(template);
        Optional<EmailCause> cause = cause(template, deedPoll, newestRefusal);
        if (cause.isEmpty()) {
            return EmailAvailability.unavailable(
                template, reasonNotApplicable(template, deedPoll, newestRefusal), provisioned);
        }
        UUID referralId = cause.get().referralId();
        return counterpart(deedPoll.getId(), referralId, template)
            .map(blocking -> EmailAvailability.unavailable(
                template, alreadyRequested(referralId, blocking), provisioned))
            .orElseGet(() -> EmailAvailability.applicable(template, provisioned));
    }

    /**
     * The row that has taken this referral's -- or this case's -- slot for some <em>other</em> email of this
     * template's exclusion group, or empty if nothing has.
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
     *
     * <p>Which slot that is, and whether there is one at all, is {@link #occupantOfTheGroupSlot}'s answer -- the
     * one place the scope rule is written, shared with the send's own resolution of a refused insert.
     */
    private Optional<Notification> counterpart(String deedPollId, UUID referralId, EmailTemplate template) {
        return occupantOfTheGroupSlot(deedPollId, referralId, template)
            .filter(occupant -> !template.name().equals(occupant.getTemplate()));
    }

    /**
     * The row holding this template's exclusion-group slot, whichever of the two keys the slot belongs to.
     *
     * <p><strong>The one statement of V20's scope rule, read by both callers.</strong> {@link #availability} asks
     * it to grey an option out in advance and {@link #resolveRefusedInsert} asks it to explain a refusal the
     * database has already made, and the two must agree: a send refused on a row availability said nothing about
     * is a caseworker composing an email that cannot be sent. Written twice is how they would come to disagree,
     * and there is no test that would catch it -- {@code EmailExclusionGroupTest} holds the flag against the
     * migration's text, not the call sites against each other.
     *
     * <p><strong>A case-driven email is asked about only where its group is exclusive per case.</strong> V15's
     * index is partial on {@code "referralId" IS NOT NULL}, so a chase's rows cannot occupy it and no row can
     * occupy it against a chase -- asking with a null referral would be a query whose answer is always empty, and
     * one that read as though a repeat chase were something the database had an opinion about. It is not: decision
     * 5 says a second chase is legitimate. The forms emails are the other shape: no referral, and one deed per
     * case, which is what V20's key enforces and what {@link EmailExclusionGroup#exclusivePerCase()} distinguishes.
     *
     * @param referralId the referral the email speaks for, or null for a case-driven one -- which is the column
     *     the two partial indexes are partial on opposite sides of, so it is also which key can have refused a row
     */
    private Optional<Notification> occupantOfTheGroupSlot(
        String deedPollId, UUID referralId, EmailTemplate template) {

        String group = template.exclusionGroup().name();
        if (referralId != null) {
            return notifications.findBlocking(deedPollId, referralId, group);
        }
        return template.exclusionGroup().exclusivePerCase()
            ? notifications.findBlockingForCase(deedPollId, group)
            : Optional.empty();
    }

    /**
     * The sentence that says another email of this group has already been asked for, chosen by what the group is
     * scoped to.
     *
     * <p>Two sentences rather than one with the noun substituted, because the two say different things about what
     * went wrong: a referral gets one outcome email because the two contradict each other about enrolment, and a
     * case gets one forms email because an applicant signs one deed. A caseworker reading either needs the reason,
     * not the shape.
     *
     * <p>Keyed on the nullable referral id rather than on an {@link EmailCause}, so that the send -- which holds
     * the id and not the cause by the time it is explaining a refusal -- says it through this method too rather
     * than repeating the choice inline.
     */
    private static String alreadyRequested(UUID referralId, Notification blocking) {
        return referralId != null
            ? alreadyRequested(blocking)
            : alreadyRequestedOnThisCase(blocking);
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
        return "The " + displayName(blocking) + " email was already requested for this referral on "
            + ResolvedValues.date(blocking.getCreatedOn())
            + ", and one referral gets one of these emails: the two would tell the applicant opposite things "
            + "about enrolment";
    }

    /**
     * The sentence that says another email of this group has already been asked for on this <em>case</em>, and
     * when.
     *
     * <p>V20's constraint in words. "Requested" rather than "delivered" for the reason
     * {@link #alreadyRequested(Notification)} gives, and the date is formatted the way an applicant sees one for
     * the same reason.
     */
    private static String alreadyRequestedOnThisCase(Notification blocking) {
        return "The " + displayName(blocking) + " email was already requested for this case on "
            + ResolvedValues.date(blocking.getCreatedOn())
            + ", and a case gets one of these emails: an applicant signs and swears one deed, and the two would "
            + "send them two different legal instruments to sign";
    }

    /**
     * What to call the email an occupying row was written for.
     *
     * <p>The stored member name turned back into a display name where it still names a template, and left as it
     * stands where it does not: a template renamed after rows were written must still produce a readable sentence
     * rather than nothing at all.
     */
    private static String displayName(Notification blocking) {
        return EmailTemplate.byName(blocking.getTemplate())
            .map(EmailTemplate::displayName)
            .orElse(blocking.getTemplate());
    }

    /**
     * What entitles this case to this email, or empty if nothing does.
     *
     * <p>A {@code switch} with no {@code default}, so a template added to the enum does not silently inherit
     * another one's rule: it fails to compile until somebody says what makes it applicable.
     *
     * <p><strong>An {@link EmailCause} rather than a referral, since ⑤.</strong> Two of the three templates are
     * entitled by a referral and the third by the case's own state, and the type says which -- so a case-driven
     * template is not handed a referral that happens to exist, and the outbox row it writes carries the null
     * {@code referralId} V11 designed the column for. See {@link EmailCause} for the alternatives and why each
     * is worse.
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
     * <p>The newest refusal is a parameter rather than read here, so one caller reads it once and then asks
     * which of its sentences to say when it entitles the case to nothing.
     *
     * <p><strong>⑤ reads the case's derived state and nothing else</strong> -- decision 1. Not its referrals, not
     * its correspondence history and not its document rows: {@code state} is already the cascade's answer to
     * "what is this case waiting for", computed from {@code awaitingFurtherDocsInfo}, and a second derivation
     * here would be a second answer to drift from it. The refusal passed in is deliberately unread on that arm.
     */
    private static Optional<EmailCause> cause(
        EmailTemplate template, DeedPoll deedPoll, Optional<Referral> newestRefusal) {

        return switch (template) {
            case APPLICATION_REJECTED -> newestRefusal.map(EmailCause::of);
            case REQUEST_CONFIRMATION_TO_PROCEED -> newestRefusal
                .filter(CaseEmailService::aboutNonPublication)
                .filter(CaseEmailService::awaitingAnAnswer)
                .map(EmailCause::of);
            case CHASE_MISSING_DOCUMENTS -> awaitingFurtherDocuments(deedPoll)
                ? Optional.of(EmailCause.ofCase())
                : Optional.empty();
            // Case-driven like ⑤, and on two conditions rather than one: the case's type has to take this deed,
            // and no signature may have been requested yet. Neither reads a referral -- no judge is involved in
            // sending an applicant their forms.
            case FORMS_FOR_COMPLETION_ADULT, FORMS_FOR_COMPLETION_MINOR -> formsApply(template, deedPoll)
                ? Optional.of(EmailCause.ofCase())
                : Optional.empty();
        };
    }

    /**
     * Whether this case is entitled to <em>this</em> forms email, which is two questions.
     *
     * <p><strong>The type has to route to this very template, not merely to one of the two.</strong> That is the
     * whole of decision 3 as behaviour: an {@code AdultTransgender} case is offered ① and must never be offered
     * ②, because an applicant sent the wrong deed swears the wrong legal instrument. The routing is
     * {@link FormsForCompletion#emailFor}, which derives it from the document-type rows rather than restating
     * which type takes which deed.
     *
     * <p><strong>And no signature may have been requested</strong> -- decision 9. {@code AwaitingSignature} is
     * derived from {@code signatureRequestedDate}, so the email is offered while that column is null; sending it
     * <em>is</em> the request for a signature, which is why it is the right column to read and why this service
     * deliberately does not write it (flag 2 is a business question).
     *
     * <p><strong>The column and not {@code state}</strong>, though the two are one fact. {@code state} is derived,
     * and {@code dateReturned}, {@code dateEnroled} and {@code signatureReceivedDate} all outrank the signature
     * request in the cascade -- so a case that has been signed and enrolled reads {@code Enroled} while still
     * carrying the requested date, and a rule written against the state would offer the forms again to an applicant
     * whose deed has already been sealed. Reading the column is decision 9 exactly as it is written.
     */
    private static boolean formsApply(EmailTemplate template, DeedPoll deedPoll) {
        return FormsForCompletion.emailFor(deedPoll.getType()).filter(template::equals).isPresent()
            && deedPoll.getSignatureRequestedDate() == null;
    }

    /**
     * Whether the case is in the one state this chase is sent from.
     *
     * <p>{@link DeedPollState#AwaitingFurtherDocs} exactly, and a null state is not it: a case whose state has
     * never been derived is one this service knows nothing about, and the safe reading of "we do not know" is
     * that the applicant is not owed a demand for documents nobody has recorded a need for.
     */
    private static boolean awaitingFurtherDocuments(DeedPoll deedPoll) {
        return deedPoll.getState() == DeedPollState.AwaitingFurtherDocs;
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
     * Whether this referral is still one the applicant could be asked to confirm, which it is not once their
     * answer is recorded.
     *
     * <p><strong>Asking again and having an answer have to be mutually exclusive, and this is where they are
     * made so.</strong> {@code ReferralService.recordConfirmation} refuses a second answer and there is no route
     * that clears the first, so a second ④ could never lead to a recordable reply. Worse, it would ask an applicant
     * who has already replied to reply again, which is the office looking as though it had not read what they sent.
     *
     * <p><strong>This check and the clock's statement enforce the same rule at two moments, because one moment is
     * not enough.</strong> Here it is decided on a read; in {@link #startConfirmationClock} it is re-decided by the
     * {@code WHERE} clause of the statement that stamps the referral, which is what catches an answer recorded in
     * between -- the send is then refused there too, in the words this file's {@link #CONFIRMATION_ALREADY_ANSWERED}
     * gives, and the outbox row rolls back with it. So a caseworker gets the same 409 whichever side of the
     * applicant's answer their click landed on.
     *
     * <p><strong>Reachable rather than theoretical, which is why it is refused here rather than noted.</strong>
     * The V15 index stops a second ④ only while the first row still occupies the exclusion group; a row that
     * failed {@code PERMANENT}ly does not -- notify-1 deliberately permits a replacement -- and that is the state
     * of every ④ in an environment where the template is not yet provisioned. So the sequence is: ④ sent, case
     * waits, send fails terminally, the caseworker chases the applicant by phone and records the answer, and ④ is
     * then offered again.
     *
     * <p>Refusing the send rather than clearing the answer, because the answer is the office's record of what the
     * applicant said: a re-ask that silently discarded it would lose a decision about a refund. A case that
     * genuinely has to ask again needs a referral it can ask on, which is what raising one does.
     *
     * <p><strong>The constraint is deliberately one-directional: a recorded answer does not withhold ③.</strong>
     * Both templates share the {@code REFERRAL_OUTCOME} exclusion group, so the same {@code PERMANENT}-failure gap
     * that reaches this check also leaves ③ available once an answer is recorded -- including a {@code Confirmed}
     * one. That is not an oversight. A rejection invites no reply, so an
     * answer the applicant has already given says nothing about whether they may still be told a decision; and the
     * refusal ④ speaks for may have covered other {@link ReferralReason}s than publication, which one decision and
     * one direction cannot be decomposed into -- so an applicant who confirmed they want to proceed may still have
     * to be told enrolment was refused on a ground confirming had no bearing on. Which of the two emails fits is
     * the caseworker's judgement on every non-publication refusal, answered and unanswered alike; see
     * {@link #aboutNonPublication}. {@code CaseEmailServiceTest.answeredConfirmationIsNotOfferedAgain} pins it, so
     * withholding ③ on an answer would be a decision about the process rather than a tightening of this rule.
     */
    private static boolean awaitingAnAnswer(Referral referral) {
        return referral.getConfirmationOutcome() == null;
    }

    /**
     * The sentence that says why a template does not apply, which is per template for the reason its text is.
     *
     * <p>④ has three sentences because it has three conditions, and a caseworker can act on the difference: a
     * case with no refusal at all is waiting on a judge, a case whose newest refusal was about something else is
     * one where this email is simply the wrong email, and a case whose applicant has already answered needs
     * nothing sent at all. Told which, they do three different things.
     *
     * <p>The order repeats {@link #cause}'s filters, so the sentence names the first condition that failed rather
     * than the last one tested.
     */
    private static String reasonNotApplicable(
        EmailTemplate template, DeedPoll deedPoll, Optional<Referral> newestRefusal) {

        return switch (template) {
            case APPLICATION_REJECTED -> NO_REFUSAL;
            case REQUEST_CONFIRMATION_TO_PROCEED -> reasonNoConfirmationToAskFor(newestRefusal);
            // One condition, so one sentence -- and its own rather than a shared "this does not apply", because
            // what a caseworker does about it is nothing like what they do about a missing refusal.
            case CHASE_MISSING_DOCUMENTS -> NOT_AWAITING_FURTHER_DOCS;
            case FORMS_FOR_COMPLETION_ADULT, FORMS_FOR_COMPLETION_MINOR -> reasonNoFormsToSend(template, deedPoll);
        };
    }

    /**
     * Which of a forms email's conditions failed, in the order {@link #formsApply} tests them.
     *
     * <p>Four sentences for two conditions, because "the type does not take this deed" splits three ways and a
     * caseworker does something different about each: set the type, wait for somebody to identify a
     * relinquishment's forms, or send the other forms email instead. A shared sentence would send all three of them
     * looking in the same wrong place.
     *
     * <p>The third is composed from the deed the case does take rather than written per template, so it cannot
     * drift from {@link FormsForCompletion}'s routing -- and it says what a caseworker should do rather than only
     * what is wrong.
     */
    private static String reasonNoFormsToSend(EmailTemplate template, DeedPoll deedPoll) {
        Optional<DeedTemplate> deed = DeedRouting.deedFor(deedPoll.getType());
        if (deed.isEmpty()) {
            return deedPoll.getType() == DeedPollType.DeedOfRelinquishment
                ? RELINQUISHMENT_HAS_ITS_OWN_FORMS
                : TYPE_NOT_ESTABLISHED;
        }
        if (!FormsForCompletion.deedOf(template).equals(deed)) {
            return "This case takes " + deed.get().formCode() + ", so it is offered the other forms email and not "
                + "this one: an applicant sent the wrong deed would sign and swear the wrong legal instrument";
        }
        return SIGNATURE_ALREADY_REQUESTED;
    }

    /**
     * Which of ④'s three conditions failed, in the order {@link #cause} tests them.
     */
    private static String reasonNoConfirmationToAskFor(Optional<Referral> newestRefusal) {
        if (newestRefusal.isEmpty()) {
            return NO_REFUSAL_TO_CONFIRM;
        }
        if (!aboutNonPublication(newestRefusal.get())) {
            return REFUSAL_NOT_ABOUT_NON_PUBLICATION;
        }
        return CONFIRMATION_ALREADY_ANSWERED;
    }

    /**
     * The case and the reason it is entitled to this email, or the 404 or 409 that says it is not.
     *
     * <p><strong>Separate from {@link #compose} so that the recipient can be judged between them.</strong> A send
     * checks the address before the slot payload -- notify-1's decision, and the argument is in {@link #send}'s
     * own documentation -- which is only expressible if entitlement and composition are two steps. A preview
     * takes both in one go and never checks the address at all.
     */
    private Entitled entitle(String deedPollId, EmailTemplate template) {
        DeedPoll deedPoll = requireCase(deedPollId);
        // Read only where this template's own rule is about a referral. A chase is case-driven, so composing one
        // queries the referral table not at all -- which is decision 1 as behaviour rather than as intent, and a
        // switch expression because an arm somebody forgot would otherwise read a referral into a rule that has
        // no business with one.
        Optional<Referral> newestRefusal = switch (template) {
            case APPLICATION_REJECTED, REQUEST_CONFIRMATION_TO_PROCEED -> newestRefusal(deedPollId);
            case CHASE_MISSING_DOCUMENTS, FORMS_FOR_COMPLETION_ADULT, FORMS_FOR_COMPLETION_MINOR ->
                Optional.empty();
        };
        EmailCause cause = cause(template, deedPoll, newestRefusal)
            .orElseThrow(() -> new ConflictError(reasonNotApplicable(template, deedPoll, newestRefusal)));
        return new Entitled(deedPoll, cause);
    }

    /**
     * The case, the referral behind the email, and the personalisation the two of them plus the slots resolve to.
     *
     * <p>Shared by the preview and the send so that what a caseworker approved is what is stored: two resolvers
     * would be two ways of answering the same question, and the one place they could differ is the content of a
     * letter about a refusal.
     */
    private Composed compose(String deedPollId, EmailTemplate template, Map<String, Object> slots) {
        // A preview describes the deed rather than generating one -- see FormsForCompletionPersonalisation's
        // DEED_NOT_YET_GENERATED, and this method's own documentation for why a read must not write it.
        return compose(entitle(deedPollId, template), template, slots,
                       deed -> FormsForCompletionPersonalisation.DEED_NOT_YET_GENERATED);
    }

    /**
     * The same, once the case and its cause are in hand.
     *
     * @param deedDocument how this path gets a reference to the case's deed: the stored document's id on a send,
     *     and a description of where the link will go on a preview. A function rather than a flag so that the
     *     preview path does not merely <em>choose</em> not to store a deed -- it has no way to
     */
    private Composed compose(Entitled entitled,
                             EmailTemplate template,
                             Map<String, Object> slots,
                             Function<DeedTemplate, String> deedDocument) {
        DeedPoll deedPoll = entitled.deedPoll();
        EmailCause cause = entitled.cause();

        // The judge's words reach the email only through this slot, pre-populated and editable. ③'s resolve() is
        // deliberately not given the referral at all, and ④'s is given it only for the decision date and
        // documents that it never reads getDirectionsText() -- a second, un-edited copy of the direction in the
        // same email would make "what was this applicant told" a question with two answers. ⑤ has no referral,
        // so its cause pre-populates nothing and its one slot is the caseworker's from an empty box.
        Map<EmailSlot, String> accepted = EmailSlots.parse(template, slots, cause.directionsText());
        Map<String, Object> personalisation = switch (template) {
            case APPLICATION_REJECTED ->
                ApplicationRejectedPersonalisation.resolve(deedPoll, accepted).toMap();
            case REQUEST_CONFIRMATION_TO_PROCEED ->
                RequestConfirmationToProceedPersonalisation.resolve(
                    deedPoll, cause.requireReferral(), accepted).toMap();
            case CHASE_MISSING_DOCUMENTS ->
                MissingDocumentsPersonalisation.resolve(deedPoll, accepted).toMap();
            // One optional slot, the caseworker's notes. The one value that is neither a column nor a slot is the
            // deed, which the caller supplies because a send stores one and a preview must not.
            case FORMS_FOR_COMPLETION_ADULT, FORMS_FOR_COMPLETION_MINOR ->
                FormsForCompletionPersonalisation.resolve(
                    deedPoll, deedDocument.apply(requireDeed(template)), accepted).toMap();
        };
        return new Composed(deedPoll, cause, personalisation);
    }

    /**
     * The deed a forms email carries.
     *
     * <p>Unreachable for any other template: the only caller is the arm of a switch over {@link EmailTemplate}
     * whose members {@link FormsForCompletion#deedOf} answers for. That is what this says rather than a
     * {@code NoSuchElementException} two frames further in.
     */
    private static DeedTemplate requireDeed(EmailTemplate template) {
        return FormsForCompletion.deedOf(template).orElseThrow(() -> new IllegalStateException(
            "The " + template.key() + " email is composed with a deed, but it declares none"));
    }

    /**
     * The id of this case's deed, generating and storing it if the case has none yet.
     *
     * <p><strong>In the send's own transaction, which is what makes the row and the deed one act.</strong> If the
     * enqueue is then refused -- the other forms email already asked for, the request id reused -- the document row
     * and its blob roll back with the refusal, so a case never holds a deed for an email nobody was owed.
     *
     * <p><strong>Generated at most once per case</strong> (decision 6): a retry of the same compose journey finds
     * the deed the first attempt stored, which is why the frozen personalisation of an idempotent repeat compares
     * equal to the stored row's. {@code DeedDocumentService} is where that rule and its idempotency argument live.
     *
     * <p><strong>A case that already holds the <em>other</em> deed is a 409, and the outbox gets first say on
     * it.</strong> Both routes to a contradiction end here -- the case's type was corrected between two sends --
     * but only one of them is a contradiction the database can see: V20's index, like V15's, deliberately excludes
     * a row Notify refused {@code PERMANENT}, so a first forms email that failed that way leaves the slot free and
     * {@code storeDeed} is the only thing standing between the applicant and a minor's covering words carrying an
     * adult's deed. Where the slot is <em>not</em> free the refusal is the outbox's business rather than the deed's,
     * and the sentence has to be the one {@link #availability} already greyed the option out with -- so the slot is
     * read here, before a deed is generated, and {@code storeDeed}'s own refusal is what is left over.
     *
     * <p>Read rather than left to the insert, unlike everywhere else in this class: the insert happens after the
     * composition, so by the time it could answer the deed would already be stored. The index is still what settles
     * the race -- {@link #resolveRefusedInsert} is unchanged -- and this read only decides which of two true
     * sentences a caseworker is shown.
     */
    private String deedDocumentId(String deedPollId, EmailTemplate template, DeedTemplate deed) {
        counterpart(deedPollId, null, template).ifPresent(occupant -> {
            // Null referral because a forms email is case-driven and this method is reached for no other kind:
            // requireDeed is what says so, and FormsForCompletion.deedOf is where it is declared.
            log.info("Case {} already owes {} for its {} group, so {} was refused before a deed was generated",
                     deedPollId, occupant.getTemplate(), template.exclusionGroup().name(), template.key());
            throw new ConflictError(alreadyRequested(null, occupant));
        });
        return deeds.storeDeed(deedPollId, deed).getId();
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
     * The same JSON read back as a map, so it can be compared with one that came out of {@code jsonb}.
     *
     * <p>Only for {@link #isTheSameSend}. Parsing what this service has just written is not a round trip for its
     * own sake: it is what puts both sides of that comparison through the same deserialiser, so the comparison is
     * about the content rather than about which Java type each side happened to hold.
     */
    private Map<String, Object> asMap(String personalisation) {
        try {
            return json.readValue(personalisation, new TypeReference<>() {
            });
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Resolved personalisation could not be read back as JSON", impossible);
        }
    }

    /**
     * One request's resolved email: the case it is about, what entitled it, and what Notify is given.
     *
     * <p>A record rather than three returns, so the preview and the send cannot resolve two of the three and
     * disagree about the fourth.
     *
     * <p>{@link EmailCause} and not a {@code Referral}, because a case-driven template has none -- and a nullable
     * field here would put "is there one?" at every call site rather than at the one place that answers it.
     */
    private record Composed(DeedPoll deedPoll, EmailCause cause, Map<String, Object> personalisation) {
    }

    /**
     * A case that may have this email, and the reason it may -- everything a send knows before it reads the
     * caseworker's payload.
     */
    private record Entitled(DeedPoll deedPoll, EmailCause cause) {
    }
}
