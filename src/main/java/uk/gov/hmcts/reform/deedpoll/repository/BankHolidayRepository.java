package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.domain.settings.BankHoliday;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * V42's {@code deedpoll_bank_holiday}, seeded with GOV.UK's England and Wales dates for 2026 to 2028.
 */
@Repository
public class BankHolidayRepository {

    private static final RowMapper<BankHoliday> HOLIDAY = (row, index) ->
        new BankHoliday(row.getObject("holiday_date", LocalDate.class), row.getString("name"));

    private final JdbcTemplate jdbc;

    public BankHolidayRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BankHoliday> list() {
        return jdbc.query("SELECT holiday_date, name FROM deedpoll_bank_holiday ORDER BY holiday_date", HOLIDAY);
    }

    public boolean listed(LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM deedpoll_bank_holiday WHERE holiday_date = ?)", Boolean.class, date));
    }

    /**
     * Adds a date unless it is already listed, so two racing adds insert once and neither raises.
     *
     * @return whether this call added it
     */
    public boolean insert(LocalDate date, String name, LocalDateTime at, String byId, String byName) {
        return jdbc.update("""
            INSERT INTO deedpoll_bank_holiday (holiday_date, name, created_at, created_by_id, created_by_name)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (holiday_date) DO NOTHING
            """, date, name, at, byId, byName) == 1;
    }

    /**
     * Removes a date if it is listed.
     *
     * @return whether this call removed it
     */
    public boolean delete(LocalDate date) {
        return jdbc.update("DELETE FROM deedpoll_bank_holiday WHERE holiday_date = ?", date) == 1;
    }
}
