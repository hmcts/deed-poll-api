package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.enums.DynamicsValues;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentSchemas;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a generated deed is filed as, and who each deed is for.
 *
 * <p>Container-free: it is a statement about two enums and a name, and the case-type lists are the part of it
 * that must not drift, so they are asserted where CI runs.
 */
class DeedDocumentTypesTest {

    @Test
    @DisplayName("files each deed under its own type, named as the court and the caseworker both name it")
    void namesEachType() {
        assertThat(DeedDocumentTypes.name(DeedTemplate.ADULT)).isEqualTo("LOC020 Change of name deed");
        assertThat(DeedDocumentTypes.name(DeedTemplate.MINOR))
            .isEqualTo("LOC022 Change of name deed for a child");
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("every type name fits the column it is stored in")
    void everyTypeNameFitsItsColumn(DeedTemplate template) {
        // DocumentService caps an uploaded document's name; nothing caps a type's, so a name longer than the
        // column would be a constraint violation on the first deed of a fresh environment.
        assertThat(DeedDocumentTypes.name(template)).hasSizeLessThanOrEqualTo(DocumentSchemas.MAX_NAME_LENGTH);
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("every type name is distinct, since it is what the lookup keys on")
    void everyTypeNameIsDistinct(DeedTemplate template) {
        List<String> others = Arrays.stream(DeedTemplate.values())
            .filter(other -> other != template)
            .map(DeedDocumentTypes::name)
            .toList();

        assertThat(others).isNotEmpty().doesNotContain(DeedDocumentTypes.name(template));
    }

    @Test
    @DisplayName("the adult deed is for the two adult case types and the minor's for the two minor ones")
    void statesWhoEachDeedIsFor() {
        assertThat(DeedDocumentTypes.caseTypes(DeedTemplate.ADULT))
            .containsExactly(DeedPollType.Adult, DeedPollType.AdultTransgender);
        assertThat(DeedDocumentTypes.caseTypes(DeedTemplate.MINOR))
            .containsExactly(DeedPollType.Minor, DeedPollType.MinorTransgender);
    }

    @Test
    @DisplayName("no case type takes two deeds")
    void noCaseTypeTakesTwoDeeds() {
        // The failure this guards is the one decision 3 names: an AdultTransgender applicant swearing a
        // minor's deed. Here it would show up as a case type appearing on both lists.
        List<DeedPollType> everyListing = new ArrayList<>();
        for (DeedTemplate template : DeedTemplate.values()) {
            everyListing.addAll(DeedDocumentTypes.caseTypes(template));
        }

        assertThat(everyListing).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("relinquishment and Unknown are on neither list, deliberately")
    void leavesRelinquishmentAndUnknownOff() {
        List<DeedPollType> everyListing = Arrays.stream(DeedTemplate.values())
            .flatMap(template -> DeedDocumentTypes.caseTypes(template).stream())
            .toList();

        // Whether a relinquishment needs a generated deed at all is open -- notify-4a's flag 5 -- and Unknown
        // has no safe default. Both absences are decisions, so they are asserted rather than left to be
        // noticed as a gap.
        assertThat(everyListing)
            .isNotEmpty()
            .doesNotContain(DeedPollType.DeedOfRelinquishment)
            .doesNotContain(DeedPollType.Unknown);
    }

    @ParameterizedTest
    @EnumSource(DeedTemplate.class)
    @DisplayName("applicableTypes is the Dynamics option-set integer of each case type, in the same order")
    void applicableTypesAreTheDynamicsIntegers(DeedTemplate template) {
        Integer[] expected = DeedDocumentTypes.caseTypes(template).stream()
            .map(DynamicsValues.DEED_POLL_TYPE_VALUE::get)
            .toArray(Integer[]::new);

        assertThat(DeedDocumentTypes.applicableTypes(template)).containsExactly(expected);
    }

    @Test
    @DisplayName("applicableTypes holds the integers the Dataverse export holds, not enum positions")
    void applicableTypesHoldTheDataverseIntegers() {
        // Pinned as literals as well as derived: the column's other rows came out of Dataverse, and these
        // integers are what a re-import would read. 1/2 are Adult and AdultTransgender, 3/4 the minor pair.
        assertThat(DeedDocumentTypes.applicableTypes(DeedTemplate.ADULT)).containsExactly(1, 2);
        assertThat(DeedDocumentTypes.applicableTypes(DeedTemplate.MINOR)).containsExactly(3, 4);
    }
}
