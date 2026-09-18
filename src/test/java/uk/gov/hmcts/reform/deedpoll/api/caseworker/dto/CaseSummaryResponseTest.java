package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ComputedFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferralState;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one row of the case list carries, and that BOTH factories carry it.
 *
 * <p>{@link CaseSummaryResponse} has two entry points -- one case and a list of them -- and they are not
 * independent: the list one delegates. That is exactly the arrangement in which a field added to the record and
 * mapped in only one of them compiles, because a record's canonical constructor is the only mandatory caller.
 * So both are exercised on the same populated case and asserted to answer the same row.
 *
 * <p>{@code currentReferralState} is the field this test was written for. It is the column the "Returned --
 * action needed" queue filters on, so a row missing it renders a list a caseworker cannot read the reason out of,
 * and its null case is the ordinary one -- most cases have never been referred -- which is why the null is
 * asserted as deliberately as the value.
 */
class CaseSummaryResponseTest {

    private static final LocalDateTime RECEIVED = LocalDateTime.of(2026, 2, 3, 9, 0);

    @Test
    @DisplayName("a row carries the list columns, the searched surname and the current referral state")
    void rowCarriesTheListColumnsAndTheCurrentReferralState() {
        CaseSummaryResponse row = CaseSummaryResponse.from(referredCase());

        assertThat(row.id()).isEqualTo("case-1");
        assertThat(row.displayReference()).isEqualTo("KBDR2026-7");
        assertThat(row.privacy()).isEqualTo(Privacy.Private);
        assertThat(row.dateReceived()).isEqualTo(RECEIVED);
        assertThat(row.type()).isEqualTo(DeedPollType.DeedOfRelinquishment);
        assertThat(row.state()).isEqualTo(DeedPollState.ReferredToMaster);
        assertThat(row.newForename()).isEqualTo("Ada");
        assertThat(row.newSurname()).isEqualTo("Threepwood");
        assertThat(row.currentReferralState()).isEqualTo(ReferralState.Decided);
    }

    @Test
    @DisplayName("the list factory answers the same row as the single one, so neither can map a field the other drops")
    void theListFactoryAnswersTheSameRow() {
        DeedPoll record = referredCase();

        List<CaseSummaryResponse> rows =
            CaseSummaryResponse.from(List.of(new DeedPollWithComputed(record, new ComputedFields(11, 22))));

        assertThat(rows).containsExactly(CaseSummaryResponse.from(record));
    }

    @Test
    @DisplayName("a case that was never referred has a null current referral state, not a state standing in for one")
    void caseNeverReferredHasNoCurrentReferralState() {
        DeedPoll record = new DeedPoll();
        record.setId("case-2");
        record.setState(DeedPollState.Received);

        assertThat(CaseSummaryResponse.from(record).currentReferralState())
            .as("null is the answer for the great majority of cases, and is distinct from any live state")
            .isNull();
    }

    private static DeedPoll referredCase() {
        DeedPoll record = new DeedPoll();
        record.setId("case-1");
        record.setDisplayReference("KBDR2026-7");
        record.setPrivacy(Privacy.Private);
        record.setDateReceived(RECEIVED);
        record.setType(DeedPollType.DeedOfRelinquishment);
        record.setState(DeedPollState.ReferredToMaster);
        record.setNewForename("Ada");
        record.setNewSurname("Threepwood");
        record.setCurrentReferralState(ReferralState.Decided);
        return record;
    }
}
