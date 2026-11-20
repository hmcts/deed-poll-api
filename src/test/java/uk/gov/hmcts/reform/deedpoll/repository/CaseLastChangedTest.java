package uk.gov.hmcts.reform.deedpoll.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.audit.AuditAction;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CaseLastChanged}'s statement is a constant, so its read actions are written out: these hold them to
 * {@link AuditAction}'s {@code isRead} members, so a new read action cannot silently make a review stale.
 */
class CaseLastChangedTest {

    @Test
    @DisplayName("the read actions written out are exactly AuditAction's read members")
    void readActionsAreAuditActionsReadMembers() {
        assertThat(CaseLastChanged.READ_ACTIONS).containsExactlyInAnyOrderElementsOf(
            Arrays.stream(AuditAction.values()).filter(AuditAction::isRead).map(AuditAction::name).toList());
    }

    @Test
    @DisplayName("the statement excludes every read action, each quoted once")
    void statementExcludesEveryReadAction() {
        for (String action : CaseLastChanged.READ_ACTIONS) {
            assertThat(CaseLastChanged.sql()).containsOnlyOnce("'" + action + "'");
        }
    }
}
