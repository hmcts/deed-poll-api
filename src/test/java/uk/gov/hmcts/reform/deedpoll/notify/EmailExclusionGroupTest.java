package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exclusion groups, and the one fact about them that is written down twice.
 *
 * <p><strong>Container-free, and this is the class that makes it so.</strong> V20's index is the enforcement and
 * only {@code NotificationRepositoryTest} executes it -- against real Postgres, which CI has no Docker for. So the
 * agreement between {@link EmailExclusionGroup#exclusivePerCase()} and the migration's {@code IN} list is asserted
 * against the migration's <em>text</em> instead, where CI runs. The failure it catches is precise: a group added to
 * the enum as exclusive per case, with no index to enforce it, sends one applicant two deeds and nothing fails.
 */
class EmailExclusionGroupTest {

    /**
     * V20's SQL, read as text.
     *
     * <p>Read from {@code src/main/resources} rather than from the classpath, so a stale {@code build/} copy cannot
     * make this pass against a migration that is no longer what is committed.
     */
    private static final Path MIGRATION =
        Path.of("src/main/resources/db/migration/V20__notification_case_exclusion_group.sql");

    @Test
    @DisplayName("only the forms group is exclusive per case, which is a decision about each of the three")
    void statesWhichGroupsAreExclusivePerCase() {
        // Every group, not only the true one: what a reader needs is that each of these was decided rather than
        // defaulted. REFERRAL_OUTCOME is keyed on the referral, DOCUMENT_CHASE on nothing at all -- a second
        // reminder is the email's whole purpose -- and only the forms pair on the case.
        assertThat(EmailExclusionGroup.FORMS_FOR_COMPLETION.exclusivePerCase()).isTrue();
        assertThat(EmailExclusionGroup.REFERRAL_OUTCOME.exclusivePerCase()).isFalse();
        assertThat(EmailExclusionGroup.DOCUMENT_CHASE.exclusivePerCase()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(EmailExclusionGroup.class)
    @DisplayName("every case-exclusive group is named by V20's index, and no other group is")
    void theMigrationNamesExactlyTheCaseExclusiveGroups(EmailExclusionGroup group) {
        // The agreement in both directions, and both directions fail silently. A group the enum calls exclusive
        // but the index does not name is unenforced, so two contradictory emails reach one applicant. A group the
        // index names but the enum does not is worse in the other way: the insert is refused and the service has no
        // read that can explain it, so an ordinary conflict becomes a 500.
        String quoted = "'" + group.name() + "'";
        assertThat(inClause())
            .as("V20 %s name %s", group.exclusivePerCase() ? "must" : "must not", group.name())
            .satisfies(clause -> {
                if (group.exclusivePerCase()) {
                    assertThat(clause).contains(quoted);
                } else {
                    assertThat(clause).doesNotContain(quoted);
                }
            });
    }

    @Test
    @DisplayName("V20 keys on the case, is partial on rows with no referral, and carries V12's failure predicate")
    void theMigrationKeysOnTheCaseAndCarriesTheFailurePredicate() {
        String sql = migration();

        // The three parts of the index, each of which changes what it means. The key, because a group scoped to
        // anything wider would refuse a legitimate email on another case; the null-referral predicate, because
        // that is what keeps it disjoint from V15's; and V12's verdict clause, because notify-1 task 7's
        // replacement-after-permanent-refusal rule has to hold here too.
        assertThat(sql)
            .contains("CREATE UNIQUE INDEX \"deedpoll_notification_case_exclusion_group_key\"")
            .contains("ON \"deedpoll_notification\" (\"deedPollId\", \"exclusionGroup\")")
            .contains("WHERE \"referralId\" IS NULL")
            .contains("(\"state\" <> 'Failed' OR \"failureVerdict\" IS DISTINCT FROM 'PERMANENT')");
    }

    @ParameterizedTest
    @EnumSource(EmailTemplate.class)
    @DisplayName("every template declares a group, and a case-driven template's group decides whether it repeats")
    void everyTemplateDeclaresAGroup(EmailTemplate template) {
        assertThat(template.exclusionGroup()).isNotNull();
    }

    @Test
    @DisplayName("the two forms emails share a group, which is what makes them mutually exclusive")
    void bothFormsEmailsShareAGroup() {
        // Decision 8 as a fact about the enum, and the property V20's key acts on: a case gets one of them, and
        // which one follows from its type rather than from a caseworker's choice.
        assertThat(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.exclusionGroup())
            .isEqualTo(EmailExclusionGroup.FORMS_FOR_COMPLETION)
            .isEqualTo(EmailTemplate.FORMS_FOR_COMPLETION_MINOR.exclusionGroup())
            .isNotEqualTo(EmailTemplate.CHASE_MISSING_DOCUMENTS.exclusionGroup());
        // The member name is the value a row stores and the literal V20 names, so renaming it is a migration as
        // well as a data migration.
        assertThat(EmailExclusionGroup.FORMS_FOR_COMPLETION.name()).isEqualTo("FORMS_FOR_COMPLETION");
    }

    @Test
    @DisplayName("the clause is a quoted list, so the assertions above cannot pass on a partial match")
    void theInClauseIsAQuotedList() {
        // Guards the reading rather than the rule. The two assertions above are string containment, so they are
        // only as trustworthy as the shape of what they are given: a clause built some other way -- a subquery, an
        // unquoted identifier -- could satisfy "contains 'FORMS_FOR_COMPLETION'" while enforcing something else.
        assertThat(inClause().strip()).matches("'[A-Z_]+'(, *'[A-Z_]+')*");
    }

    /**
     * The {@code IN (...)} list of V20's index predicate, and nothing else in the file.
     *
     * <p>Sliced out rather than searching the whole migration, because the file's comments explain the decision at
     * length and name every group in doing so -- so a test that grepped the file would be green for a group the
     * index does not enforce.
     */
    private static String inClause() {
        String sql = migration();
        int start = sql.indexOf("\"exclusionGroup\" IN (");
        assertThat(start).as("V20 names the groups its index applies to in an IN clause").isNotNegative();
        int open = sql.indexOf('(', start);
        int close = sql.indexOf(')', open);
        assertThat(close).as("the IN clause is closed").isGreaterThan(open);
        return sql.substring(open + 1, close);
    }

    /**
     * V20 s text, with comment lines dropped -- {@link #inClause} is what narrows it further.
     */
    private static String migration() {
        try {
            List<String> lines = Files.readAllLines(MIGRATION);
            // Comments dropped here as well as narrowed later, so `migration()` is safe for the structural
            // assertions above: a commented-out CREATE INDEX must not satisfy them.
            return Arrays.stream(lines.toArray(String[]::new))
                .filter(line -> !line.stripLeading().startsWith("--"))
                .reduce("", (all, line) -> all + line + "\n");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("V20 could not be read from " + MIGRATION, unreadable);
        }
    }
}
