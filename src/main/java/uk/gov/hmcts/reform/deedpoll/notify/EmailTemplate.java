package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An email this service can send, and everything about it that is not the template's own words.
 *
 * <p>Five members. The <em>forms for completion</em> pair joined once this service could generate the deed they
 * carry, which Notify cannot do. A sixth -- {@code forms-for-completion-relinquishment} -- is settled as a real
 * template but its document set has not been identified, so it is deliberately not declared here yet: see
 * {@code docs/plans/notify-4a-attach-files-to-an-email.md} flag 5.
 *
 * <p><strong>Two are referral-driven and three are case-driven</strong>, which is the axis {@link EmailCause}
 * names: a template is entitled to be sent either by something a Master decided or by the state of the case
 * itself, and the second kind stores no {@code referralId} rather than borrowing one.
 *
 * <p><strong>The member name is what the outbox row stores; the {@link #key()} is what configuration and a
 * URL say.</strong> Those are two different things on purpose. A Notify template UUID differs per environment,
 * so it is configuration -- {@code notify.templates.<key>} -- and a row holding the UUID would stop being
 * readable the moment a template was re-pointed. The member name is stable and means the same thing in every
 * environment, so {@code Notification.template} holds that.
 *
 * <p>The key doubles as the URL token for {@code /caseworker/cases/{caseId}/emails/{template}}, deliberately:
 * it is already kebab-case, a client already sees it in the {@code GET} that lists what may be sent, and a
 * third spelling of the same idea would be a mapping table nobody maintains.
 *
 * <p><strong>{@link #resolvedKeys()} and {@link #slots()} together are the template's whole personalisation.</strong>
 * The split is the rule of decision 12: the slots are client input and the resolved keys are not, so
 * {@link EmailSlots#parse} can refuse a request that tries to cross the line and say which key it was.
 *
 * <p><strong>{@link #exclusionGroup()} is a required argument, and that is the point of it.</strong> Two emails
 * that contradict each other must not both reach one applicant, and the group is what the outbox's unique index
 * is keyed on -- so a template added here cannot be written without somebody saying which emails it is an
 * alternative to. See {@link EmailExclusionGroup}.
 *
 * <p><strong>{@link #invitesReply()} is a required argument for the same reason, and it is a property of the
 * template rather than of the service.</strong> A template whose words ask the applicant to reply needs the
 * monitored inbox configured as {@code notify.email-reply-to-id}, or Notify sends it from an address nobody
 * reads -- so such a template is <em>unprovisioned</em> without one, exactly as any template is without a UUID.
 * Asked per template so that a missing reply-to cannot disable an email that never invited one.
 */
public enum EmailTemplate {

    /**
     * Enrolment refused: quotes the judge, then starts a refund. Template ③.
     *
     * <p>Referral-driven -- it is sent on a judge's direction -- which is why the outbox's partial unique index
     * on {@code ("referralId", "exclusionGroup")} makes one email of this group per referral a database
     * constraint. An applicant must not be told twice that their application was refused, and must not be told
     * it was refused and then asked whether to proceed with it.
     *
     * <p>Invites no reply: it tells the applicant a decision and starts a refund, and asks them for nothing. So
     * it goes on sending from Notify's default sender however this service's reply-to is configured.
     */
    APPLICATION_REJECTED(
        "application-rejected",
        "Application rejected",
        EmailExclusionGroup.REFERRAL_OUTCOME,
        false,
        ApplicationRejectedPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES),
        List.of()),

    /**
     * A non-publication request refused, but enrolment may still go ahead: asks the applicant to confirm they
     * still want the deed enrolled, or to say they do not and be refunded. Template ④.
     *
     * <p>Referral-driven like ③, and deliberately <em>not</em> derived from the decision: both are sent off a
     * {@code Rejected} referral and they say opposite things about enrolment, so the caseworker chooses. Only a
     * referral raised for {@code NonPublication} offers this one -- see {@link CaseEmailService} for why that is
     * necessary without being sufficient.
     *
     * <p>Declared after ③ rather than before it because the order here is the order the Email tab lists
     * templates in, and the one that refuses enrolment outright is the commoner of the two.
     *
     * <p>In ③'s exclusion group, because the two say opposite things about enrolment: one referral gets one of
     * them, and which one is the caseworker's choice made once.
     *
     * <p><strong>Invites a reply, and that is the whole point of the email.</strong> Two of its paragraphs say
     * "let us know by replying to this email", so Notify's default no-reply sender would lose the answer this
     * email exists to collect. {@code notify.email-reply-to-id} is therefore required configuration for it, and
     * it reads unprovisioned without one.
     */
    REQUEST_CONFIRMATION_TO_PROCEED(
        "request-confirmation-to-proceed",
        "Request confirmation to proceed",
        EmailExclusionGroup.REFERRAL_OUTCOME,
        true,
        RequestConfirmationToProceedPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES),
        List.of()),

    /**
     * Waiting for further documents or information: names what is still needed and asks the applicant to send
     * it. Template ⑤.
     *
     * <p><strong>The first case-driven template, and the reason {@link EmailCause} exists.</strong> It is
     * offered when the case's own state is {@code AwaitingFurtherDocs} -- see {@code CaseEmailService} -- so
     * there is no referral behind it and its outbox rows store a null {@code referralId}, which is what V11
     * designed the nullable column for. No judge, no direction, no decision date and no refund branch.
     *
     * <p><strong>Its group is its own, and a repeat chase is deliberately not refused.</strong> A case can sit
     * in {@code AwaitingFurtherDocs} after one reminder, and the caseworker may need to chase again -- so the
     * mutual exclusion the other two have is the wrong shape here. See {@link EmailExclusionGroup#DOCUMENT_CHASE}
     * for why a group is declared anyway, and the plan's decision 6 for what separates a deliberate second
     * chase from a double-click.
     *
     * <p><strong>Invites a reply, and the wording says so twice.</strong> "Reply to this email" is how the
     * applicant sends what is missing or says they have already sent it, so {@code notify.email-reply-to-id} is
     * required configuration and this template reads unprovisioned without one.
     *
     * <p>One slot, required: only a caseworker knows which documents are outstanding. Nothing is attached --
     * this email asks for incoming material and sends none -- so it declares no document keys.
     */
    CHASE_MISSING_DOCUMENTS(
        "chase-missing-documents",
        "Chase missing documents",
        EmailExclusionGroup.DOCUMENT_CHASE,
        true,
        MissingDocumentsPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.DOCUMENTS_REQUIRED),
        List.of()),

    /**
     * The forms an adult applicant has to print, sign and swear, with their generated LOC020 deed. Template ①.
     *
     * <p><strong>The first template that carries a document, which is what {@link #documentKeys()} exists
     * for.</strong> The deed is generated per case by {@code DeedDocumentService} and its id is frozen into the
     * row's personalisation under {@link FormsForCompletionPersonalisation#DEED_DOCUMENT}; the drain resolves that
     * to a Notify download link. The other three forms this email names -- LOC021 and LOC027 -- are the same bytes
     * for every applicant and are attached to the Notify template itself, so this service never holds them.
     *
     * <p><strong>Case-driven, like ⑤, and its condition is the case's own signature date.</strong> Sending this
     * email <em>is</em> the request for a signature (decision 9), so it is offered while
     * {@code signatureRequestedDate} is null and the case's type takes the adult deed. Whether the send should
     * therefore set that date is a business question this service deliberately does not answer -- the plan's flag
     * 2 -- so it does not.
     *
     * <p><strong>In a group of two with ②, exclusive per case rather than per referral.</strong> There is no
     * referral behind either, so V15's index never sees their rows; V20's case-scoped key is what stops one
     * applicant being sent two different deeds to swear. See {@link EmailExclusionGroup#FORMS_FOR_COMPLETION}.
     *
     * <p><strong>One optional slot, {@link EmailSlot#ADDITIONAL_NOTES}</strong> -- the same box ③ and ④ carry, not
     * a second one meaning the same thing. See {@link FormsForCompletionPersonalisation}, which also has the reason
     * this email addresses the applicant by their former name.
     *
     * <p><strong>Invites a reply, which an earlier draft of the plan had not noticed.</strong> The source ends
     * "you can reply to this email or call the Deed Poll office", so {@code notify.email-reply-to-id} is required
     * configuration and this template reads unprovisioned without one.
     */
    FORMS_FOR_COMPLETION_ADULT(
        "forms-for-completion-adult",
        "Forms for completion",
        EmailExclusionGroup.FORMS_FOR_COMPLETION,
        true,
        FormsForCompletionPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.ADDITIONAL_NOTES),
        FormsForCompletionPersonalisation.DOCUMENT_KEYS),

    /**
     * The same, for a child's application: four forms rather than three, and the LOC022 deed. Template ②.
     *
     * <p>Everything ① says applies, with two differences that are the source documents' own: this one names
     * LOC023, LOC024 and LOC028 instead of LOC021 and LOC027, and its Exhibit B is the child's birth certificate
     * rather than evidence of citizenship. None of that is a difference in the values below -- both resolve from
     * {@link FormsForCompletionPersonalisation} -- which is why two templates is decision 1 rather than one
     * template with conditionals in it.
     *
     * <p>Declared after ① because the order here is the order the Email tab lists templates in, and an adult
     * application is much the commoner of the two.
     *
     * <p>A different {@link #displayName()} from ①'s, unlike its wording, because the display name is what a case
     * note says an email was -- and "Forms for completion" on both would make a timeline unable to say which deed
     * an applicant was sent. See {@link EmailNotes}.
     */
    FORMS_FOR_COMPLETION_MINOR(
        "forms-for-completion-minor",
        "Forms for completion for a child",
        EmailExclusionGroup.FORMS_FOR_COMPLETION,
        true,
        FormsForCompletionPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.ADDITIONAL_NOTES),
        FormsForCompletionPersonalisation.DOCUMENT_KEYS);

    private final String key;
    private final String displayName;
    private final EmailExclusionGroup exclusionGroup;
    private final boolean invitesReply;
    private final List<String> resolvedKeys;
    private final List<EmailSlot> slots;
    private final List<String> documentKeys;

    EmailTemplate(
        String key,
        String displayName,
        EmailExclusionGroup exclusionGroup,
        boolean invitesReply,
        List<String> resolvedKeys,
        List<EmailSlot> slots,
        List<String> documentKeys
    ) {
        this.key = key;
        this.displayName = displayName;
        this.exclusionGroup = exclusionGroup;
        this.invitesReply = invitesReply;
        this.resolvedKeys = List.copyOf(resolvedKeys);
        this.slots = List.copyOf(slots);
        this.documentKeys = List.copyOf(documentKeys);
    }

    /**
     * The template by its {@link #key()}, or empty if it names none.
     *
     * <p>Matched exactly, as every other name this service reads off the wire is: a near-miss is a client bug
     * worth reporting rather than guessing at.
     */
    public static Optional<EmailTemplate> byKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String trimmed = key.trim();
        return Arrays.stream(values())
            .filter(candidate -> candidate.key.equals(trimmed))
            .findFirst();
    }

    /**
     * The template whose <em>member name</em> is {@code name}, or empty if no member has it.
     *
     * <p>The other half of the split this class's javadoc describes, and the direction the drain reads in: an
     * outbox row stores the member name, so the sender has to turn that back into a member to find the
     * configured Notify UUID. Empty rather than an exception, because the one way this happens is a member
     * renamed after rows were written -- which the sender reports as a permanent failure naming the row, rather
     * than as a crash that takes the rest of the batch with it.
     */
    public static Optional<EmailTemplate> byName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.trim();
        return Arrays.stream(values())
            .filter(candidate -> candidate.name().equals(trimmed))
            .findFirst();
    }

    /** Every key, for a refusal that has to say what it would have accepted. */
    public static String keys() {
        return Arrays.stream(values()).map(EmailTemplate::key).collect(Collectors.joining(", "));
    }

    /**
     * The configuration key under {@code notify.templates}, and the URL token.
     */
    public String key() {
        return key;
    }

    /** The name a caseworker sees for this email type. */
    public String displayName() {
        return displayName;
    }

    /**
     * The set of emails this one is an alternative to, of which a referral gets exactly one.
     *
     * <p>Written onto the outbox row by its member name and keyed on by the partial unique index, so the
     * choice is settled by Postgres rather than by a check with a race in it. The row keeps the group it was
     * written under -- a deliberate copy rather than a lookup, so re-grouping templates later cannot
     * retrospectively make two already-sent emails conflict.
     */
    public EmailExclusionGroup exclusionGroup() {
        return exclusionGroup;
    }

    /**
     * Whether this email's own words ask the applicant to reply to it.
     *
     * <p><strong>What it decides is where the reply goes, and whether the email may be sent at all.</strong> A
     * template that invites a reply is sent with {@code notify.email-reply-to-id} as its reply-to address, and is
     * {@linkplain NotifyProperties#isProvisioned unprovisioned} without one -- a verified inbox nobody configured
     * fails in exactly the way no inbox does, and silently, because Notify drops a blank reply-to and sends from
     * no-reply instead. One that does not invite a reply is unaffected by the setting in either direction: it is
     * sent through the client's four-argument overload, so configuring a reply-to for ④ cannot quietly change ③.
     */
    public boolean invitesReply() {
        return invitesReply;
    }

    /** The personalisation keys resolved from the case; a client may not set any of these. */
    public List<String> resolvedKeys() {
        return resolvedKeys;
    }

    /** The slots a caseworker fills, in the order a form should show them. */
    public List<EmailSlot> slots() {
        return slots;
    }

    /**
     * Personalisation keys whose frozen values are supporting-document ids.
     *
     * <p>Empty until a template carries documents. Declaring the keys on the template keeps correspondence
     * history from mistaking an arbitrary personalisation value for a document id.
     */
    public List<String> documentKeys() {
        return documentKeys;
    }

    /**
     * Every personalisation key this template sends, resolved values first then slots.
     *
     * <p>The order {@code toMap()} writes them in, so a test can hold the two to each other.
     */
    public List<String> personalisationKeys() {
        List<String> keys = new ArrayList<>(resolvedKeys);
        slots.forEach(slot -> keys.add(slot.placeholder()));
        return List.copyOf(keys);
    }

    /**
     * Whether {@code name} is one of the keys this service resolves rather than accepts.
     */
    public boolean resolves(String name) {
        return resolvedKeys.contains(name);
    }

    /**
     * The slot this template takes under {@code placeholder}, or empty if it takes none by that name.
     */
    public Optional<EmailSlot> slot(String placeholder) {
        return slots.stream().filter(candidate -> candidate.placeholder().equals(placeholder)).findFirst();
    }

    /**
     * The Notify template UUID for this template in this environment.
     *
     * <p>{@link NotifyProperties#UNPROVISIONED_TEMPLATE_ID} until notify-0 provisions one, at which point a
     * preview answers 400 and a send 403 -- both the taxonomy's permanent branch, which is the correct answer
     * to "this template does not exist" rather than a defect. {@link NotifyProperties#isProvisioned} is how the
     * Email tab tells a caseworker that instead.
     */
    public String templateId(NotifyProperties properties) {
        return properties.templateId(key);
    }
}
