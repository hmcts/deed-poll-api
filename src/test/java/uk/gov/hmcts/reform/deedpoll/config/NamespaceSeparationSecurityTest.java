package uk.gov.hmcts.reform.deedpoll.config;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The caseworker and citizen namespaces do not share a DTO.
 *
 * <p>Asserted rather than remembered, which is the whole reason the citizen namespace is created empty in plan
 * 01 task 12: the two audiences are not the same shape of the same thing. A caseworker sees every field on a
 * case, including the admin-only privacy fields; a citizen sees their own application. A DTO reused across both
 * is one added field away from exposing the first to the second, and at the time the reuse looks like sensible
 * deduplication rather than a disclosure.
 *
 * <p>A bytecode rule rather than a grep for imports, so a fully-qualified reference, a generic parameter or a
 * field type is caught too -- the ways of using a class that do not appear in an import list.
 *
 * <p>Every rule allows an empty {@code should}, because the citizen namespace is deliberately empty until a
 * later plan and a rule that failed for having nothing to check would have to be deleted rather than kept.
 * {@link #bothNamespacesExist()} is what stops that leniency from making the whole class vacuous.
 */
class NamespaceSeparationSecurityTest {

    private static final String CASEWORKER = "uk.gov.hmcts.reform.deedpoll.api.caseworker";
    private static final String CITIZEN = "uk.gov.hmcts.reform.deedpoll.api.citizen";

    private static final JavaClasses API_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("uk.gov.hmcts.reform.deedpoll.api");

    @Test
    @DisplayName("no citizen class depends on a caseworker DTO")
    void citizenDoesNotDependOnCaseworkerDtos() {
        noClasses()
            .that().resideInAPackage(CITIZEN + "..")
            .should().dependOnClassesThat().resideInAPackage(CASEWORKER + ".dto..")
            .allowEmptyShould(true)
            .check(API_CLASSES);
    }

    @Test
    @DisplayName("no caseworker class depends on a citizen DTO either, so the sharing cannot start at that end")
    void caseworkerDoesNotDependOnCitizenDtos() {
        noClasses()
            .that().resideInAPackage(CASEWORKER + "..")
            .should().dependOnClassesThat().resideInAPackage(CITIZEN + ".dto..")
            .allowEmptyShould(true)
            .check(API_CLASSES);
    }

    @Test
    @DisplayName("the two namespaces do not depend on each other at all")
    void theNamespacesAreIndependent() {
        // A controller calling the other namespace's controller would share the DTOs by another route, and one
        // slice rule says it for every package pair rather than one rule per pair.
        slices()
            .matching("uk.gov.hmcts.reform.deedpoll.api.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true)
            .check(API_CLASSES);
    }

    /**
     * The rules above pass trivially against a namespace that does not exist, so this asserts all four do. Tasks
     * 13 to 15 fill the caseworker side; the citizen side stays empty until a later plan.
     *
     * <p>Asserted against the source tree rather than the bytecode because a {@code package-info.java} carrying
     * only documentation compiles to no class file at all -- so an empty package is a real, reviewable thing in
     * the repository and nothing at runtime. Which also means the three rules above are checking an empty set
     * today and become load-bearing the moment a DTO is added; the point of writing them now is that nobody has
     * to remember to.
     */
    @Test
    @DisplayName("both namespaces exist, so the rules are checking something")
    void bothNamespacesExist() {
        assertThat(documentedPackage(CITIZEN + ".controller")).exists();
        assertThat(documentedPackage(CITIZEN + ".dto")).exists();
        assertThat(documentedPackage(CASEWORKER + ".controller")).exists();
        assertThat(documentedPackage(CASEWORKER + ".dto")).exists();
    }

    private static Path documentedPackage(String packageName) {
        return Path.of("src/main/java", packageName.replace('.', '/'), "package-info.java");
    }
}
