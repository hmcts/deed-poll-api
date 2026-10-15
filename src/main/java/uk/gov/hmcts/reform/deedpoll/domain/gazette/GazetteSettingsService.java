package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteSettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Admin-only configuration with atomic change attribution and protection against stale edit forms.
 *
 * <p>A change also records one audit event naming {@code recipientEmail}, with no values: the settings belong to no
 * case, so a value could never be disposed, and V33's own history table already holds the addresses.
 */
@Service
public class GazetteSettingsService {

    public static final int MAX_EMAIL_LENGTH = 254;

    /**
     * The address rule as published in OpenAPI, which admin compiles with JavaScript's {@code RegExp}.
     *
     * <p><strong>Published, not compiled here.</strong> Its repeated groups make Java's regex engine recurse once per
     * repetition (Sonar S5998), and the fix Sonar suggests, possessive quantifiers, is a syntax error in JavaScript.
     * So this service checks the same rule with {@link #isWellFormed}, which has no repeated group, and
     * {@code GazetteSettingsServiceEmailTest} fails if the two ever disagree.
     */
    public static final String EMAIL_PATTERN = "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
        + "(?:[.][A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
        + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
        + "(?:[.][A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$";

    /**
     * One or more dot-atoms joined by single dots, once the dot rules in {@link #isWellFormed} have run.
     */
    private static final Pattern LOCAL_PART = Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+");

    /** One domain label: up to 63 characters, alphanumeric at both ends, hyphens only inside. */
    private static final Pattern DOMAIN_LABEL = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    /**
     * The settings are one row, {@code id = 1}, and the trail names it so.
     */
    private static final String SETTINGS_ID = "1";

    private final GazetteSettingsRepository repository;
    private final AuditTrail audit;

    public GazetteSettingsService(GazetteSettingsRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public GazetteSettings get(CurrentUser caller) {
        caller.require(Capability.ACCESS_GAZETTE);
        return repository.read(false);
    }

    @Transactional
    public GazetteSettings update(CurrentUser caller, Map<String, Object> fields) {
        caller.require(Capability.ACCESS_GAZETTE);
        final String subject = caller.requireUserId();
        if (!Set.of("recipientEmail", "revision").containsAll(fields.keySet())) {
            throw new InvalidInputError("Only recipientEmail and revision may be supplied.");
        }
        Object address = fields.get("recipientEmail");
        if (!(address instanceof String text) || text.isBlank()) {
            throw invalid("recipientEmail", "Enter the London Gazette recipient email address.");
        }
        String email = text.strip();
        if (email.length() > MAX_EMAIL_LENGTH || !isWellFormed(email) || email.indexOf('@') > 64) {
            throw invalid("recipientEmail", "Enter a valid email address, like name@example.com.");
        }
        if (!(fields.get("revision") instanceof Integer revision) || revision < 0) {
            throw invalid("revision", "Reload the recipient settings before saving.");
        }
        GazetteSettings previous = repository.read(true);
        if (previous.revision() != revision) {
            throw new ConflictError("The London Gazette recipient has changed. Reload the settings before saving.");
        }
        if (email.equals(previous.recipientEmail())) {
            return previous;
        }
        GazetteSettings next = new GazetteSettings(
            email, Math.incrementExact(revision), AuditClock.now(), caller.name());
        repository.change(previous, next, subject);
        audit.record(AuditEntry.of(AuditAction.GAZETTE_SETTINGS_CHANGED, AuditActor.of(caller),
                                   AuditEntities.GAZETTE_SETTINGS, SETTINGS_ID, null)
                         .withFields(List.of("recipientEmail")),
                     List.of());
        return next;
    }

    /**
     * Whether an address matches {@link #EMAIL_PATTERN}, decided without a repeated group.
     *
     * <p>Exactly one {@code @}, because neither part's characters include it. The local part is the pattern's
     * dot-atoms: its characters, with no dot first, last or doubled. The domain is two or more labels separated by
     * single dots, each a {@link #DOMAIN_LABEL}.
     */
    static boolean isWellFormed(String email) {
        int at = email.indexOf('@');
        if (at < 1 || at != email.lastIndexOf('@')) {
            return false;
        }
        String local = email.substring(0, at);
        if (!LOCAL_PART.matcher(local).matches()
            || local.charAt(0) == '.' || local.charAt(local.length() - 1) == '.' || local.contains("..")) {
            return false;
        }
        String[] labels = email.substring(at + 1).split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        for (String label : labels) {
            if (!DOMAIN_LABEL.matcher(label).matches()) {
                return false;
            }
        }
        return true;
    }

    private static InvalidInputError invalid(String field, String message) {
        return new InvalidInputError(message, List.of(new FieldError(field, message)));
    }
}
