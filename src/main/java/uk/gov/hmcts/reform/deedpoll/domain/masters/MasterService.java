package uk.gov.hmcts.reform.deedpoll.domain.masters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditActor;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntities;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditEntry;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditTrail;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Delivery Manager's Masters list (plan D1, decision D-2): a name, an email and an available flag, linked to the
 * Master's IDAM account the first time they use the service, by the email IDAM gives for them.
 *
 * <p>Any staff caller may read the list, because a caseworker chooses a Master from it. Only
 * {@link Capability#MANAGE_PROCESS_CONFIG} may add or change an entry, and nobody may delete one: a Master who leaves
 * is marked unavailable, because referrals and history refer to them.
 *
 * <p>Audited by field name only. No case owns a Master, so a value could never be disposed with one, and the email
 * is personal data: it never reaches an audit event, a log line or an error message, which is why a duplicate is
 * refused as "another Master already has this email address" rather than by the address.
 */
@Service
public class MasterService {

    public static final int MAX_NAME_LENGTH = 200;

    public static final int MAX_EMAIL_LENGTH = 320;

    public static final String NAME = "name";
    public static final String EMAIL = "email";
    public static final String AVAILABLE = "available";
    public static final String REVISION = "revision";

    /**
     * The field name an unlinking change records beside {@link #EMAIL}.
     */
    static final String LINKED = "linked";

    /**
     * Whoever {@code StaffArea.CASEWORKER} admits; the list is a caseworker's choice of Master.
     */
    @SuppressWarnings("deprecation")
    private static final Role[] STAFF = {Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER,
        Role.DEEDPOLL_ADMIN};

    private static final Set<String> CREATE_KEYS = Set.of(NAME, EMAIL, AVAILABLE);
    private static final Set<String> UPDATE_KEYS = Set.of(NAME, EMAIL, AVAILABLE, REVISION);

    private static final String DUPLICATE_EMAIL = "Another Master already has this email address.";

    private static final Logger LOG = LoggerFactory.getLogger(MasterService.class);

    private final MasterRepository repository;
    private final AuditTrail audit;

    public MasterService(MasterRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** Every entry, available or not, ordered by name and then id. */
    @Transactional(readOnly = true)
    public List<Master> list(CurrentUser caller) {
        caller.requireAnyRole(STAFF);
        return repository.list();
    }

    /**
     * {@code name} and {@code email} are required; {@code available} defaults to true.
     */
    @Transactional
    public Master create(CurrentUser caller, Map<String, Object> fields) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        final String subject = caller.requireUserId();
        refuseUnknown(fields, CREATE_KEYS);
        String name = name(fields.get(NAME));
        String email = email(fields.get(EMAIL));
        boolean available = !fields.containsKey(AVAILABLE) || available(fields.get(AVAILABLE));
        if (repository.emailTaken(email, null)) {
            throw duplicateEmail();
        }
        UUID id = UUID.randomUUID();
        try {
            repository.insert(id, name, email, available, AuditClock.now(), subject, caller.name());
        } catch (DuplicateKeyException raced) {
            // Its message carries the address, so it goes no further than here.
            throw duplicateEmail();
        }
        audit.record(AuditEntry.of(AuditAction.MASTER_ADDED, AuditActor.of(caller), AuditEntities.MASTER,
                                   id.toString(), null)
                         .withFields(List.of(NAME, EMAIL, AVAILABLE)),
                     List.of());
        return new Master(id.toString(), name, email, available, false, 0);
    }

    /**
     * A keyed patch of {@code name}, {@code email} and {@code available}, at least one of them, with the revision
     * the form read. A stale revision is 409 even when nothing would change; saving the current values is a no-op
     * with no audit. A changed email unlinks the entry, so the new address has to sign in to link it again.
     */
    @Transactional
    public Master update(CurrentUser caller, String id, Map<String, Object> fields) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        final String subject = caller.requireUserId();
        refuseUnknown(fields, UPDATE_KEYS);
        if (!fields.containsKey(NAME) && !fields.containsKey(EMAIL) && !fields.containsKey(AVAILABLE)) {
            throw invalid(NAME, "Supply at least one of name, email and available.");
        }
        Optional<String> name = fields.containsKey(NAME) ? Optional.of(name(fields.get(NAME))) : Optional.empty();
        Optional<String> email = fields.containsKey(EMAIL) ? Optional.of(email(fields.get(EMAIL))) : Optional.empty();
        Optional<Boolean> available = fields.containsKey(AVAILABLE)
            ? Optional.of(available(fields.get(AVAILABLE))) : Optional.empty();
        if (!(fields.get(REVISION) instanceof Integer revision) || revision < 0) {
            throw invalid(REVISION, "Reload the Masters list before saving.");
        }
        UUID key = parseId(id);
        Master previous = repository.findForUpdate(key).orElseThrow(MasterService::notFound);
        // Before the no-op, so an out-of-date form holding the values the row now has is never told it saved.
        if (previous.revision() != revision) {
            throw new ConflictError("This Master has changed. Reload the Masters list before saving.");
        }
        String nextName = name.orElse(previous.name());
        String nextEmail = email.orElse(previous.email());
        boolean nextAvailable = available.orElse(previous.available());
        List<String> changed = new ArrayList<>();
        if (!nextName.equals(previous.name())) {
            changed.add(NAME);
        }
        boolean emailChanged = !nextEmail.equals(previous.email());
        if (emailChanged) {
            changed.add(EMAIL);
        }
        if (nextAvailable != previous.available()) {
            changed.add(AVAILABLE);
        }
        if (changed.isEmpty()) {
            return previous;
        }
        if (emailChanged && repository.emailTaken(nextEmail, key)) {
            throw duplicateEmail();
        }
        boolean unlink = emailChanged && previous.linked();
        if (unlink) {
            changed.add(LINKED);
        }
        int nextRevision = Math.incrementExact(revision);
        try {
            repository.update(key, nextName, nextEmail, nextAvailable, unlink, nextRevision, AuditClock.now(),
                              subject, caller.name());
        } catch (DuplicateKeyException raced) {
            throw duplicateEmail();
        }
        audit.record(AuditEntry.of(AuditAction.MASTER_CHANGED, AuditActor.of(caller), AuditEntities.MASTER,
                                   key.toString(), null)
                         .withFields(changed),
                     List.of());
        return new Master(key.toString(), nextName, nextEmail, nextAvailable, previous.linked() && !unlink,
                          nextRevision);
    }

    /**
     * Links the caller to the unlinked entry holding their IDAM email, the first time a judge uses the service.
     *
     * <p>Nothing happens unless the caller holds {@link Role#JUDICIAL} and has both an email and a subject. Linking
     * never creates an entry, because the list is the Delivery Manager's, and never moves one: an entry linked to
     * another account, or an account already linked to another entry, is left alone with a fixed WARN that names
     * neither the address nor the subject. The write is conditional on the entry still being unlinked, so two pods
     * racing link it once, and only the one that wrote records {@link AuditAction#MASTER_LINKED}, as the service.
     */
    @Transactional
    public void linkIfListed(CurrentUser caller) {
        if (!caller.roles().contains(Role.JUDICIAL) || caller.email() == null || caller.userId() == null) {
            return;
        }
        String subject = caller.userId();
        Optional<MasterRepository.Linking> entry = repository.linkingByEmail(caller.email());
        if (entry.isEmpty() || subject.equals(entry.get().subject())) {
            return;
        }
        if (entry.get().subject() != null) {
            LOG.warn("A judicial caller's email matches a Master already linked to another account");
            return;
        }
        if (repository.subjectLinked(subject)) {
            LOG.warn("A judicial caller already linked to one Master has the email of another");
            return;
        }
        LocalDateTime now = AuditClock.now();
        if (repository.link(entry.get().id(), subject, now)) {
            audit.record(AuditEntry.of(AuditAction.MASTER_LINKED, AuditActor.system(), AuditEntities.MASTER,
                                       entry.get().id().toString(), null)
                             .withFields(List.of(LINKED)),
                         List.of());
        }
    }

    private static void refuseUnknown(Map<String, Object> fields, Set<String> keys) {
        for (String key : fields.keySet()) {
            if (!keys.contains(key)) {
                throw invalid(key, "Only name, email, available and, on a change, revision may be supplied.");
            }
        }
    }

    private static String name(Object value) {
        String name = value instanceof String text ? text.trim() : "";
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw invalid(NAME, "Enter the Master's name, at most " + MAX_NAME_LENGTH + " characters.");
        }
        return name;
    }

    /**
     * Trimmed and lower-cased; one {@code @} with something either side, no whitespace, at most 320.
     */
    static String email(Object value) {
        String email = value instanceof String text ? text.trim().toLowerCase(Locale.ROOT) : "";
        int at = email.indexOf('@');
        if (email.length() > MAX_EMAIL_LENGTH || at <= 0 || at != email.lastIndexOf('@')
            || at == email.length() - 1 || email.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(EMAIL, "Enter the Master's email address, like name@example.com.");
        }
        return email;
    }

    private static boolean available(Object value) {
        if (!(value instanceof Boolean available)) {
            throw invalid(AVAILABLE, "Say whether the Master is available, as true or false.");
        }
        return available;
    }

    private static UUID parseId(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static NotFoundError notFound() {
        return new NotFoundError("No such Master");
    }

    private static ConflictError duplicateEmail() {
        return new ConflictError(DUPLICATE_EMAIL, List.of(new FieldError(EMAIL, DUPLICATE_EMAIL)));
    }

    private static InvalidInputError invalid(String field, String message) {
        return new InvalidInputError(message, List.of(new FieldError(field, message)));
    }
}
