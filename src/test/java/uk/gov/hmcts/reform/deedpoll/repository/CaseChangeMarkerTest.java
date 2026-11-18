package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V50's change marker leaves reads out by a literal list of action names, in its trigger and in its backfill; the Java
 * side states the same fact as {@link AuditAction#isRead}. A read action added to the enum alone would move
 * {@code lastChangedOn} whenever somebody opened the case, so every review would go stale; one left in the list after
 * a rename would let that write through unnoticed. Docker-free, so a local run without Postgres still checks it.
 */
class CaseChangeMarkerTest {

    private static final Path MIGRATION = Path.of(
        "src/main/resources/db/migration/V50__application_deletion_change_marker_and_removed_documents.sql");
    private static final Pattern READ_LIST = Pattern.compile("action (?:NOT )?IN \\(([^)]*)\\)");

    @Test
    @DisplayName("V50's read actions are exactly AuditAction's isRead members, in the trigger and the backfill")
    void readActionsAgree() throws IOException {
        List<String> reads = Arrays.stream(AuditAction.values()).filter(AuditAction::isRead).map(Enum::name).sorted()
            .toList();
        Matcher lists = READ_LIST.matcher(Files.readString(MIGRATION));
        int found = 0;
        while (lists.find()) {
            found++;
            List<String> listed = Arrays.stream(lists.group(1).split(","))
                .map(name -> name.trim().replace("'", "")).sorted().toList();
            assertThat(listed).isEqualTo(reads);
        }
        assertThat(found).as("the trigger's list and the backfill's").isEqualTo(2);
    }
}
