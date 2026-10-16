package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.support.DocumentFixtures;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Upload content policy: shared admission checks, not malware scanning")
class UploadContentPolicyTest {
    private static final List<String> TYPES = List.of("application/pdf", "image/jpeg", "image/png", "image/tiff",
        DocumentFixtures.DOCX);

    @Test
    @DisplayName("each permitted format passes only under its own declared MIME type")
    void eachSignatureIsAcceptedOnlyUnderItsOwnDeclaredType() {
        for (String actual : TYPES) {
            for (String declared : TYPES) {
                UploadedFile file = UploadedFile.of(DocumentFixtures.bytes(actual), "fixture", declared);
                if (actual.equals(declared)) {
                    assertThatCode(() -> UploadContentPolicy.verify(file)).doesNotThrowAnyException();
                } else {
                    assertThatThrownBy(() -> UploadContentPolicy.verify(file)).isInstanceOf(InvalidInputError.class);
                }
            }
        }
    }

    @ParameterizedTest
    @DisplayName("mismatched and truncated files produce a file-field validation error")
    @ValueSource(strings = {"", "MZ executable", "<html>test</html>", "<svg/>", "%PDF", " \n%PDF-1.4"})
    void rejectsMismatchedOrTruncatedContentWithAFileError(String content) {
        assertThatThrownBy(() -> verifyPdf(content)).isInstanceOfSatisfying(InvalidInputError.class,
            error -> assertThat(error.errors()).containsExactly(new FieldError("file", error.getMessage())));
    }

    @ParameterizedTest
    @DisplayName("encryption and active PDF names are refused, including escaped names")
    @ValueSource(strings = {"/Encrypt", "/Encr#79pt", "/JavaScript", "/JS", "/Launch", "/EmbeddedFile",
        "/RichMedia", "/XFA", "/J#53"})
    void refusesEncryptionAndActivePdfNames(String token) {
        assertThatThrownBy(() -> verifyPdf("%PDF-1.7\n<<" + token + " 1 0 R>>"))
            .isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("longer PDF names do not trigger partial token matches")
    void doesNotMatchLongerPdfNames() {
        assertThatCode(() -> verifyPdf("%PDF-1.4\n<</JSExample 1 /EncryptionExample 2>>"))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("legacy Word is refused, and OLE-wrapped DOCX gets an encrypted-file message")
    void refusesLegacyWordAndOleDisguisedAsDocx() {
        byte[] ole = {(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0, (byte) 0xa1, (byte) 0xb1, 0x1a, (byte) 0xe1};
        assertThatThrownBy(() -> verify(ole, "application/msword")).isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> verify(ole, DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class)
            .hasMessageContaining("password-protected");
    }

    @ParameterizedTest
    @DisplayName("Word macro, ActiveX and embedded-object parts are refused")
    @ValueSource(strings = {"word/vbaProject.bin", "word/activeX/activeX1.bin", "word/embeddings/oleObject1.bin"})
    void refusesDangerousWordParts(String path) {
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(Map.of(path, "payload")), DocumentFixtures.DOCX))
            .isInstanceOf(InvalidInputError.class);
    }

    @ParameterizedTest
    @DisplayName("external Word template, object and frame relationships are refused")
    @ValueSource(strings = {"attachedTemplate", "oleObject", "frame"})
    void refusesExternalWordRelationships(String type) {
        String relationships = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId2"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/%s"
                Target="https://example.invalid/template" TargetMode="External"/>
            </Relationships>
            """.formatted(type);
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(Map.of("word/_rels/document.xml.rels", relationships)),
            DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("non-Word ZIPs and highly compressed DOCX packages are refused")
    void refusesNonWordZipAndZipBomb() {
        assertThatThrownBy(() -> verify(DocumentFixtures.zip(Map.of("hello.txt", "hello")), DocumentFixtures.DOCX))
            .isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(Map.of("word/large.xml", "a".repeat(2_000_000))),
            DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("macro content types are refused even when the macro part is renamed")
    void refusesMacroContentTypesEvenWithoutTheStandardMacroFilename() {
        String types = """
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Override PartName="/word/document.xml"
                ContentType="application/vnd.ms-word.document.macroEnabled.main+xml"/>
            </Types>
            """;
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(Map.of("[Content_Types].xml", types)),
            DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class);
        String renamedMacro = types.replace("application/vnd.ms-word.document.macroEnabled.main+xml",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml")
            .replace("</Types>", """
                <Override PartName="/word/payload.bin" ContentType="application/vnd.ms-office.vbaProject"/>
                </Types>
                """);
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(Map.of("[Content_Types].xml", renamedMacro,
            "word/payload.bin", "payload")), DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("ordinary external hyperlinks remain allowed")
    void ordinaryExternalHyperlinksDoNotBecomeExternalTemplates() {
        String relationships = """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId2"
                Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink"
                Target="https://example.invalid/" TargetMode="External"/>
            </Relationships>
            """;
        assertThatCode(() -> verify(DocumentFixtures.docx(Map.of("word/_rels/document.xml.rels", relationships)),
            DocumentFixtures.DOCX)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DOCX archives cannot exceed the entry-count budget")
    void refusesExcessiveZipEntryCounts() {
        Map<String, String> entries = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 1001; index++) {
            entries.put("word/part" + index + ".xml", "<test/>");
        }
        assertThatThrownBy(() -> verify(DocumentFixtures.docx(entries), DocumentFixtures.DOCX))
            .isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("both TIFF byte orders pass and truncated image signatures fail")
    void bothTiffByteOrdersAreAcceptedAndTruncatedImageSignaturesAreRefused() {
        verify(new byte[]{73, 73, 42, 0}, "image/tiff");
        verify(new byte[]{77, 77, 0, 42}, "image/tiff");
        assertThatThrownBy(() -> verify(new byte[]{73, 73, 42}, "image/tiff"))
            .isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> verify(new byte[]{(byte) 255, (byte) 216}, "image/jpeg"))
            .isInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> verify(new byte[]{(byte) 137, 80, 78, 71}, "image/png"))
            .isInstanceOf(InvalidInputError.class);
    }

    @Test
    @DisplayName("deterministic malformed-package samples fail closed without server errors")
    void randomMalformedPackagesAreRefusalsNotServerErrors() {
        Random random = new Random(5);
        for (int size = 4; size < 1000; size += 37) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            bytes[0] = 80;
            bytes[1] = 75;
            bytes[2] = 3;
            bytes[3] = 4;
            assertThatThrownBy(() -> verify(bytes, DocumentFixtures.DOCX)).isInstanceOf(InvalidInputError.class);
        }
    }

    private static void verifyPdf(String content) {
        verify(content.getBytes(StandardCharsets.US_ASCII), "application/pdf");
    }

    private static void verify(byte[] bytes, String type) {
        UploadContentPolicy.verify(UploadedFile.of(bytes, "untrusted-name", type));
    }
}
