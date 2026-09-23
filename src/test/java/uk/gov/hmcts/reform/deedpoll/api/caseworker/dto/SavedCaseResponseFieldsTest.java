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
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link SavedCaseResponse} publishes, and that each field carries its own value.
 *
 * <p>The same two questions {@link CaseDetailResponseFieldsTest} asks of the read response, for the same reason
 * -- seventy-eight positional arguments, thirty-five of them {@code String} -- with one addition that matters
 * more here than there.
 *
 * <p><strong>The field set is pinned against {@code CaseDetailResponse}'s, not only against
 * {@link DeedPollFields}.</strong> These two records publish the same case to the same consumer, and the whole
 * of the intended difference is the three child collections. Asserting that difference exactly is what stops
 * them drifting: a column published on a read and forgotten on a write would otherwise leave a caseworker
 * client with a field that appears and disappears depending on which call last returned it, and each record's
 * own coverage test would stay green while it happened.
 */
class SavedCaseResponseFieldsTest {

    @Test
    @DisplayName("publishes exactly the case's readable fields and its derived counts, and no children")
    void publishesExactlyTheReadableFieldsAndNoChildren() {
        Set<String> expected = new LinkedHashSet<>(DeedPollFields.readAll(new DeedPoll()).keySet());
        expected.addAll(CaseResponseFields.DERIVED);

        assertThat(CaseResponseFields.componentNames(SavedCaseResponse.class))
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("is the detail response minus the three child collections, exactly")
    void isTheDetailResponseMinusTheChildren() {
        List<String> detail = CaseResponseFields.componentNames(CaseDetailResponse.class);
        List<String> saved = CaseResponseFields.componentNames(SavedCaseResponse.class);

        // Order too, not just membership: both records are read positionally by their own from() methods, and a
        // reordering that a set comparison would accept is exactly the kind of edit that transposes two fields.
        assertThat(saved).containsExactlyElementsOf(
            detail.stream().filter(name -> !CaseResponseFields.CHILDREN.contains(name)).toList());
        assertThat(saved).doesNotContainAnyElementsOf(CaseResponseFields.CHILDREN);
    }

    @Test
    @DisplayName("does not publish the children as empty lists, which would be a claim about a case it did not read")
    void doesNotPublishEmptyChildCollections() {
        // A write does not fetch the children, so it has nothing true to say about them. Publishing them empty
        // would tell a caller patching a case with three notes that it has none -- and it is the plausible
        // mistake, since a fresh entity's collections really are empty and the code would look right.
        DeedPoll record = new DeedPoll();
        record.setId("case-1");

        SavedCaseResponse published =
            SavedCaseResponse.from(new DeedPollWithComputed(record, new ComputedFields(null, null)));

        assertThat(published.getClass().getRecordComponents()).noneMatch(
            component -> CaseResponseFields.CHILDREN.contains(component.getName()));
        assertThat(published.id()).isEqualTo("case-1");
    }

    @ParameterizedTest(name = "flags {0} {1} {2} {3}")
    @CsvSource({"true,true,false,false", "true,false,true,false"})
    @DisplayName("maps every field to its own value, so no two are transposed")
    void mapsEveryFieldToItsOwnValue(boolean first, boolean second, boolean third, boolean fourth)
        throws Exception {

        CaseResponseFields.Populated populated =
            CaseResponseFields.populate(SavedCaseResponse.class, first, second, third, fourth);

        SavedCaseResponse published = SavedCaseResponse.from(
            new DeedPollWithComputed(populated.record(), new ComputedFields(11, 22)));

        CaseResponseFields.assertCarriesItsOwnValue(
            SavedCaseResponse.class, published, populated.expected());

        assertThat(published.daysReceivedToEnroled()).isEqualTo(11);
        assertThat(published.daysReceivedToReturned()).isEqualTo(22);
    }
}
