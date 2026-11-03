package uk.gov.hmcts.reform.deedpoll.domain.mi;

import org.springframework.jdbc.core.JdbcTemplate;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Hand-built cases and referrals for the management-information tests, written by SQL so a test states exactly the
 * columns the figures read.
 *
 * <p>The tests share one database with every other test class, some of which commit, so fixtures sit in 2189 and
 * 2190, where nothing else is dated. The figures that look across all time (the open caseload and the cases with no
 * {@code dateReceived}) are asserted as a difference from a baseline taken in the same transaction.
 */
final class MiFixtures {

    private final JdbcTemplate jdbc;

    MiFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static LocalDateTime at(int year, int month, int day) {
        return LocalDateTime.of(year, month, day, 0, 0);
    }

    static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    CaseBuilder newCase() {
        return new CaseBuilder();
    }

    String referral(String caseId, MasterDecision decision, LocalDateTime decisionDate, Urgency urgency,
                    ConfirmationOutcome outcome, LocalDateTime confirmationReceivedAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO deedpoll_referral (id, "deedPollId", "state", "urgency", "decision", "decisionDate",
                "confirmationOutcome", "confirmationReceivedAt", "modifiedOn")
            VALUES (CAST(? AS uuid), ?, CAST(? AS "ReferralState"), CAST(? AS "Urgency"), CAST(? AS "MasterDecision"),
                ?, CAST(? AS "ConfirmationOutcome"), ?, now())
            """, id, caseId, decisionDate == null ? "Open" : "Closed", urgency.name(), name(decision), decisionDate,
            name(outcome), confirmationReceivedAt);
        return id;
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    final class CaseBuilder {
        private DeedPollType type = DeedPollType.Adult;
        private boolean migrated;
        private LocalDateTime received;
        private LocalDateTime ready;
        private LocalDateTime referred;
        private LocalDateTime masterDateOfDecision;
        private LocalDateTime signatureRequested;
        private LocalDateTime signatureReceived;
        private LocalDateTime enroled;
        private LocalDateTime returned;

        CaseBuilder type(DeedPollType value) {
            type = value;
            return this;
        }

        CaseBuilder migrated() {
            migrated = true;
            return this;
        }

        CaseBuilder received(LocalDateTime value) {
            received = value;
            return this;
        }

        CaseBuilder ready(LocalDateTime value) {
            ready = value;
            return this;
        }

        CaseBuilder referred(LocalDateTime value) {
            referred = value;
            return this;
        }

        CaseBuilder masterDateOfDecision(LocalDateTime value) {
            masterDateOfDecision = value;
            return this;
        }

        CaseBuilder signatureRequested(LocalDateTime value) {
            signatureRequested = value;
            return this;
        }

        CaseBuilder signatureReceived(LocalDateTime value) {
            signatureReceived = value;
            return this;
        }

        CaseBuilder enroled(LocalDateTime value) {
            enroled = value;
            return this;
        }

        CaseBuilder returned(LocalDateTime value) {
            returned = value;
            return this;
        }

        String insert() {
            String id = UUID.randomUUID().toString();
            jdbc.update("""
                INSERT INTO deedpoll_deedpoll (id, "type", "migratedRecord", "dateReceived", "dateReadyForMaster",
                    "dateReferred", "masterDateOfDecision", "signatureRequestedDate", "signaturerecieveddate",
                    "dateEnroled", "dateReturned", "modifiedOn")
                VALUES (?, CAST(? AS "DeedPollType"), ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                """, id, name(type), migrated, received, ready, referred, masterDateOfDecision, signatureRequested,
                signatureReceived, enroled, returned);
            return id;
        }
    }
}
