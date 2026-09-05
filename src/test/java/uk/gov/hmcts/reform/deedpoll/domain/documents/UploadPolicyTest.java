package uk.gov.hmcts.reform.deedpoll.domain.documents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.FieldError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports every test block in {@code reference/ts/documents/upload-policy.test.ts}.
 *
 * <p>Nothing here needs a database or a context: the policy is two constants and three functions, which is
 * the point of it being a policy rather than middleware.
 */
class UploadPolicyTest {

    @Test
    @DisplayName("sets the limit at 25MB")
    void setsTheLimitAt25Mb() {
        assertThat(UploadPolicy.MAX_UPLOAD_BYTES).isEqualTo(25L * 1024 * 1024);
    }

    @Test
    @DisplayName("accepts PDF, the common scan formats and Word")
    void acceptsPdfScanFormatsAndWord() {
        for (String type : List.of("application/pdf", "image/jpeg", "image/png", "image/tiff",
            "application/msword")) {
            assertThat(UploadPolicy.isAllowedUploadType(type)).as(type).isTrue();
        }
        assertThat(UploadPolicy.isAllowedUploadType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document")).isTrue();
    }

    @Test
    @DisplayName("rejects an executable and anything else not on the list")
    void rejectsAnythingNotOnTheList() {
        assertThat(UploadPolicy.isAllowedUploadType("application/x-msdownload")).isFalse();
        assertThat(UploadPolicy.isAllowedUploadType("application/zip")).isFalse();
        assertThat(UploadPolicy.isAllowedUploadType("")).isFalse();
    }

    @Test
    @DisplayName("does not accept a type by prefix, which would let application/* through")
    void doesNotAcceptATypeByPrefix() {
        assertThat(UploadPolicy.isAllowedUploadType("application/pdf; charset=binary")).isFalse();
    }

    @Test
    @DisplayName("does not fall over on a null declared type")
    void doesNotFallOverOnANullDeclaredType() {
        // Not a reference block: JavaScript's Set.has(undefined) simply answers false. Here a client that
        // sends a multipart part with no Content-Type gives Spring a null, and a NullPointerException from the
        // policy would be a 500 for a file that should have been a 400.
        assertThat(UploadPolicy.isAllowedUploadType(null)).isFalse();
    }

    @Nested
    @DisplayName("uploadTypeRejection")
    class TypeRejection {

        @Test
        @DisplayName("is an InvalidInputError, so the route layer maps it to 400 rather than 500")
        void isAnInvalidInputError() {
            assertThat(UploadPolicy.uploadTypeRejection("application/zip"))
                .isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("names the offending type in the message and attributes the error to the file input")
        void namesTheOffendingType() {
            InvalidInputError rejection = UploadPolicy.uploadTypeRejection("application/zip");

            assertThat(rejection.getMessage()).contains("application/zip");
            assertThat(rejection.errors()).containsExactly(
                new FieldError(UploadPolicy.UPLOAD_FILE_FIELD, "Upload a PDF, image or Word document"));
        }
    }

    @Nested
    @DisplayName("uploadLimitRejection")
    class LimitRejection {

        @Test
        @DisplayName("states the limit in megabytes for an over-size file")
        void statesTheLimitInMegabytes() {
            InvalidInputError rejection = UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE");

            assertThat(rejection.getMessage()).isEqualTo("The file must be smaller than 25MB");
            assertThat(rejection.errors().get(0).field()).isEqualTo(UploadPolicy.UPLOAD_FILE_FIELD);
        }

        @Test
        @DisplayName("derives the megabytes from the limit rather than restating it")
        void derivesTheMegabytesFromTheLimit() {
            // Guards the pairing: changing MAX_UPLOAD_BYTES must change the message, or the form would reject
            // at one size while telling the caseworker another.
            long megabytes = Math.round((double) UploadPolicy.MAX_UPLOAD_BYTES / (1024 * 1024));

            assertThat(UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE").getMessage())
                .contains(String.valueOf(megabytes));
        }

        @Test
        @DisplayName("asks for one file at a time when too many or an unexpected field arrive")
        void asksForOneFileAtATime() {
            assertThat(UploadPolicy.uploadLimitRejection("LIMIT_FILE_COUNT").getMessage())
                .isEqualTo("Upload one file at a time");
            assertThat(UploadPolicy.uploadLimitRejection("LIMIT_UNEXPECTED_FILE").getMessage())
                .isEqualTo("Upload one file at a time");
        }

        @Test
        @DisplayName("falls back to a generic message for a code it does not know")
        void fallsBackToAGenericMessage() {
            // There are codes this does not enumerate. A vague message beats an unmapped failure becoming a
            // 500, which is the whole reason the function exists.
            assertThat(UploadPolicy.uploadLimitRejection("LIMIT_FIELD_KEY").getMessage())
                .isEqualTo("The file could not be read");
            assertThat(UploadPolicy.uploadLimitRejection("")).isInstanceOf(InvalidInputError.class);
            assertThat(UploadPolicy.uploadLimitRejection(null)).isInstanceOf(InvalidInputError.class);
        }

        @Test
        @DisplayName("always attributes the failure to a field, so a form can render it in place")
        void alwaysAttributesTheFailureToAField() {
            for (String code : List.of("LIMIT_FILE_SIZE", "LIMIT_FILE_COUNT", "LIMIT_UNEXPECTED_FILE",
                "SOMETHING_ELSE")) {
                assertThat(UploadPolicy.uploadLimitRejection(code).errors()).as(code).hasSize(1);
            }
        }

        @Test
        @DisplayName("says the same thing in the message and against the field")
        void saysTheSameThingInBothPlaces() {
            // Not a reference block, though its implementation derives both from one string. Worth pinning: an
            // error summary renders the message and the input renders the field's, and a caseworker reading two
            // different sentences about one file has to work out which is true.
            InvalidInputError rejection = UploadPolicy.uploadLimitRejection("LIMIT_FILE_SIZE");

            assertThat(rejection.errors().get(0).message()).isEqualTo(rejection.getMessage());
        }
    }

    @Test
    @DisplayName("uses the same field name every caller posts the file under")
    void usesTheSameFieldName() {
        assertThat(UploadPolicy.UPLOAD_FILE_FIELD).isEqualTo("file");
    }

    @Test
    @DisplayName("exposes the accepted set for anything that needs to list it")
    void exposesTheAcceptedSet() {
        assertThat(UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES).hasSize(6);
    }

    @Test
    @DisplayName("the accepted set cannot be added to by a caller")
    void theAcceptedSetIsImmutable() {
        // Not a reference block -- a JavaScript Set is mutable and its test does not pretend otherwise. Here
        // the set is public and reachable from anywhere, and a type added to it at runtime would be a policy
        // change no review saw.
        assertThatThrownBy(() -> UploadPolicy.ALLOWED_UPLOAD_MIME_TYPES.add("application/zip"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
