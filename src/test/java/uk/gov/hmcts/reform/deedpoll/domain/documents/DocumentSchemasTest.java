package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DocumentDirection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/documents/schemas.test.ts}.
 *
 * <p>{@code safeParse(...).success === false} becomes "throws {@link InvalidInputError}", and where the
 * reference only checks that flag this asserts the {@link FieldError} as well -- the field name is what lets
 * a form render the failure against the right input rather than as a banner, and it is the part a boolean
 * success flag cannot check.
 *
 * <p>The reference has no tests for {@code uploadFormSchema}; the four here are additions, noted at each.
 * That schema is the one a real request goes through -- everything else about a document arrives through it --
 * and the reference's own comment on it records that its predecessor validated none of this and so turned a
 * typo into a 500.
 */
class DocumentSchemasTest {

    /** The reference's `validDocument` fixture, field for field. */
    private static final Map<String, Object> VALID_DOCUMENT = payload(
        "direction", DocumentDirection.Incoming.name(),
        "name", "Birth certificate",
        "documentTypeId", "3f0f5b62-1f0a-4c1e-9b3a-2c1d4e5f6a7b");

    private static final String TYPE_ID = "3f0f5b62-1f0a-4c1e-9b3a-2c1d4e5f6a7b";

    private static Map<String, Object> payload(Object... keysAndValues) {
        Map<String, Object> raw = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            raw.put(String.valueOf(keysAndValues[i]), keysAndValues[i + 1]);
        }
        return raw;
    }

    /** The reference's `{ ...validDocument, field: value }`. */
    private static Map<String, Object> documentWith(String field, Object value) {
        Map<String, Object> raw = new LinkedHashMap<>(VALID_DOCUMENT);
        raw.put(field, value);
        return raw;
    }

    /** The reference's `delete partial[field]`. */
    private static Map<String, Object> documentWithout(String field) {
        Map<String, Object> raw = new LinkedHashMap<>(VALID_DOCUMENT);
        raw.remove(field);
        return raw;
    }

    /** The failures the rejection carries, each attributed to its field. */
    private static List<FieldError> errorsFrom(ThrowingParse parse) {
        try {
            parse.run();
        } catch (InvalidInputError rejected) {
            return rejected.errors();
        }
        throw new AssertionError("expected the payload to be rejected");
    }

    private interface ThrowingParse {
        void run();
    }

    @Nested
    @DisplayName("createDocumentSchema")
    class CreateDocument {

        @Test
        @DisplayName("accepts metadata without the file, which is attached separately")
        void acceptsMetadataWithoutTheFile() {
            CreateDocumentInput parsed = DocumentSchemas.parseCreateDocument(VALID_DOCUMENT);

            assertThat(parsed.direction()).isEqualTo(DocumentDirection.Incoming);
            assertThat(parsed.name()).isEqualTo("Birth certificate");
            assertThat(parsed.documentTypeId()).isEqualTo(TYPE_ID);
            // The reference asserts `parse(valid)` equals `valid`, i.e. that nothing was added. The three
            // storage fields being null IS that claim here: a create with no file records no file.
            assertThat(parsed.storageKey()).isNull();
            assertThat(parsed.originalName()).isNull();
            assertThat(parsed.mimeType()).isNull();
        }

        @Test
        @DisplayName("requires direction, name and the document-type FK")
        void requiresEveryMandatoryField() {
            for (String field : VALID_DOCUMENT.keySet()) {
                assertThatThrownBy(() -> DocumentSchemas.parseCreateDocument(documentWithout(field)))
                    .as("%s should be required", field)
                    .isInstanceOf(InvalidInputError.class);

                assertThat(errorsFrom(() -> DocumentSchemas.parseCreateDocument(documentWithout(field))))
                    .containsExactly(new FieldError(field, field + " is required"));
            }
        }

        @Test
        @DisplayName("requires documentTypeId to be a UUID, so a bad FK fails before it reaches the database")
        void requiresDocumentTypeIdToBeAUuid() {
            assertThat(errorsFrom(() ->
                DocumentSchemas.parseCreateDocument(documentWith("documentTypeId", "not-a-uuid"))))
                .containsExactly(new FieldError("documentTypeId", "documentTypeId must be a UUID"));
        }

        @Test
        @DisplayName("rejects a direction outside the option set")
        void rejectsADirectionOutsideTheOptionSet() {
            assertThat(errorsFrom(() -> DocumentSchemas.parseCreateDocument(documentWith("direction", "Sideways"))))
                .containsExactly(new FieldError("direction", "direction is not one of the accepted values"));
        }

        @Test
        @DisplayName("enforces the 100-character name limit")
        void enforcesTheNameLimit() {
            assertThat(errorsFrom(() ->
                DocumentSchemas.parseCreateDocument(documentWith("name", "a".repeat(101)))))
                .containsExactly(new FieldError("name", "name must be 100 characters or fewer"));

            assertThatCode(() -> DocumentSchemas.parseCreateDocument(documentWith("name", "a".repeat(100))))
                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("accepts the storage fields when a caller does supply them")
        void acceptsTheStorageFields() {
            // Not a reference block: its "accepts metadata without the file" covers only the absent case, and
            // uploadDocument writes all three -- so a create that cannot carry them would be a silent gap.
            Map<String, Object> raw = new LinkedHashMap<>(VALID_DOCUMENT);
            raw.put("storageKey", "blob-1");
            raw.put("originalName", "birth.pdf");
            raw.put("mimeType", "application/pdf");

            CreateDocumentInput parsed = DocumentSchemas.parseCreateDocument(raw);

            assertThat(parsed.storageKey()).isEqualTo("blob-1");
            assertThat(parsed.originalName()).isEqualTo("birth.pdf");
            assertThat(parsed.mimeType()).isEqualTo("application/pdf");
        }

        @Test
        @DisplayName("strips a key the caller may not set")
        void stripsAnUnknownKey() {
            // Not a reference block, where `z.object()` strips unknown keys and the schemas test does not
            // restate it. Here it is structural -- CreateDocumentInput has nowhere for sizeBytes to land --
            // and worth pinning, because a caller declaring a size that disagrees with the stored blob would
            // make the download's own headers wrong.
            Map<String, Object> raw = new LinkedHashMap<>(VALID_DOCUMENT);
            raw.put("sizeBytes", 99);
            raw.put("deedPollId", "some-other-case");

            assertThatCode(() -> DocumentSchemas.parseCreateDocument(raw)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reports every offending field at once, not just the first")
        void reportsEveryOffendingField() {
            // Not a reference block: safeParse's success flag cannot tell how many failures there were. A
            // caseworker retyping a document's details should see all of its problems in one pass.
            assertThat(errorsFrom(() -> DocumentSchemas.parseCreateDocument(payload("name", "a".repeat(101)))))
                .extracting(FieldError::field)
                .containsExactly("direction", "name", "documentTypeId");
        }
    }

    @Nested
    @DisplayName("createDocumentTypeSchema")
    class CreateDocumentType {

        @Test
        @DisplayName("accepts a name with no applicability lists")
        void acceptsANameWithNoApplicabilityLists() {
            CreateDocumentTypeInput parsed = DocumentSchemas.parseCreateDocumentType(payload("name", "Passport"));

            assertThat(parsed.name()).isEqualTo("Passport");
            // The reference asserts the parsed object equals `{name}` -- nothing defaulted in. Absent, not
            // empty: what an absent list means on a create is "take the column's own [] default".
            assertThat(parsed.applicableMaritalStatuses()).isEmpty();
            assertThat(parsed.applicableTypes()).isEmpty();
        }

        @Test
        @DisplayName("accepts option-set integers for the applicability lists")
        void acceptsOptionSetIntegers() {
            CreateDocumentTypeInput parsed = DocumentSchemas.parseCreateDocumentType(payload(
                "name", "Marriage certificate",
                "applicableMaritalStatuses", List.of(2),
                "applicableTypes", List.of(1, 2)));

            assertThat(parsed.applicableMaritalStatuses()).contains(List.of(2));
            assertThat(parsed.applicableTypes()).contains(List.of(1, 2));
        }

        @Test
        @DisplayName("rejects non-integer option-set values")
        void rejectsNonIntegerOptionSetValues() {
            assertThatThrownBy(() -> DocumentSchemas.parseCreateDocumentType(
                payload("name", "X", "applicableTypes", List.of("1"))))
                .isInstanceOf(InvalidInputError.class);

            assertThatThrownBy(() -> DocumentSchemas.parseCreateDocumentType(
                payload("name", "X", "applicableTypes", List.of(1.5))))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("requires a name")
        void requiresAName() {
            assertThat(errorsFrom(() -> DocumentSchemas.parseCreateDocumentType(Map.of())))
                .containsExactly(new FieldError("name", "name is required"));
        }

        @Test
        @DisplayName("refuses a null applicability list rather than storing one")
        void refusesANullApplicabilityList() {
            // Not a reference block. The column has a [] default and so is NOT NULL -- the distinction
            // schema-primitives.ts draws between .optional() and .nullish() -- so a null must be refused here
            // rather than reaching the database and failing there with less to say.
            assertThat(errorsFrom(() ->
                DocumentSchemas.parseCreateDocumentType(payload("name", "X", "applicableTypes", null))))
                .containsExactly(new FieldError("applicableTypes",
                    "applicableTypes cannot be null"));
        }
    }

    @Nested
    @DisplayName("partial update schemas")
    class PartialUpdates {

        @Test
        @DisplayName("accept empty patches")
        void acceptEmptyPatches() {
            assertThat(DocumentSchemas.parseUpdateDocument(Map.of()).isEmpty()).isTrue();
            assertThat(DocumentSchemas.parseUpdateDocumentType(Map.of()).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("still validate present fields")
        void stillValidatePresentFields() {
            assertThatThrownBy(() -> DocumentSchemas.parseUpdateDocument(payload("documentTypeId", "nope")))
                .isInstanceOf(InvalidInputError.class);

            assertThatThrownBy(() ->
                DocumentSchemas.parseUpdateDocumentType(payload("name", "a".repeat(101))))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("tell an omitted field from one written as null")
        void tellAnOmittedFieldFromOneWrittenAsNull() {
            // Not a reference block: JavaScript gives it the distinction free, so its schemas test has nothing
            // to prove. Here it is the whole reason UpdateDocumentInput uses PatchField, and clearing
            // storageKey -- detaching a blob from its row -- is the write that depends on it.
            UpdateDocumentInput omitted = DocumentSchemas.parseUpdateDocument(payload("name", "Renamed"));
            assertThat(omitted.storageKey().present()).isFalse();

            UpdateDocumentInput cleared = DocumentSchemas.parseUpdateDocument(payload("storageKey", null));
            assertThat(cleared.storageKey().present()).isTrue();
            assertThat(cleared.storageKey().isCleared()).isTrue();
        }

        @Test
        @DisplayName("refuse a null where the column is NOT NULL")
        void refuseANullWhereTheColumnIsNotNull() {
            // The other half of the pair above, and the reason the two groups of fields are not one rule:
            // direction, name and documentTypeId have no legal null, so a clear is a validation error naming
            // the field rather than a constraint violation naming the column.
            assertThat(errorsFrom(() -> DocumentSchemas.parseUpdateDocument(payload("name", null))))
                .containsExactly(new FieldError("name", "name cannot be null"));

            assertThat(errorsFrom(() -> DocumentSchemas.parseUpdateDocumentType(payload("name", null))))
                .containsExactly(new FieldError("name", "name cannot be null"));
        }

        @Test
        @DisplayName("accept a document patch naming every field")
        void acceptADocumentPatchNamingEveryField() {
            // Not a reference block: `.partial()` needs no proof in TypeScript that each field survives it.
            UpdateDocumentInput parsed = DocumentSchemas.parseUpdateDocument(payload(
                "direction", "Outgoing",
                "name", "Renamed",
                "documentTypeId", TYPE_ID,
                "storageKey", "blob-2",
                "originalName", "scan.tiff",
                "mimeType", "image/tiff"));

            assertThat(parsed.isEmpty()).isFalse();
            assertThat(parsed.direction().value()).isEqualTo(DocumentDirection.Outgoing);
            assertThat(parsed.name().value()).isEqualTo("Renamed");
            assertThat(parsed.documentTypeId().value()).isEqualTo(TYPE_ID);
            assertThat(parsed.storageKey().value()).isEqualTo("blob-2");
            assertThat(parsed.originalName().value()).isEqualTo("scan.tiff");
            assertThat(parsed.mimeType().value()).isEqualTo("image/tiff");
        }

        @Test
        @DisplayName("accept a type patch that empties an applicability list")
        void acceptATypePatchThatEmptiesAnApplicabilityList() {
            // Not a reference block. Emptying a list is [], not null -- so the field a caller uses to say "this
            // type applies to every marital status" must be reachable, and it must not be confused with the
            // null the test above refuses.
            UpdateDocumentTypeInput parsed = DocumentSchemas.parseUpdateDocumentType(
                payload("applicableTypes", List.of()));

            assertThat(parsed.isEmpty()).isFalse();
            assertThat(parsed.applicableTypes()).contains(List.of());
        }
    }

    @Nested
    @DisplayName("uploadFormSchema")
    class UploadForm {

        @Test
        @DisplayName("accepts a form carrying only the document type")
        void acceptsAFormCarryingOnlyTheDocumentType() {
            // Not a reference block -- it has no tests for this schema at all. Its own comment says why one is
            // wanted: direction and name are optional because the domain defaults them, so the minimum form
            // must parse.
            UploadFormInput parsed = DocumentSchemas.parseUploadForm(payload("documentTypeId", TYPE_ID));

            assertThat(parsed.documentTypeId()).isEqualTo(TYPE_ID);
            assertThat(parsed.name()).isEmpty();
            assertThat(parsed.direction()).isEmpty();
        }

        @Test
        @DisplayName("requires the document type, so a missing FK is a 400 and not a constraint violation")
        void requiresTheDocumentType() {
            assertThat(errorsFrom(() -> DocumentSchemas.parseUploadForm(Map.of())))
                .containsExactly(new FieldError("documentTypeId", "documentTypeId is required"));
        }

        @Test
        @DisplayName("reads direction from the member name, because multipart carries no types")
        void readsDirectionFromTheMemberName() {
            UploadFormInput parsed = DocumentSchemas.parseUploadForm(
                payload("documentTypeId", TYPE_ID, "direction", "Outgoing", "name", "Sealed deed"));

            assertThat(parsed.direction()).contains(DocumentDirection.Outgoing);
            assertThat(parsed.name()).contains("Sealed deed");
        }

        @Test
        @DisplayName("rejects a direction outside the option set rather than passing it to the database")
        void rejectsADirectionOutsideTheOptionSet() {
            assertThat(errorsFrom(() ->
                DocumentSchemas.parseUploadForm(payload("documentTypeId", TYPE_ID, "direction", "Sideways"))))
                .containsExactly(new FieldError("direction", "direction is not one of the accepted values"));
        }
    }
}
