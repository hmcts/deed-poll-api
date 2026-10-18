package uk.gov.hmcts.reform.deedpoll.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Upgrade populated history, including the same case in more than one old batch. */
@Tag("requires-docker")
@Testcontainers
class GazetteLedgerUpgradeTest {
    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void preservesHistoricalEvidenceAndBackfillsOnePermanentReservationPerCase() {
        String url = ContainerDatabase.awaitJdbcUrl(POSTGRES);
        var source = new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("33").load().migrate();
        var jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO deedpoll_deedpoll (id, \"modifiedOn\") VALUES ('one', now()), ('two', now())");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch VALUES (?, '2026-01-01', 'subject', 'Original', ?, 2)",
            first, UUID.randomUUID());
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch VALUES (?, '2026-02-01', 'subject', 'Later', ?, 1)",
            second, UUID.randomUUID());
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch_case "
            + "VALUES (?, 'one', 'old-one'), (?, 'two', 'old-two')",
            first, first);
        jdbc.update("INSERT INTO deedpoll_gazette_notice_batch_case VALUES (?, 'one', 'later-one')", second);
        var batches = jdbc.queryForList("SELECT * FROM deedpoll_gazette_notice_batch ORDER BY id");
        final var members = jdbc.queryForList(
            "SELECT * FROM deedpoll_gazette_notice_batch_case ORDER BY batch_id, deed_poll_id");
        var upgrade = Flyway.configure().dataSource(source).locations("classpath:db/migration").target("34").load();
        assertThat(upgrade.migrate().migrationsExecuted).isOne();
        upgrade.validate();
        assertThat(jdbc.queryForList("SELECT * FROM deedpoll_gazette_notice_batch ORDER BY id")).isEqualTo(batches);
        assertThat(jdbc.queryForList(
            "SELECT * FROM deedpoll_gazette_notice_batch_case ORDER BY batch_id, deed_poll_id"))
            .isEqualTo(members);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_gazette_request", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT sent_batch_id FROM deedpoll_gazette_reservation ORDER BY deed_poll_id",
            UUID.class)).containsExactly(first, first);
        assertThat(jdbc.queryForObject(
            "SELECT count(request_batch_id) FROM deedpoll_gazette_reservation", Integer.class))
            .isZero();
        assertThatThrownBy(() -> jdbc.update("DELETE FROM deedpoll_deedpoll WHERE id = 'one'"))
            .isInstanceOf(DataAccessException.class);
        assertThat(upgrade.migrate().migrationsExecuted).isZero();
    }
}
