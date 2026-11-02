package uk.gov.hmcts.reform.deedpoll.domain.settings;

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
import uk.gov.hmcts.reform.deedpoll.repository.BankHolidayRepository;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * England and Wales bank holidays (plan D1, decision D-7): the referral windows and Master reminders run on working
 * days only.
 *
 * <p>V42 seeds GOV.UK's dates for 2026 to 2028, and a Delivery Manager ({@link Capability#MANAGE_PROCESS_CONFIG})
 * adds or removes one; nothing calls the internet. Any staff caller may read the list. Nothing is cached, so D2 and
 * D3 see a change on their next decision. Audited by field name only, as the process settings are.
 */
@Service
public class BankHolidays {

    private static final Set<DayOfWeek> WEEKEND = EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    public static final int MAX_NAME_LENGTH = 200;

    public static final String DATE = "date";
    public static final String NAME = "name";

    /**
     * Whoever {@code StaffArea.CASEWORKER} admits.
     */
    @SuppressWarnings("deprecation")
    private static final Role[] STAFF = {Role.CASEWORKER, Role.TEAM_LEADER, Role.DELIVERY_MANAGER,
        Role.DEEDPOLL_ADMIN};

    private static final Set<String> CREATE_KEYS = Set.of(DATE, NAME);

    private final BankHolidayRepository repository;
    private final AuditTrail audit;

    public BankHolidays(BankHolidayRepository repository, AuditTrail audit) {
        this.repository = repository;
        this.audit = audit;
    }

    /** Monday to Friday, and not a listed bank holiday. No capability check: D2 and D3 ask it per decision. */
    @Transactional(readOnly = true)
    public boolean isWorkingDay(LocalDate date) {
        return !WEEKEND.contains(date.getDayOfWeek()) && !repository.listed(date);
    }

    /** Every listed date, earliest first. */
    @Transactional(readOnly = true)
    public List<BankHoliday> list(CurrentUser caller) {
        caller.requireAnyRole(STAFF);
        return repository.list();
    }

    /**
     * {@code date} ({@code yyyy-MM-dd}) and {@code name}, both required. A date already listed is 409.
     */
    @Transactional
    public BankHoliday add(CurrentUser caller, Map<String, Object> fields) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        final String subject = caller.requireUserId();
        for (String key : fields.keySet()) {
            if (!CREATE_KEYS.contains(key)) {
                throw invalid(key, "Only date and name may be supplied.");
            }
        }
        LocalDate date = fields.get(DATE) instanceof String text ? parse(text) : null;
        if (date == null) {
            throw invalid(DATE, "Enter the date as yyyy-MM-dd, like 2029-01-01.");
        }
        String name = fields.get(NAME) instanceof String text ? text.trim() : "";
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw invalid(NAME, "Enter the bank holiday's name, at most " + MAX_NAME_LENGTH + " characters.");
        }
        if (!repository.insert(date, name, AuditClock.now(), subject, caller.name())) {
            String message = "This date is already a bank holiday.";
            throw new ConflictError(message, List.of(new FieldError(DATE, message)));
        }
        record(AuditAction.BANK_HOLIDAY_ADDED, caller, date);
        return new BankHoliday(date, name);
    }

    /** Removes a listed date; one that is not listed, or is no date at all, is 404. */
    @Transactional
    public void remove(CurrentUser caller, String date) {
        caller.require(Capability.MANAGE_PROCESS_CONFIG);
        caller.requireUserId();
        LocalDate parsed = parse(date);
        if (parsed == null || !repository.delete(parsed)) {
            throw new NotFoundError("No such bank holiday");
        }
        record(AuditAction.BANK_HOLIDAY_REMOVED, caller, parsed);
    }

    private void record(AuditAction action, CurrentUser caller, LocalDate date) {
        audit.record(AuditEntry.of(action, AuditActor.of(caller), AuditEntities.BANK_HOLIDAY, date.toString(), null)
                         .withFields(List.of(DATE, NAME)),
                     List.of());
    }

    private static LocalDate parse(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            return null;
        }
    }

    private static InvalidInputError invalid(String field, String message) {
        return new InvalidInputError(message, List.of(new FieldError(field, message)));
    }
}
