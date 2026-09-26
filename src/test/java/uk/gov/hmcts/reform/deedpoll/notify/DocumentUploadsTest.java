package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.json.JSONObject;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The drain-time seam: a stored document becomes a GOV.UK Notify upload, and the frozen row does not change.
 *
 * <p><strong>Container-free, with a mocked {@link DocumentService} and no Notify at all.</strong>
 * {@code prepareUpload} is a static, local computation -- it base64-encodes the bytes into a JSON object and makes
 * no network call -- so what leaves this class can be asserted exactly without a stub, and the send itself is
 * {@code NotifyEmailSenderTest}'s.
 *
 * <p>notify-4a's Task 1, built here because notify-4b's task 4 needs it and the plan says to build it for one
 * document rather than stop.
 */
class DocumentUploadsTest {

    private static final String CASE_ID = "case-1";

    private static final String DEED_ID = "aa6d1f80-0000-4000-8000-0000000000de";

    private static final String FILENAME = "LOC020-change-of-name-deed-KBDP2026-00001.docx";

    private static final byte[] CONTENT = "PK\u0003\u0004 a deed".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private DocumentService documents;

    private DocumentUploads uploads;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentService.class);
        uploads = new DocumentUploads(documents, configured(StubNotify.RETENTION_WEEKS));
    }

    @Test
    @DisplayName("replaces the document id with an upload carrying the bytes, the filename and the retention")
    void replacesTheIdWithAnUpload() {
        stored(CONTENT);

        ResolvedDocuments resolved = uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT);

        Map<String, Object> personalisation = ready(resolved);
        // Every other key is carried through untouched: this class resolves documents and recomputes nothing.
        assertThat(personalisation).containsEntry("reference_number", "KBDP2026-00001");
        Object upload = personalisation.get("deed_document");
        assertThat(upload).isInstanceOf(JSONObject.class);
        JSONObject prepared = (JSONObject) upload;
        assertThat(prepared.getString("filename"))
            .as("the name the applicant's own computer shows, not a UUID and not the storage key")
            .isEqualTo(FILENAME);
        // Decision 4, and it is not negotiable per document: a deed carries the applicant's name, address and
        // email, so a link anybody holding the URL could fetch is the wrong default.
        assertThat(prepared.getBoolean("confirm_email_before_download")).isTrue();
        // Decision 5: the period is configuration and reaches Notify in the unit it documents.
        assertThat(prepared.getString("retention_period"))
            .isEqualTo(StubNotify.RETENTION_WEEKS + " weeks");
        // Read case-scoped, which is what stops a stored id resolving to another applicant's deed.
        verify(documents).downloadDocument(CASE_ID, DEED_ID);
    }

    @Test
    @DisplayName("leaves the frozen row alone, because a row holding an upload token could not be re-sent")
    void leavesTheFrozenRowAlone() {
        stored(CONTENT);
        Notification frozen = row();
        Map<String, Object> before = Map.copyOf(frozen.getPersonalisation());

        uploads.resolve(frozen, EmailTemplate.FORMS_FOR_COMPLETION_ADULT);

        // The invariant the whole design rests on, and it would break silently: the resolved map is built, passed
        // and discarded. A row whose personalisation held a token would be a row that stopped being sendable once
        // the token expired, and a token at rest in the database.
        assertThat(frozen.getPersonalisation()).isEqualTo(before).containsEntry("deed_document", DEED_ID);
    }

    @Test
    @DisplayName("answers with the row's own map for a template that carries no document, reading nothing")
    void carriesNoDocumentsForATemplateWithNone() {
        Notification chase = row();
        chase.setTemplate(EmailTemplate.CHASE_MISSING_DOCUMENTS.name());

        ResolvedDocuments resolved = uploads.resolve(chase, EmailTemplate.CHASE_MISSING_DOCUMENTS);

        // Not merely equal -- the same map, so nothing about the three emails that carry no file changes, not even
        // the identity of what they are sent with.
        assertThat(ready(resolved)).isSameAs(chase.getPersonalisation());
        verifyNoInteractions(documents);
    }

    @Test
    @DisplayName("a document that cannot be read is retried, so the store being down does not poison the case")
    void anUnreadableDocumentIsRetried() {
        when(documents.downloadDocument(CASE_ID, DEED_ID))
            .thenThrow(new NotFoundError("This document has no attached file"));

        // Decision 7's first half, and it needs TEMPORARY to be true rather than merely said. TECHNICAL is terminal
        // on the first attempt and a terminally failed row still occupies V15's and V20's partial indexes, so
        // CaseEmailService.resolveRefusedInsert answers the caseworker's re-send with an idempotent 200 naming that
        // dead row: one unmounted volume and the applicant is never sent their deed. Not PERMANENT either, for the
        // opposite reason -- after notify-1 task 7 that is the one verdict whose row can be REPLACED, which would
        // invite a second deed for one change of name. When the eight attempts run out this row fails with a
        // TEMPORARY verdict, which those indexes count exactly as they counted a TECHNICAL one.
        SendOutcome.Failed refused = refusal(uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT));
        assertThat(refused).isEqualTo(new SendOutcome.Failed(
            NotificationFailure.TEMPORARY, "the deed_document document could not be read"));
        // The behaviour and not the label, because the label is only worth what retryable() makes of it.
        assertThat(refused.failure().retryable())
            .as("a store that was briefly unavailable must leave the row claimable by the next drain")
            .isTrue();
    }

    @Test
    @DisplayName("a store that will not read is retried too, rather than escaping the drain")
    void storeThatWillNotReadIsRetried() {
        DeedPollSupportingDocument deed = deedRow();
        when(documents.downloadDocument(CASE_ID, DEED_ID)).thenReturn(new DocumentDownload(deed, failingStream()));

        // LocalFilesystemDocumentStore throws UncheckedIOException, and CDAM will throw something of its own. Any
        // of them must become a verdict rather than propagate: the drain runs a whole batch in one transaction, and
        // an exception here would be recorded as TECHNICAL anyway -- terminal, and with a message naming an
        // exception class instead of a document.
        assertThat(refusal(uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT)).failure())
            .isEqualTo(NotificationFailure.TEMPORARY);
    }

    @Test
    @DisplayName("a document over 2 MB is refused before the send, naming the document and the limit")
    void anOversizedDocumentIsRefusedWithAUsefulMessage() {
        stored(new byte[DocumentUploads.MAX_UPLOAD_BYTES + 1]);

        SendOutcome.Failed refused = refusal(uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT));

        // Decision 8. The client's own refusal is a NotificationClientException carrying status 413 and the message
        // "File is larger than 2MB", which classify() sends to TECHNICAL by its default branch -- the right verdict,
        // arrived at by accident and naming no document. This sentence is displayed to a caseworker, so it names
        // which file and what the limit is.
        assertThat(refused.failure()).isEqualTo(NotificationFailure.TECHNICAL);
        assertThat(refused.reason())
            .contains("deed_document")
            .contains("LOC020 Change of name deed")
            .contains(String.valueOf(DocumentUploads.MAX_UPLOAD_BYTES));
    }

    @Test
    @DisplayName("a document of exactly 2 MB is sent, because the limit is what Notify refuses above")
    void documentOfExactlyTheLimitIsSent() {
        // The boundary, stated rather than inferred: the client refuses strictly more than 2,097,152 bytes, so a
        // check that refused the limit itself would reject a document Notify would have taken.
        stored(new byte[DocumentUploads.MAX_UPLOAD_BYTES]);

        assertThat(ready(uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT)))
            .containsKey("deed_document");
    }

    @Test
    @DisplayName("no configured retention is PERMANENT, because no retry configures one")
    void noRetentionIsPermanent() {
        DocumentUploads unconfigured = new DocumentUploads(documents, configured(null));

        // The one failure here that is not TECHNICAL, and it is the reply-to's shape exactly: the number is a
        // business decision this service will not make, no retry supplies it, and isProvisioned already reports the
        // template unprovisioned so a caseworker is told before they compose. Nothing is read, either.
        assertThat(refusal(unconfigured.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT)))
            .isEqualTo(new SendOutcome.Failed(
                NotificationFailure.PERMANENT,
                "no usable document retention period is configured, and the forms-for-completion-adult email "
                    + "carries a document"));
        verify(documents, never()).downloadDocument(anyString(), anyString());
    }

    @Test
    @DisplayName("a retention Notify would refuse is the same PERMANENT refusal as none at all")
    void anOutOfRangeRetentionIsTheSameRefusal() {
        // 104 weeks is past the 78 Notify documents, and the client checks only the size locally -- so without the
        // range check in NotifyProperties.retention this row would reach Notify, take a 400, and be classified
        // PERMANENT with a reason naming a status code, on a case whose deed had already been generated and stored.
        DocumentUploads outOfRange = new DocumentUploads(documents, configured(104));

        assertThat(refusal(outOfRange.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT)))
            .isEqualTo(new SendOutcome.Failed(
                NotificationFailure.PERMANENT,
                "no usable document retention period is configured, and the forms-for-completion-adult email "
                    + "carries a document"));
        verify(documents, never()).downloadDocument(anyString(), anyString());
    }

    @Test
    @DisplayName("a row whose personalisation names no document is refused rather than sent short")
    void rowWithNoDocumentReferenceIsRefused() {
        Notification missing = row();
        missing.getPersonalisation().remove("deed_document");

        // A defect in this service rather than an operational failure -- the send freezes the id -- but refused
        // rather than sent: the template names the key, so Notify would refuse the personalisation anyway, and a
        // send that quietly dropped the deed would tell an applicant to sign a document they were never given.
        //
        // TECHNICAL and deliberately not the TEMPORARY an unreadable document gets: the row's own personalisation
        // is what is wrong, so no attempt answers differently and retrying would only postpone the note that tells
        // a caseworker to look. Terminal without being replaceable, which is what keeps one deed per case.
        assertThat(refusal(uploads.resolve(missing, EmailTemplate.FORMS_FOR_COMPLETION_ADULT)))
            .isEqualTo(new SendOutcome.Failed(
                NotificationFailure.TECHNICAL, "the deed_document document reference is missing from this email"));
        verifyNoInteractions(documents);
    }

    @Test
    @DisplayName("falls back to the recorded name when a document has no original filename")
    void fallsBackToTheRecordedName() {
        DeedPollSupportingDocument deed = deedRow();
        deed.setOriginalName(null);
        when(documents.downloadDocument(eq(CASE_ID), eq(DEED_ID)))
            .thenReturn(new DocumentDownload(deed, new ByteArrayInputStream(CONTENT)));

        // originalName is nullable on the entity, and a null filename reaches Notify as JSON null -- which it
        // answers 400 for. The recorded name is a worse filename than DeedFilename's and an immeasurably better
        // one than none.
        assertThat(((JSONObject) ready(uploads.resolve(row(), EmailTemplate.FORMS_FOR_COMPLETION_ADULT))
            .get("deed_document")).getString("filename"))
            .isEqualTo("LOC020 Change of name deed");
    }

    private void stored(byte[] content) {
        when(documents.downloadDocument(CASE_ID, DEED_ID))
            .thenReturn(new DocumentDownload(deedRow(), new ByteArrayInputStream(content)));
    }

    private static Map<String, Object> ready(ResolvedDocuments resolved) {
        assertThat(resolved).isInstanceOf(ResolvedDocuments.Ready.class);
        return ((ResolvedDocuments.Ready) resolved).personalisation();
    }

    private static SendOutcome.Failed refusal(ResolvedDocuments resolved) {
        assertThat(resolved).isInstanceOf(ResolvedDocuments.Refused.class);
        return ((ResolvedDocuments.Refused) resolved).outcome();
    }

    private static NotifyProperties configured(Integer retentionWeeks) {
        return new NotifyProperties(
            "https://notify.invalid",
            "a-key",
            Map.of(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.key(), "a-template-uuid"),
            "a-reply-to-id",
            retentionWeeks);
    }

    private static DeedPollSupportingDocument deedRow() {
        DeedPollSupportingDocument deed = new DeedPollSupportingDocument();
        deed.setId(DEED_ID);
        deed.setName("LOC020 Change of name deed");
        deed.setOriginalName(FILENAME);
        deed.setMimeType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        return deed;
    }

    private static Notification row() {
        Notification notification = new Notification();
        notification.setId(UUID.fromString("e5dd8b51-b705-41ea-8246-1db549b4e6bd"));
        notification.setDeedPollId(CASE_ID);
        notification.setTemplate(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.name());
        notification.setRecipient("applicant@example.com");
        Map<String, Object> personalisation = new LinkedHashMap<>();
        personalisation.put("reference_number", "KBDP2026-00001");
        personalisation.put("publish_to_london_gazette", true);
        personalisation.put("deed_document", DEED_ID);
        notification.setPersonalisation(personalisation);
        notification.setAttempts(1);
        notification.setCreatedBy("A Caseworker");
        return notification;
    }

    /**
     * A blob the store will not read, which is what an unavailable {@code DocumentStore} looks like from here.
     */
    private static InputStream failingStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("the store is unavailable");
            }
        };
    }
}
