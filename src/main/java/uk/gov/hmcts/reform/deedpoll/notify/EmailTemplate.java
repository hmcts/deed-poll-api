package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * An email this service can send, and everything about it that is not the template's own words.
 *
 * <p>One member, because this plan ships one template. The other three in
 * {@code NOTIFY_TEMPLATE_TAXONOMY.md} §6 are later plans: <em>Request confirmation to proceed</em> needs the
 * discrimination question in §4 settled, and the two <em>forms for completion</em> emails are blocked on
 * document generation, which Notify cannot do.
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
 */
public enum EmailTemplate {

    /**
     * Enrolment refused: quotes the judge, then starts a refund. Template ③.
     *
     * <p>Referral-driven -- it is sent on a judge's direction -- which is why the outbox's partial unique index
     * on {@code ("referralId", "template")} makes one of these per referral a database constraint. An applicant
     * must not be told twice that their application was refused.
     */
    APPLICATION_REJECTED(
        "application-rejected",
        ApplicationRejectedPersonalisation.RESOLVED_KEYS,
        List.of(EmailSlot.JUDGES_REASON, EmailSlot.ADDITIONAL_NOTES));

    private final String key;
    private final List<String> resolvedKeys;
    private final List<EmailSlot> slots;

    EmailTemplate(String key, List<String> resolvedKeys, List<EmailSlot> slots) {
        this.key = key;
        this.resolvedKeys = List.copyOf(resolvedKeys);
        this.slots = List.copyOf(slots);
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

    /** The personalisation keys resolved from the case; a client may not set any of these. */
    public List<String> resolvedKeys() {
        return resolvedKeys;
    }

    /** The slots a caseworker fills, in the order a form should show them. */
    public List<EmailSlot> slots() {
        return slots;
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
