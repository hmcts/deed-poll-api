package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.masters.Master;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * V42's {@code deedpoll_master}. The IDAM subject is written here and compared here, and read out only as the
 * {@link Linking} the linking rule needs, never onto a {@link Master}.
 */
@Repository
public class MasterRepository {

    // Compile-time constants, one per variant, so no statement is assembled at runtime.
    private static final String SELECT = "SELECT id, name, email, available, idam_subject IS NOT NULL AS linked, "
        + "revision FROM deedpoll_master";
    private static final String LIST = SELECT + " ORDER BY name, id";
    private static final String FIND = SELECT + " WHERE id = ?";
    private static final String FIND_FOR_UPDATE = SELECT + " WHERE id = ? FOR UPDATE";

    private static final RowMapper<Master> MASTER = (row, index) -> new Master(row.getObject("id", UUID.class)
        .toString(), row.getString("name"), row.getString("email"), row.getBoolean("available"),
        row.getBoolean("linked"), row.getInt("revision"));

    private final JdbcTemplate jdbc;

    public MasterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Who an entry is linked to, for the linking rule alone. */
    public record Linking(UUID id, String subject) {
    }

    /**
     * What an assignment needs of an entry: its name for {@code claimedBy}, whether it may be assigned, and the
     * linked subject for {@code claimedById}. The subject goes no further than the referral row.
     */
    public record Assignee(String name, boolean available, String subject) {
    }

    /**
     * The entry, locked until the caller's transaction ends, so two assignments to one Master serialise and the
     * per-Master cap's count is read after the other has committed. Always taken after the referral's own lock.
     */
    public Optional<Assignee> findAssigneeForUpdate(UUID id) {
        return jdbc.query("SELECT name, available, idam_subject FROM deedpoll_master WHERE id = ? FOR UPDATE",
                          (row, index) -> new Assignee(row.getString("name"), row.getBoolean("available"),
                                                       row.getString("idam_subject")),
                          id).stream().findFirst();
    }

    /** The entry, unlocked: whom a Master email goes to, at send time. */
    public Optional<Master> find(UUID id) {
        return jdbc.query(FIND, MASTER, id).stream().findFirst();
    }

    public List<Master> list() {
        return jdbc.query(LIST, MASTER);
    }

    /** The entry, locked until the caller's transaction ends, so two edits of it serialise on the revision. */
    public Optional<Master> findForUpdate(UUID id) {
        return jdbc.query(FIND_FOR_UPDATE, MASTER, id).stream().findFirst();
    }

    /** Whether another entry holds this address, in any case. */
    public boolean emailTaken(String email, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_master WHERE lower(email) = lower(?) AND id IS DISTINCT FROM ?)",
            Boolean.class, email, except));
    }

    public void insert(UUID id, String name, String email, boolean available, LocalDateTime at, String byId,
                       String byName) {
        jdbc.update("""
            INSERT INTO deedpoll_master (id, name, email, available, created_at, created_by_id, created_by_name,
                revision)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """, id, name, email, available, at, byId, byName);
    }

    /**
     * The whole editable row; {@code unlink} clears the account, as an email change must.
     */
    public void update(UUID id, String name, String email, boolean available, boolean unlink, int revision,
                       LocalDateTime at, String byId, String byName) {
        jdbc.update("""
            UPDATE deedpoll_master SET name = ?, email = ?, available = ?,
                idam_subject = CASE WHEN ? THEN NULL ELSE idam_subject END,
                linked_at = CASE WHEN ? THEN NULL ELSE linked_at END,
                revision = ?, updated_at = ?, updated_by_id = ?, updated_by_name = ?
            WHERE id = ?
            """, name, email, available, unlink, unlink, revision, at, byId, byName, id);
    }

    /** The entry holding this address, in any case, with whoever it is linked to. */
    public Optional<Linking> linkingByEmail(String email) {
        return jdbc.query("SELECT id, idam_subject FROM deedpoll_master WHERE lower(email) = lower(?)",
                          (row, index) -> new Linking(row.getObject("id", UUID.class), row.getString("idam_subject")),
                          email).stream().findFirst();
    }

    /** Whether any entry is already linked to this account. */
    public boolean subjectLinked(String subject) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_master WHERE idam_subject = ?)", Boolean.class, subject));
    }

    /**
     * Links an unlinked entry, conditionally, so of two racing requests one writes and the other matches nothing.
     * The email is checked again in the same statement, so an edit that changed the address since it was read leaves
     * the entry unlinked rather than bound to the old address's account.
     *
     * @return whether this call linked it
     */
    public boolean link(UUID id, String email, String subject, LocalDateTime at) {
        return jdbc.update("UPDATE deedpoll_master SET idam_subject = ?, linked_at = ? "
                               + "WHERE id = ? AND idam_subject IS NULL AND lower(email) = lower(?)",
                           subject, at, id, email) == 1;
    }
}
