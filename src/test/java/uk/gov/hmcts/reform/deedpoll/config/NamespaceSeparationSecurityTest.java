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
 * The caseworker, citizen and judicial namespaces do not share a DTO.
 *
 * <p>Asserted rather than remembered, which is the whole reason the citizen namespace is created empty in plan
 * 01 task 12 and the judicial one has its own DTOs from the start: the three audiences are not the same shape of
 * the same thing. A caseworker sees every field on a case, including the admin-only privacy fields; a citizen
 * sees their own application; a judge sees the deliberately narrow projection they need to direct a referral --
 * the reference, the application type, the old and new names, and the referral's own reasons, notes and urgency,
 * and none of the payment references, internal audit trails or admin-only privacy editability. A DTO reused
 * across two of those is one added field away from exposing the first audience's data to the second, and at the
 * time the reuse looks like sensible deduplication rather than a disclosure.
 *
 * <p>A bytecode rule rather than a grep for imports, so a fully-qualified reference, a generic parameter or a
 * field type is caught too -- the ways of using a class that do not appear in an import list.
 *
 * <p>Every rule allows an empty {@code should}, because the citizen namespace is deliberately empty until a
 * later plan and a rule that failed for having nothing to check would have to be deleted rather than kept.
 * {@link #everyNamespaceExists()} is what stops that leniency from making the whole class vacuous.
 */
class NamespaceSeparationSecurityTest {

    private static final String CASEWORKER = "uk.gov.hmcts.reform.deedpoll.api.caseworker";
    private static final String CITIZEN = "uk.gov.hmcts.reform.deedpoll.api.citizen";
    private static final String JUDICIAL = "uk.gov.hmcts.reform.deedpoll.api.judicial";
    private static final String CALLER = "uk.gov.hmcts.reform.deedpoll.api.caller";

    private static final JavaClasses API_CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("uk.gov.hmcts.reform.deedpoll.api");

    @Test
    @DisplayName("no citizen class depends on a caseworker DTO")
    void citizenDoesNotDependOnCaseworkerDtos() {
        assertNoDtoDependency(CITIZEN, CASEWORKER);
    }

    @Test
    @DisplayName("no caseworker class depends on a citizen DTO either, so the sharing cannot start at that end")
    void caseworkerDoesNotDependOnCitizenDtos() {
        assertNoDtoDependency(CASEWORKER, CITIZEN);
    }

    /**
     * The rule the judicial surface exists for.
     *
     * <p>This is the pair that is a disclosure rather than a tidiness question, and it is why the judiciary got
     * a namespace instead of a role-gated slice of {@code /caseworker/**}: {@code CaseDetailResponse} carries
     * every field a caseworker sees, so a judicial endpoint returning one is one field away from putting the
     * administrative and privacy fields in front of a judge. The judicial application view is a separate, narrow
     * projection, and "separate" has to mean a different type rather than an alias -- which is what a bytecode
     * rule can say and a convention cannot.
     */
    @Test
    @DisplayName("no judicial class depends on a caseworker DTO")
    void judicialDoesNotDependOnCaseworkerDtos() {
        assertNoDtoDependency(JUDICIAL, CASEWORKER);
    }

    @Test
    @DisplayName("no caseworker class depends on a judicial DTO either")
    void caseworkerDoesNotDependOnJudicialDtos() {
        assertNoDtoDependency(CASEWORKER, JUDICIAL);
    }

    @Test
    @DisplayName("the judicial and citizen namespaces do not share a DTO in either direction")
    void judicialAndCitizenDoNotShareDtos() {
        // Both empty of DTOs today in the citizen case, and asserted anyway: the citizen surface is the one that
        // will be written last, against a service that by then has two other namespaces to copy from.
        assertNoDtoDependency(JUDICIAL, CITIZEN);
        assertNoDtoDependency(CITIZEN, JUDICIAL);
    }

    @Test
    @DisplayName("the caller namespace shares no DTO with a staff or citizen namespace, in either direction")
    void callerSharesNoDtos() {
        for (String other : new String[] {CASEWORKER, CITIZEN, JUDICIAL}) {
            assertNoDtoDependency(CALLER, other);
            assertNoDtoDependency(other, CALLER);
        }
    }

    @Test
    @DisplayName("the three namespaces do not depend on each other at all")
    void theNamespacesAreIndependent() {
        // A controller calling another namespace's controller would share the DTOs by another route, and one
        // slice rule says it for every package pair rather than one rule per pair -- so this covers the judicial
        // namespace against both others without naming them.
        slices()
            .matching("uk.gov.hmcts.reform.deedpoll.api.(*)..")
            .should().notDependOnEachOther()
            .allowEmptyShould(true)
            .check(API_CLASSES);
    }

    /**
     * The rules above pass trivially against a namespace that does not exist, so this asserts all six packages
     * do. The caseworker side is filled; the judicial side is filled by the judicial-surface plan; the citizen
     * side stays empty until a later plan.
     *
     * <p>Asserted against the source tree rather than the bytecode because a {@code package-info.java} carrying
     * only documentation compiles to no class file at all -- so an empty package is a real, reviewable thing in
     * the repository and nothing at runtime. Which also means a rule about an empty namespace is checking an
     * empty set today and becomes load-bearing the moment a DTO is added; the point of writing them now is that
     * nobody has to remember to.
     */
    @Test
    @DisplayName("every namespace exists, so the rules are checking something")
    void everyNamespaceExists() {
        for (String namespace : new String[] {CASEWORKER, CITIZEN, JUDICIAL, CALLER}) {
            assertThat(documentedPackage(namespace + ".controller")).exists();
            assertThat(documentedPackage(namespace + ".dto")).exists();
        }
    }

    /**
     * No class in one namespace may reference a type in another's {@code dto} package, by any route bytecode
     * records -- a field, a parameter, a return type, a generic argument or a fully-qualified name.
     */
    private static void assertNoDtoDependency(String namespace, String otherNamespace) {
        noClasses()
            .that().resideInAPackage(namespace + "..")
            .should().dependOnClassesThat().resideInAPackage(otherNamespace + ".dto..")
            .allowEmptyShould(true)
            .check(API_CLASSES);
    }

    private static Path documentedPackage(String packageName) {
        return Path.of("src/main/java", packageName.replace('.', '/'), "package-info.java");
    }
}
