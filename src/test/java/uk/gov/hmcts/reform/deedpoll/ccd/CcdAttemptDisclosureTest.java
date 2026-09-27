package uk.gov.hmcts.reform.deedpoll.ccd;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CcdAttemptCountResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CcdAttemptListResponse;
import uk.gov.hmcts.reform.deedpoll.api.caseworker.dto.CcdAttemptResponse;
import uk.gov.hmcts.reform.deedpoll.entity.CcdCreationAttempt;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the repair view never publishes about an attempt (plan 02b, decision 11): the worker that holds it, its
 * lease, the operation id data-store's idempotency rests on, whether a submission may have reached CCD -- published
 * only as its consequence, {@code canRetryFromScratch} -- and who repaired it and when.
 *
 * <p>The {@code requestId} precedent in {@code NotificationSecurityTest}: a rule over every class of the API surface,
 * so a DTO this test does not name cannot publish one either, and a counterpart holding that the entity does store
 * each, so the rule is not green because nothing does. Container-free, and it imports the API package alone.
 */
class CcdAttemptDisclosureTest {

    /**
     * Names no API type may declare at all. {@code claimedBy} is not one of them: a referral publishes its claimant's
     * display name under that name, so the worker is held out of the repair view's own types in {@link #WORKER}.
     */
    private static final List<String> UNPUBLISHED = List.of(
        "leaseExpiresAt", "operationId", "submissionMayHaveReached", "repairedById", "repairedOn", "notBefore");

    /** The worker that holds an attempt: a pod name, which the repair view's types never carry. */
    private static final String WORKER = "claimedBy";

    private static JavaClasses apiClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll.api");
    }

    @Test
    @DisplayName("no class of the API surface has a field for what an attempt keeps to itself")
    void theApiSurfaceCarriesNone() {
        for (String name : UNPUBLISHED) {
            noFields()
                .that().areDeclaredInClassesThat().resideInAPackage("uk.gov.hmcts.reform.deedpoll.api..")
                .should().haveName(name)
                .allowEmptyShould(true)
                .check(apiClasses());
        }
    }

    @Test
    @DisplayName("the repair view's own types carry none of it either, the domain summary included")
    void theRepairTypesCarryNone() {
        assertThat(Stream.of(CcdAttemptListResponse.class, CcdAttemptResponse.class, CcdAttemptCountResponse.class,
                             CcdAttemptSummary.class, CcdAttemptOverview.class)
                       .flatMap(type -> Arrays.stream(type.getRecordComponents()))
                       .map(RecordComponent::getName))
            .doesNotContainAnyElementsOf(UNPUBLISHED)
            .doesNotContain(WORKER)
            .contains("canRetryFromScratch");
    }

    @Test
    @DisplayName("the counterpart: the attempt does store every one of them, so the rules above are not vacuous")
    void theEntityStoresEach() {
        assertThat(Arrays.stream(CcdCreationAttempt.class.getDeclaredFields()).map(Field::getName))
            .containsAll(UNPUBLISHED)
            .contains(WORKER);
    }
}
