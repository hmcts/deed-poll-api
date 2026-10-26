package uk.gov.hmcts.reform.deedpoll.domain.settings;

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
import uk.gov.hmcts.reform.deedpoll.repository.ProcessSettingsRepository;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.REFERRAL_CLAIM_EXPIRY_MINUTES;

/**
 * The service's business process values, which a Delivery Manager changes while it runs, with atomic change
 * attribution and protection against stale edit forms. The shape of {@code GazetteSettingsService}, deliberately.
 *
 * <p>A change records one audit event naming the changed fields, with no values: the settings belong to no case, so
 * a value could never be disposed, and V40's own history table holds the before and after values.
 *
 * <p>Nothing is cached. Every read is the row, so a committed change applies to the next read everywhere.
 */
@Service
public class ProcessSettingsService {

    /** The shortest claim window a Delivery Manager may set, in minutes. V40's check states it too. */
    public static final int MIN_REFERRAL_CLAIM_EXPIRY_MINUTES = 30;

    /** The longest claim window a Delivery Manager may set, in minutes (24 hours). V40's check states it too. */
    public static final int MAX_REFERRAL_CLAIM_EXPIRY_MINUTES = 1440;

    private static final String REVISION = "revision";

    private static final Set<String> KEYS = Set.of(REFERRAL_CLAIM_EXPIRY_MINUTES, REVISION);

    /**
     * The settings are one row, {@code id = 1}, and the trail names it so.
     */
    private static final String SETTINGS_ID = "1";

    private final ProcessSettingsRepository repository;
    private final AuditTrail audit;

    public ProcessSettingsService(ProcessSettingsRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public ProcessSettings get(CurrentUser caller) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        return read(false);
    }

    @Transactional
    public ProcessSettings update(CurrentUser caller, Map<String, Object> fields) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        final String subject = caller.requireUserId();
        for (String key : fields.keySet()) {
            if (!KEYS.contains(key)) {
                throw invalid(key, "Only referralClaimExpiryMinutes and revision may be supplied.");
            }
        }
        if (!(fields.get(REFERRAL_CLAIM_EXPIRY_MINUTES) instanceof Integer minutes)
            || minutes < MIN_REFERRAL_CLAIM_EXPIRY_MINUTES || minutes > MAX_REFERRAL_CLAIM_EXPIRY_MINUTES) {
            throw invalid(REFERRAL_CLAIM_EXPIRY_MINUTES, "Enter a claim window from "
                + MIN_REFERRAL_CLAIM_EXPIRY_MINUTES + " to " + MAX_REFERRAL_CLAIM_EXPIRY_MINUTES
                + " minutes, in whole minutes.");
        }
        if (!(fields.get(REVISION) instanceof Integer revision) || revision < 0) {
            throw invalid(REVISION, "Reload the process settings before saving.");
        }
        ProcessSettings previous = read(true);
        // Before the no-op, so an out-of-date form holding the value the row now has is never told it saved.
        if (previous.revision() != revision) {
            throw new ConflictError("The process settings have changed. Reload them before saving.");
        }
        if (previous.referralClaimExpiryMinutes() == minutes) {
            return previous;
        }
        ProcessSettings next = new ProcessSettings(
            Duration.ofMinutes(minutes), Math.incrementExact(revision), AuditClock.now(), caller.name());
        List<String> changed = List.of(REFERRAL_CLAIM_EXPIRY_MINUTES);
        repository.change(previous, next, subject, changed);
        audit.record(AuditEntry.of(AuditAction.PROCESS_SETTINGS_CHANGED, AuditActor.of(caller),
                                   AuditEntities.PROCESS_SETTINGS, SETTINGS_ID, null)
                         .withFields(changed),
                     List.of());
        return next;
    }

    /**
     * How long a judge's untouched claim survives, for the referral sweep. No capability check: the sweep runs for
     * whoever loads the judicial pool. Unlocked, and joins the caller's transaction.
     *
     * @throws IllegalStateException if the seeded row is missing; there is no default to fall back to
     */
    public Duration referralClaimExpiry() {
        return read(false).referralClaimExpiry();
    }

    /** V40 seeds the row and nothing deletes it, so its absence is an error and never a default. */
    private ProcessSettings read(boolean lock) {
        return repository.read(lock).orElseThrow(() -> new IllegalStateException(
            "The process settings row is missing; V40 seeds it and nothing deletes it."));
    }

    private static InvalidInputError invalid(String field, String message) {
        return new InvalidInputError(message, List.of(new FieldError(field, message)));
    }
}
