package uk.gov.hmcts.reform.deedpoll.storage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.config.AbstractPostgresContainerTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Execute the operator's actual aggregate queries against migrated Postgres, with rollback-only fixtures. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CdamOperationalQueriesTest extends AbstractPostgresContainerTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void emptyBacklogReportsZeroRatherThanNull() throws IOException {
        jdbc.update("DELETE FROM deedpoll_stagedpayload");
        var queries = queries();
        assertThat(queries).hasSize(4);
        assertThat(number(jdbc.queryForMap(queries.get(0)), "retained_bytes")).isZero();
        var preparations = jdbc.queryForMap(queries.get(1));
        assertThat(number(preparations, "preparations")).isZero();
        assertThat(number(preparations, "oldest_seconds")).isZero();
        assertThat(jdbc.queryForList(queries.get(2))).isEmpty();
        assertThat(number(jdbc.queryForMap(queries.get(3)), "overdue_seconds")).isZero();
    }

    @Test
    void expiredAndProtectedPayloadsCountButReleasedTombstonesDoNot() throws IOException {
        jdbc.update("DELETE FROM deedpoll_stagedpayload");
        stage("Staged", true);
        stage("Promoting", true);
        stage("Expired", false);
        var queries = queries();
        var retained = jdbc.queryForMap(queries.get(0));
        assertThat(number(retained, "retained_payloads")).isEqualTo(2);
        assertThat(number(retained, "retained_bytes")).isEqualTo(6);
        var groups = jdbc.queryForList(queries.get(2));
        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(row -> row.get("state")).containsExactlyInAnyOrder("Staged", "Promoting");
        for (var group : groups) {
            assertThat(group.keySet()).containsExactlyInAnyOrder(
                "ownerKind", "state", "stages", "retained_bytes", "oldest_seconds");
            assertThat(number(group, "oldest_seconds")).isGreaterThanOrEqualTo(7200);
        }
        var expired = jdbc.queryForMap(queries.get(3));
        assertThat(number(expired, "expired_backlog")).isEqualTo(1);
        assertThat(number(expired, "overdue_seconds")).isGreaterThanOrEqualTo(3600);
    }

    private void stage(String state, boolean retained) {
        String id = UUID.randomUUID().toString();
        boolean promoting = state.equals("Promoting");
        jdbc.update("""
            INSERT INTO deedpoll_ownedstage
              (id, "ownerKind", "ownerId", subject, "operationId", "originalName", "mimeType", "sizeBytes",
               checksum, "createdOn", "expiresOn", state, "caseId", "documentId", "documentTypeId")
            VALUES (?, 'RECORD', ?, 'synthetic-subject', ?, 'synthetic.pdf', 'application/pdf', 3,
                    encode(sha256(decode('010203', 'hex')), 'hex'),
                    CURRENT_TIMESTAMP - interval '2 hours', CURRENT_TIMESTAMP - interval '1 hour', ?, ?, ?, ?)
            """, id, id, UUID.randomUUID(), state, promoting ? id : null,
            promoting ? id : null, promoting ? id : null);
        if (retained) {
            jdbc.update("""
                INSERT INTO deedpoll_stagedpayload (id, content, "sizeBytes", checksum)
                VALUES (?, decode('010203', 'hex'), 3, encode(sha256(decode('010203', 'hex')), 'hex'))
                """, id);
        }
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }

    private static List<String> queries() throws IOException {
        String runbook = Files.readString(Path.of("docs/CDAM-RECOVERY.md"));
        String sql = runbook.split("```sql\\n", 2)[1].split("```", 2)[0];
        return Arrays.stream(sql.split(";"))
            .map(String::strip).filter(statement -> statement.startsWith("SELECT")).toList();
    }
}
