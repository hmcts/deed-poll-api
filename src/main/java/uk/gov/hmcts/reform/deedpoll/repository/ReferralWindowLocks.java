package uk.gov.hmcts.reform.deedpoll.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import uk.gov.hmcts.reform.deedpoll.entity.enums.WindowSlot;

import java.time.LocalDate;

/**
 * The batch send's window lock (plan F1): a transaction-scoped exclusive advisory lock on one referral window, so two
 * sends in a window serialise and the second counts what the first committed. The cap holds under concurrency by this
 * lock alone; it is taken before any row lock, so it cannot join a row-lock cycle.
 */
@Repository
public class ReferralWindowLocks {

    private final JdbcTemplate jdbc;

    public ReferralWindowLocks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Waits for, then holds until the caller's transaction ends, the lock on this window's sends. */
    public void lockForSending(LocalDate windowDate, WindowSlot windowSlot) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('deedpoll-referral-send'), hashtext(?))",
                   row -> { }, windowDate + "-" + windowSlot.name());
    }
}
