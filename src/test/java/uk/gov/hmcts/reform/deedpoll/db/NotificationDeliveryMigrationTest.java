package uk.gov.hmcts.reform.deedpoll.db;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class NotificationDeliveryMigrationTest extends AbstractPostgresContainerTest {
    @Autowired private JdbcTemplate jdbc;

    @Test
    void onlyAttemptedPendingLegacyRowsAreParkedAndHistoryIsPreserved() throws Exception {
        jdbc.execute("CREATE SCHEMA delivery_migration");
        jdbc.execute("""
            CREATE TABLE delivery_migration.deedpoll_notification
                (LIKE public.deedpoll_notification INCLUDING ALL);
            ALTER TABLE delivery_migration.deedpoll_notification
                DROP COLUMN "claimOwner", DROP COLUMN "sendStartedAt";
            SET LOCAL search_path TO delivery_migration, public;
            """);
        final UUID untouched = legacy("Pending", 0);
        UUID ambiguous = legacy("Pending", 1);
        UUID safeRefusal = legacy("Pending", 3);
        final UUID sent = legacy("Sent", 1);
        final UUID failed = legacy("Failed", 8);
        jdbc.update("""
            UPDATE deedpoll_notification SET "failureVerdict" = 'TEMPORARY', "failureReason" = 'prior refusal'
             WHERE id = ?
            """, safeRefusal);
        jdbc.execute(new ClassPathResource("db/migration/V28__notification_delivery_claims.sql")
            .getContentAsString(StandardCharsets.UTF_8));
        assertThat(jdbc.queryForList("""
            SELECT id FROM deedpoll_notification WHERE "sendStartedAt" IS NOT NULL
            """, UUID.class)).containsExactlyInAnyOrder(ambiguous, safeRefusal);
        assertThat(jdbc.queryForList("SELECT id FROM deedpoll_notification WHERE \"claimOwner\" IS NOT NULL",
                                     UUID.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM deedpoll_notification", Integer.class)).isEqualTo(5);
        assertThat(state(untouched)).isEqualTo("Pending");
        assertThat(state(ambiguous)).isEqualTo("Pending");
        assertThat(state(sent)).isEqualTo("Sent");
        assertThat(state(failed)).isEqualTo("Failed");
        assertThat(jdbc.queryForObject("SELECT \"failureReason\" FROM deedpoll_notification WHERE id = ?",
                                      String.class, safeRefusal)).isEqualTo("prior refusal");
    }

    private String state(UUID id) {
        return jdbc.queryForObject("SELECT state::text FROM deedpoll_notification WHERE id = ?", String.class, id);
    }

    private UUID legacy(String state, int attempts) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO deedpoll_notification
                (id, "deedPollId", template, "exclusionGroup", recipient, personalisation, state,
                 attempts, "createdBy", "createdOn", "modifiedOn", "nextAttemptAt")
            VALUES (?, 'synthetic-case', 'CHASE_MISSING_DOCUMENTS', 'DOCUMENT_CHASE', 'test@example.com',
                    '{}', CAST(? AS "NotificationState"), ?, 'Caseworker', now(), now(), now())
            """, id, state, attempts);
        return id;
    }
}
