package uk.gov.hmcts.reform.deedpoll.api.caseworker.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.ComputedFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollFields;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollWithComputed;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link CaseDetailResponse} publishes, and that each field carries its own value.
 *
 * <p>Two questions, and both are read off the code rather than transcribed from it -- the habit task 11
 * established, because a hand-copied field list only agrees with its source on the day it is written.
 *
 * <ul>
 *   <li><strong>Coverage, in both directions.</strong> The published set is exactly
 *       {@link DeedPollFields}' readable names plus the derived counts and the three child collections. A
 *       column added to the case therefore has to be published deliberately or omitted deliberately; neither
 *       can happen by not noticing. And a field that should not be on the caseworker surface cannot appear
 *       here without the test saying so, which is the disclosure direction.</li>
 *   <li><strong>Correspondence.</strong> The canonical constructor takes eighty positional arguments, of
 *       which thirty-five are {@code String}: two transposed by a careless edit compile silently and swap two
 *       fields on every response. So every field is populated with a value unique to it and the response is
 *       compared field by field.</li>
 * </ul>
 *
 * <p>Uniqueness is what makes the second assertion work, and the four flags are the awkward case -- four
 * fields and two possible values cannot all differ in one run. Hence two runs whose vectors give each flag a
 * distinct pair: a swap between any two of them changes the answer in at least one of the runs.
 *
 * <p>The populating and the value-per-type choices are {@link CaseResponseFields}, shared with
 * {@link SavedCaseResponseFieldsTest} so the write response is checked exactly as carefully as this one.
 */
class CaseDetailResponseFieldsTest {

    @Test
    @DisplayName("publishes exactly the case's readable fields, its derived counts and its children")
    void publishesExactlyTheReadableFields() {
        Set<String> expected = new LinkedHashSet<>(DeedPollFields.readAll(new DeedPoll()).keySet());
        expected.addAll(CaseResponseFields.DERIVED);
        expected.addAll(CaseResponseFields.CHILDREN);

        assertThat(CaseResponseFields.componentNames(CaseDetailResponse.class))
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("does not publish the citizen-journey columns, which no caseworker form carries")
    void doesNotPublishTheCitizenJourneyColumns() {
        // Real columns on deedpoll_deedpoll, and deliberately not readable through DeedPollFields: they
        // belong to the public application journey, and the reference's case schema does not accept them
        // either. Named explicitly rather than left to the coverage assertion above, because "the DTO does
        // not leak the applicant's place of birth to a caseworker screen that never shows it" is the claim
        // worth being able to point at.
        assertThat(CaseResponseFields.componentNames(CaseDetailResponse.class)).doesNotContain(
            "placeOfBirth",
            "nationalitySubject",
            "nationalitySectionProvenance",
            "parentRelationship",
            "parentAddress1",
            "parentAddress2",
            "parentTownCity",
            "parentPostcode",
            "parentCountry",
            "secondParentName",
            "secondParentRelationship");
    }

    @Test
    @DisplayName("the eight fields a caller cannot patch are exactly the ones the fixture sets directly")
    void theUnwritableFieldsAreTheExpectedEight() {
        // Pins the split the correspondence test relies on. Were a field to become writable, or another to
        // stop being, the fixture's setter branch would go stale and the coverage it claims would shrink
        // quietly.
        Set<String> unwritable = new LinkedHashSet<>(DeedPollFields.readAll(new DeedPoll()).keySet());
        unwritable.removeIf(DeedPollFields::isWritable);

        assertThat(unwritable).containsExactlyInAnyOrderElementsOf(CaseResponseFields.NOT_WRITABLE_BY_A_CALLER);
    }

    @ParameterizedTest(name = "flags {0} {1} {2} {3}")
    @CsvSource({"true,true,false,false", "true,false,true,false"})
    @DisplayName("maps every field to its own value, so no two are transposed")
    void mapsEveryFieldToItsOwnValue(boolean first, boolean second, boolean third, boolean fourth)
        throws Exception {

        CaseResponseFields.Populated populated =
            CaseResponseFields.populate(CaseDetailResponse.class, first, second, third, fourth);

        CaseDetailResponse published = CaseDetailResponse.from(
            new DeedPollWithComputed(populated.record(), new ComputedFields(11, 22)));

        CaseResponseFields.assertCarriesItsOwnValue(
            CaseDetailResponse.class, published, populated.expected());

        assertThat(published.daysReceivedToEnroled()).isEqualTo(11);
        assertThat(published.daysReceivedToReturned()).isEqualTo(22);
        assertThat(published.notes()).isEmpty();
        assertThat(published.witnesses()).isEmpty();
        assertThat(published.documents()).isEmpty();
    }
}
