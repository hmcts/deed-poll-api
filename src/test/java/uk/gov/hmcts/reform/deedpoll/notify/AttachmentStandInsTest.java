package uk.gov.hmcts.reform.deedpoll.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.service.notify.NotificationClientException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which refusals a preview answers with a stand-in, and which it leaves to fail.
 *
 * <p>The rule {@code CaseEmailServiceTest} exercises over HTTP, stated key by key: a stand-in only ever goes under a
 * file the portal attached, and never under a key this service owns or one that merely failed to match.
 */
class AttachmentStandInsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Map<String, Object> FORMS_PERSONALISATION = Map.of(
        FormsForCompletionPersonalisation.REFERENCE_NUMBER, "DP2026-000123",
        FormsForCompletionPersonalisation.DEED_DOCUMENT, FormsForCompletionPersonalisation.DEED_NOT_YET_GENERATED);

    @Test
    @DisplayName("reads every key a Notify 400 names as missing, spaces and dots included")
    void readsTheMissingKeys() {
        assertThat(AttachmentStandIns.missingKeys(missing("LOC021_0226.pdf, LOC027 blank.pdf").getMessage(), JSON))
            .containsExactly("LOC021_0226.pdf", "LOC027 blank.pdf");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Status code: 400 {template not found}",
        "Status code: 400 {\"status_code\": 400, \"errors\": [{\"error\": \"BadRequestError\", "
            + "\"message\": \"Template not found\"}]}",
        "Notify refused it",
    })
    @DisplayName("reads no missing keys out of any other refusal")
    void readsNothingFromAnotherRefusal(String message) {
        assertThat(AttachmentStandIns.missingKeys(message, JSON)).isEmpty();
    }

    @Test
    @DisplayName("stands in under each attached file, in a copy, leaving what was composed untouched")
    void standsInUnderEachFile() {
        Map<String, Object> composed = new HashMap<>(FORMS_PERSONALISATION);

        Map<String, Object> retry = AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, composed, missing("LOC021_0226.pdf, LOC027 blank.pdf"), JSON)
            .orElseThrow();

        assertThat(retry)
            .containsEntry("LOC021_0226.pdf", AttachmentStandIns.STAND_IN)
            .containsEntry("LOC027 blank.pdf", AttachmentStandIns.STAND_IN)
            .containsAllEntriesOf(FORMS_PERSONALISATION);
        // The composed map is what a send would freeze, so the stand-ins must never be written into it.
        assertThat(composed).isEqualTo(FORMS_PERSONALISATION);
    }

    @Test
    @DisplayName("stands in under a file whose name holds a comma, which Notify's list also separates with")
    void standsInUnderAFileNamedWithAComma() {
        Map<String, Object> retry = AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, FORMS_PERSONALISATION,
            missing("LOC021, statutory declaration.pdf, LOC027 blank.pdf"), JSON)
            .orElseThrow();

        assertThat(retry)
            .containsEntry("LOC021, statutory declaration.pdf", AttachmentStandIns.STAND_IN)
            .containsEntry("LOC027 blank.pdf", AttachmentStandIns.STAND_IN)
            .doesNotContainKey("LOC021");
    }

    @Test
    @DisplayName("stands in under both readings when a comma-split list is itself made of file names")
    void standsInUnderEveryReadingOfAnAmbiguousList() {
        Map<String, Object> retry = AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, FORMS_PERSONALISATION,
            missing("LOC021.pdf, Welsh version.pdf"), JSON)
            .orElseThrow();

        // One file or two: the text cannot say, and Notify ignores the stand-ins it did not ask for.
        assertThat(retry)
            .containsEntry("LOC021.pdf, Welsh version.pdf", AttachmentStandIns.STAND_IN)
            .containsEntry("LOC021.pdf", AttachmentStandIns.STAND_IN)
            .containsEntry("Welsh version.pdf", AttachmentStandIns.STAND_IN);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        // A key the template owns: the one a misspelt ((deed_document)) would otherwise hide until the drain.
        FormsForCompletionPersonalisation.DEED_DOCUMENT + ", LOC021_0226.pdf",
        FormsForCompletionPersonalisation.OLD_SURNAME,
        // A key nobody owns that is not a file: api's misspelling, not the portal's upload.
        "deed_documnet",
        "LOC021_0226.pdf, deed_documnet",
        // A key the template owns is never carried into the file name after it.
        FormsForCompletionPersonalisation.OLD_SURNAME + ", LOC021_0226.pdf",
        // This template's own slot. The file-name shape already refuses it: every key api owns is snake_case, so the
        // ownership test in retryWith is a second guard that no key declared today can reach on its own.
        "additional_notes",
    })
    @DisplayName("stands in for nothing when any missing key is not an attached file")
    void standsInForNothingElse(String keys) {
        assertThat(AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, FORMS_PERSONALISATION, missing(keys), JSON))
            .isEmpty();
    }

    @Test
    @DisplayName("never replaces a value the preview already carried")
    void neverReplacesACarriedValue() {
        Map<String, Object> composed = Map.of("LOC021_0226.pdf", "already here");

        assertThat(AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, composed, missing("LOC021_0226.pdf"), JSON))
            .isEmpty();
    }

    @Test
    @DisplayName("stands in for nothing on a refusal that is not a 400")
    void standsInForNothingOnAnotherStatus() {
        // The public constructor taking a cause leaves the status at 0, which is what a transport failure carries.
        NotificationClientException unreachable = new NotificationClientException(new IllegalStateException(
            "Missing personalisation: LOC021_0226.pdf"));

        assertThat(AttachmentStandIns.retryWith(
            EmailTemplate.FORMS_FOR_COMPLETION_ADULT, FORMS_PERSONALISATION, unreachable, JSON))
            .isEmpty();
    }

    /** Notify's 400 for a preview lacking these keys, as the client carries it. */
    private static NotificationClientException missing(String keys) {
        return new NotificationClientException("Status code: 400 {\"status_code\": 400, \"errors\": [{\"error\": "
            + "\"BadRequestError\", \"message\": \"Missing personalisation: " + keys + "\"}]}");
    }
}
