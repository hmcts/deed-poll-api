package uk.gov.hmcts.reform.deedpoll.storage;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class CorrespondenceReaderAuthorityTest {
    @Test
    void publicApiCannotSelectServiceReadAuthority() {
        noClasses().that().resideInAnyPackage("..api..")
            .should().dependOnClassesThat().haveNameMatching(".*\\.(CorrespondenceDocumentReader|CdamDocumentStore)")
            .check(new ClassFileImporter().importPackages("uk.gov.hmcts.reform.deedpoll"));
    }
}
