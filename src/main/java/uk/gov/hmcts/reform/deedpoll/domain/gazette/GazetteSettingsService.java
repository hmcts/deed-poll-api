package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteSettingsRepository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Admin-only configuration with atomic change attribution and protection against stale edit forms. */
@Service
public class GazetteSettingsService {

    public static final int MAX_EMAIL_LENGTH = 254;
    public static final String EMAIL_PATTERN = "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
        + "(?:[.][A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@"
        + "[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
        + "(?:[.][A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$";
    private static final Pattern EMAIL = Pattern.compile(EMAIL_PATTERN);

    private final GazetteSettingsRepository repository;

    public GazetteSettingsService(GazetteSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public GazetteSettings get(CurrentUser caller) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        return repository.read(false);
    }

    @Transactional
    public GazetteSettings update(CurrentUser caller, Map<String, Object> fields) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        final String subject = caller.requireUserId();
        if (!Set.of("recipientEmail", "revision").containsAll(fields.keySet())) {
            throw new InvalidInputError("Only recipientEmail and revision may be supplied.");
        }
        Object address = fields.get("recipientEmail");
        if (!(address instanceof String text) || text.isBlank()) {
            throw invalid("recipientEmail", "Enter the London Gazette recipient email address.");
        }
        String email = text.strip();
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL.matcher(email).matches() || email.indexOf('@') > 64) {
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
        return next;
    }

    private static InvalidInputError invalid(String field, String message) {
        return new InvalidInputError(message, List.of(new FieldError(field, message)));
    }
}
