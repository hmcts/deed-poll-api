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
import java.time.LocalTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.AFTERNOON_WINDOW_END;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.AFTERNOON_WINDOW_START;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.MASTER_WINDOW_CAP;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.MORNING_WINDOW_END;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.MORNING_WINDOW_START;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.REFERRAL_CLAIM_EXPIRY_MINUTES;
import static uk.gov.hmcts.reform.deedpoll.domain.settings.ProcessSettings.REMINDER_INTERVAL_HOURS;

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

    /** The shortest processing window a Delivery Manager may set, in minutes (1 day). V42's check states it too. */
    public static final int MIN_REFERRAL_CLAIM_EXPIRY_MINUTES = 1440;

    /** The longest processing window a Delivery Manager may set, in minutes (30 days). V42's check states it too. */
    public static final int MAX_REFERRAL_CLAIM_EXPIRY_MINUTES = 43200;

    public static final int MIN_REMINDER_INTERVAL_HOURS = 1;

    /** One week. V42's check states it too. */
    public static final int MAX_REMINDER_INTERVAL_HOURS = 168;

    public static final int MIN_MASTER_WINDOW_CAP = 1;

    public static final int MAX_MASTER_WINDOW_CAP = 100;

    private static final String REVISION = "revision";

    private static final Set<String> KEYS;

    static {
        Set<String> keys = new HashSet<>(ProcessSettings.FIELDS);
        keys.add(REVISION);
        KEYS = Set.copyOf(keys);
    }

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

    /**
     * The processing window and the revision are required. The other settings are optional for one release, so the
     * admin that sends only those two keeps working: an absent setting keeps its stored value, and an explicit null
     * is refused rather than read as absent.
     */
    @Transactional
    public ProcessSettings update(CurrentUser caller, Map<String, Object> fields) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        final String subject = caller.requireUserId();
        for (String key : fields.keySet()) {
            if (!KEYS.contains(key)) {
                throw invalid(key, "Only the process settings and revision may be supplied.");
            }
        }
        final int minutes = whole(fields, REFERRAL_CLAIM_EXPIRY_MINUTES, MIN_REFERRAL_CLAIM_EXPIRY_MINUTES,
            MAX_REFERRAL_CLAIM_EXPIRY_MINUTES, "Enter a processing window from " + MIN_REFERRAL_CLAIM_EXPIRY_MINUTES
                + " to " + MAX_REFERRAL_CLAIM_EXPIRY_MINUTES + " minutes (1 to 30 days), in whole minutes.");
        final Optional<Integer> hours = optionalWhole(fields, REMINDER_INTERVAL_HOURS, MIN_REMINDER_INTERVAL_HOURS,
            MAX_REMINDER_INTERVAL_HOURS, "Enter a reminder interval from " + MIN_REMINDER_INTERVAL_HOURS + " to "
                + MAX_REMINDER_INTERVAL_HOURS + " hours, in whole hours.");
        final Optional<Integer> cap = optionalWhole(fields, MASTER_WINDOW_CAP, MIN_MASTER_WINDOW_CAP,
            MAX_MASTER_WINDOW_CAP, "Enter a cap from " + MIN_MASTER_WINDOW_CAP + " to " + MAX_MASTER_WINDOW_CAP
                + " referrals per Master per window.");
        Map<String, LocalTime> times = new HashMap<>();
        for (String field : ProcessSettings.WINDOW_FIELDS) {
            if (fields.containsKey(field)) {
                times.put(field, ProcessSettingsTimes.parse(fields.get(field)).orElseThrow(() -> invalid(field,
                    "Enter a time from 00:00 to 23:45 as HH:mm, on the quarter hour (00, 15, 30 or 45).")));
            }
        }
        if (!(fields.get(REVISION) instanceof Integer revision) || revision < 0) {
            throw invalid(REVISION, "Reload the process settings before saving.");
        }
        ProcessSettings previous = read(true);
        // Before the no-op, so an out-of-date form holding the value the row now has is never told it saved.
        if (previous.revision() != revision) {
            throw new ConflictError("The process settings have changed. Reload them before saving.");
        }
        ProcessSettings candidate = new ProcessSettings(
            Duration.ofMinutes(minutes),
            hours.map(Duration::ofHours).orElse(previous.reminderInterval()),
            cap.orElse(previous.masterWindowCap()),
            times.getOrDefault(MORNING_WINDOW_START, previous.morningWindowStart()),
            times.getOrDefault(MORNING_WINDOW_END, previous.morningWindowEnd()),
            times.getOrDefault(AFTERNOON_WINDOW_START, previous.afternoonWindowStart()),
            times.getOrDefault(AFTERNOON_WINDOW_END, previous.afternoonWindowEnd()),
            previous.revision(), previous.updatedAt(), previous.updatedBy());
        requireOrdered(candidate);
        List<String> changed = ProcessSettings.FIELDS.stream()
            .filter(field -> !previous.valueOf(field).equals(candidate.valueOf(field)))
            .toList();
        if (changed.isEmpty()) {
            return previous;
        }
        ProcessSettings next = new ProcessSettings(
            candidate.processingWindow(), candidate.reminderInterval(), candidate.masterWindowCap(),
            candidate.morningWindowStart(), candidate.morningWindowEnd(), candidate.afternoonWindowStart(),
            candidate.afternoonWindowEnd(), Math.incrementExact(revision), AuditClock.now(), caller.name());
        repository.change(previous, next, subject, changed);
        audit.record(AuditEntry.of(AuditAction.PROCESS_SETTINGS_CHANGED, AuditActor.of(caller),
                                   AuditEntities.PROCESS_SETTINGS, SETTINGS_ID, null)
                         .withFields(changed),
                     List.of());
        return next;
    }

    /**
     * How long a Master has to give a direction before the referral is unassigned. No capability check: the
     * referral sweeps run for whoever's request triggers them. Unlocked, and joins the caller's transaction.
     *
     * @throws IllegalStateException if the seeded row is missing; there is no default to fall back to
     */
    public Duration processingWindow() {
        return read(false).processingWindow();
    }

    /** How often a Master is reminded of their undirected referrals. No capability check, no cache. */
    public Duration reminderInterval() {
        return read(false).reminderInterval();
    }

    /** How many referrals one Master may be assigned per referral window. No capability check, no cache. */
    public int masterWindowCap() {
        return read(false).masterWindowCap();
    }

    /**
     * The two daily referral windows, in {@link ReferralWindows#ZONE}. No capability check, no cache.
     */
    public ReferralWindows referralWindows() {
        return read(false).referralWindows();
    }

    /** Each window starts before it ends, and the morning one ends before the afternoon one starts. */
    private static void requireOrdered(ProcessSettings settings) {
        if (!settings.morningWindowStart().isBefore(settings.morningWindowEnd())) {
            throw invalid(MORNING_WINDOW_END, "The morning window must end after it starts.");
        }
        if (!settings.morningWindowEnd().isBefore(settings.afternoonWindowStart())) {
            throw invalid(AFTERNOON_WINDOW_START, "The afternoon window must start after the morning window ends.");
        }
        if (!settings.afternoonWindowStart().isBefore(settings.afternoonWindowEnd())) {
            throw invalid(AFTERNOON_WINDOW_END, "The afternoon window must end after it starts.");
        }
    }

    private static int whole(Map<String, Object> fields, String field, int min, int max, String message) {
        if (!(fields.get(field) instanceof Integer value) || value < min || value > max) {
            throw invalid(field, message);
        }
        return value;
    }

    private static Optional<Integer> optionalWhole(Map<String, Object> fields, String field, int min, int max,
                                                   String message) {
        return fields.containsKey(field) ? Optional.of(whole(fields, field, min, max, message)) : Optional.empty();
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
