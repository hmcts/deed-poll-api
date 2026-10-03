package uk.gov.hmcts.reform.deedpoll.domain.deeds;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.storage.StagedPayloads;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PreparedDeedTest {
    @Test
    void fingerprintTracksRenderedFieldsButNotAuditChanges() {
        var deedPoll = new uk.gov.hmcts.reform.deedpoll.entity.DeedPoll();
        deedPoll.setNewForename("Original");
        String original = DeedDocumentService.inputFingerprint(deedPoll, DeedTemplate.ADULT);
        deedPoll.setModifiedBy("Another worker");
        assertThat(DeedDocumentService.inputFingerprint(deedPoll, DeedTemplate.ADULT)).isEqualTo(original);
        deedPoll.setNewForename("Changed");
        assertThat(DeedDocumentService.inputFingerprint(deedPoll, DeedTemplate.ADULT)).isNotEqualTo(original);
        assertThat(DeedDocumentService.inputFingerprint(deedPoll, DeedTemplate.MINOR))
            .isNotEqualTo(DeedDocumentService.inputFingerprint(deedPoll, DeedTemplate.ADULT));
    }

    @Test
    void diagnosticTextContainsNoIdentifierNameChecksumOrBytes() {
        var prepared = new PreparedDeed("private-id", DeedTemplate.ADULT, "private-name.docx",
            "application/private", 123, "private-checksum");
        assertThat(prepared.toString()).isEqualTo("PreparedDeed[template=ADULT, sizeBytes=123]");
    }

    @Test
    void publicDtoEntitiesAndMetricsCannotReachPreparationOrPayloadTypes() {
        noClasses().that().resideInAnyPackage("..api..dto..", "..entity..", "..health..")
            .should().dependOnClassesThat().haveNameMatching(".*\\.(PreparedDeed|DeedPreparations|StagedPayloads)")
            .check(new ClassFileImporter().importPackages("uk.gov.hmcts.reform.deedpoll"));
    }

    @Test
    void unsetCapacityRefusesBeforeTouchingTheDatabase() {
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        assertThatThrownBy(() -> new StagedPayloads(jdbc, 0).retain("id", new byte[]{1}))
            .isInstanceOf(ConflictError.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void checksumIsSha256OfExactBytes() {
        assertThat(StagedPayloads.checksum(new byte[]{97, 98, 99}))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
