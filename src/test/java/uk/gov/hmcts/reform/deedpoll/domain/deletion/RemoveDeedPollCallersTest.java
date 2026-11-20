package uk.gov.hmcts.reform.deedpoll.domain.deletion;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.deedpoll.DeedPollService;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * {@code DeedPollService.removeDeedPoll} audits as the service account and removes no binary, CCD case or SDK
 * snapshot, so it is not a way to delete an application. Nothing in the main source set may call it: a Delivery
 * Manager's deletion is {@link ApplicationDeletionService}'s request, finalised by its own verified cleanup.
 */
class RemoveDeedPollCallersTest {

    /** Imported here and dropped: a whole-codebase graph held in a static field exhausts the test heap. */
    private static JavaClasses mainClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("uk.gov.hmcts.reform.deedpoll");
    }

    @Test
    @DisplayName("no production class calls the short-form removeDeedPoll")
    void noProductionCaller() {
        noClasses()
            .should().callMethod(DeedPollService.class, "removeDeedPoll", String.class)
            .because("an application is deleted only through ApplicationDeletionService's request")
            .check(mainClasses());
    }
}
