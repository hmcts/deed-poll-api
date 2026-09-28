package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CaseDetailResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.SavedCaseResponse;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every stored column of a case is declared in {@link DeedPollFields} -- with named exemptions, each with its reason.
 *
 * <p>CLAUDE.md's "four places" rule was enforced only from {@code DeedPollFields} outward: the response-field
 * tests compare each DTO with {@code readAll}, so a column added to the entity and to nothing else failed no
 * test at all. This is the missing direction, stated strictly, so that a column which must not be declared there
 * is an exemption somebody wrote down rather than a gap nobody noticed.
 */
class DeedPollColumnsTest {

    /**
     * The citizen-journey columns: real columns, and absent from {@code DeedPollFields} since before this test
     * existed, because they belong to the public application journey and the reference's case schema does not
     * accept them either -- see {@code DeedPollFields} and {@code CaseDetailResponseFieldsTest}. Written down
     * here rather than newly decided.
     */
    private static final List<String> CITIZEN_JOURNEY = List.of(
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

    /**
     * {@code ccdCaseReference} (V21), the one exemption plan 02 adds: the reference of the one CCD case linked to
     * this record. Not readable, so a caller cannot query, sort or read it; not writable, so no patch can re-point
     * a record at a different case -- its only writer is {@code DeedPollRepository.bindCcdCaseReference}, which
     * binds once; and not in either case response, because publishing it is the payment plan's decision and not
     * this one's. Adding a name to either list is a decision of the same weight, and should come with its reason.
     */
    private static final String REFERENCE = "ccdCaseReference";

    private static final Map<String, String> EXEMPT = exemptions();

    private static Map<String, String> exemptions() {
        Map<String, String> exempt = new LinkedHashMap<>();
        CITIZEN_JOURNEY.forEach(name -> exempt.put(name, "the citizen journey's, not this API's"));
        exempt.put(REFERENCE, "bound once by the linking operation; never patched, queried or published");
        return exempt;
    }

    private static List<String> columnFields() {
        return Arrays.stream(DeedPoll.class.getDeclaredFields())
            .filter(field -> field.isAnnotationPresent(Column.class))
            .map(Field::getName)
            .toList();
    }

    @Test
    @DisplayName("every column of the case is readable through DeedPollFields, except the named exemptions")
    void everyColumnIsDeclaredExceptTheExemptions() {
        assertThat(columnFields().stream().filter(name -> !EXEMPT.containsKey(name)))
            .allSatisfy(name -> assertThat(DeedPollFields.readableFields()).as(name).contains(name));
    }

    @Test
    @DisplayName("every exemption names a column that exists and is really not declared")
    void everyExemptionIsReal() {
        // So the list cannot outlive its reason: an exemption for a column that was removed, or that somebody
        // later declared after all, fails here rather than sitting in the list excusing nothing.
        assertThat(columnFields()).containsAll(EXEMPT.keySet());
        assertThat(DeedPollFields.readableFields()).doesNotContainAnyElementsOf(EXEMPT.keySet());
        assertThat(DeedPollFields.writableFields()).doesNotContainAnyElementsOf(EXEMPT.keySet());
    }

    @Test
    @DisplayName("the CCD reference cannot be patched")
    void theReferenceCannotBePatched() {
        assertThat(DeedPollFields.isWritable(REFERENCE)).isFalse();
        assertThatThrownBy(() -> DeedPollFields.write(new DeedPoll(), REFERENCE, "1234567890123456"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the CCD reference cannot be queried or sorted on")
    void theReferenceCannotBeQueriedOrSorted() {
        assertThat(CaseSort.sortableFields()).doesNotContain(REFERENCE);
        assertThatThrownBy(() -> CaseSort.parse(REFERENCE)).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> CaseQuery.parse(List.of(REFERENCE + ":eq:1234567890123456")))
            .isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> CaseQuery.parse(List.of(REFERENCE + ":isEmpty")))
            .isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("the CCD reference is not written by an entity save, only by the conditional bind")
    void theReferenceIsNotWrittenByASave() throws NoSuchFieldException {
        // Without this a save of a row read before the bind would write its stale null back over the reference.
        Column column = DeedPoll.class.getDeclaredField(REFERENCE).getAnnotation(Column.class);

        assertThat(column.insertable()).isFalse();
        assertThat(column.updatable()).isFalse();
    }

    @Test
    @DisplayName("the CCD reference is published nowhere in the API")
    void theReferenceIsPublishedNowhere() {
        assertThat(Stream.of(CaseDetailResponse.class, SavedCaseResponse.class)
                       .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                       .map(RecordComponent::getName))
            .isNotEmpty()
            .doesNotContain(REFERENCE);

        // Only the API packages, and imported here rather than held in a static field: the rule is about nothing
        // else, and a whole-codebase import kept for the life of the test JVM is what exhausts its heap.
        JavaClasses apiClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll.api");

        noFields()
            .that().areDeclaredInClassesThat().resideInAPackage("uk.gov.hmcts.reform.deedpoll.api..")
            .should().haveName(REFERENCE)
            .allowEmptyShould(true)
            .check(apiClasses);
    }
}
