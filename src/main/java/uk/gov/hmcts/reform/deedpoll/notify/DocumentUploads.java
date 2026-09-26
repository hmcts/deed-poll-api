package uk.gov.hmcts.reform.deedpoll.notify;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentDownload;
import uk.gov.hmcts.reform.deedpoll.domain.documents.DocumentService;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPollSupportingDocument;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;
import uk.gov.service.notify.NotificationClient;
import uk.gov.service.notify.NotificationClientException;
import uk.gov.service.notify.RetentionPeriodDuration;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the document references frozen on an outbox row into GOV.UK Notify uploads, immediately before the send.
 *
 * <p><strong>Notify does not attach a file; it inserts a time-limited download link.</strong>
 * {@code prepareUpload} takes the bytes and answers a token which goes into the personalisation where the template
 * names the key, and Notify renders a link there. That is why the templates must say "download" rather than
 * "attached" -- an email promising an attachment and delivering a link is a support call.
 *
 * <p><strong>The row is never rewritten, and this is where that invariant is kept.</strong> The frozen
 * personalisation holds supporting-document ids; this class builds a copy with tokens in their place, hands it to
 * the sender and discards it. A row holding a token could not be re-sent after the token expired, and the token
 * itself would be at rest in the database. {@code notify-4a} decision 3, and
 * {@code DocumentUploadsTest.leavesTheFrozenRowAlone} is what keeps it true.
 *
 * <p><strong>{@code confirm_email_before_download} is always true</strong> ({@code notify-4a} decision 4). A deed
 * carries the applicant's name, address and email, so a link anybody holding the URL could fetch is the wrong
 * default; Notify's confirmation step costs the applicant one field.
 *
 * <p><strong>Read through {@link DocumentService}, case-scoped, never through the store directly.</strong>
 * {@code downloadDocument(deedPollId, id)} is the one path that refuses a document belonging to another case --
 * which matters here more than anywhere, because the value being resolved came out of a stored JSON column and
 * would otherwise be a way to make one applicant's email carry another applicant's deed. The keys it is read for
 * come from {@link EmailTemplate#documentKeys()} rather than from the map, for the same reason
 * {@code CorrespondenceDocument} reads them that way: an arbitrary personalisation value is not a document id.
 *
 * <p><strong>No failure here is ever {@link NotificationFailure#PERMANENT} except an unconfigured retention, and
 * the rest divide by whether another attempt could answer differently.</strong> {@code notify-4a} decision 7: a
 * store that is briefly unavailable must not poison the case, and after notify-1 task 7 {@code PERMANENT} is the
 * one verdict whose row can be <em>replaced</em> -- so classifying a missing blob as permanent would invite a
 * second deed for one change of name. Which leaves two verdicts and a real distinction between them:
 *
 * <ul>
 *   <li><strong>A document that could not be read is {@link NotificationFailure#TEMPORARY}</strong> -- the only
 *       verdict {@link NotificationFailure#retryable()} admits, and the only one that makes decision 7's first
 *       half true. {@code TECHNICAL} is terminal on the <em>first</em> attempt, and a terminally failed row is
 *       still counted by V15's and V20's partial indexes because it is not {@code PERMANENT}: the slot stays
 *       occupied, so {@code CaseEmailService.resolveRefusedInsert} answers the caseworker's re-send with an
 *       idempotent 200 naming the dead row and the applicant never gets the deed. One unmounted volume would
 *       cost a case its only forms email with nothing in the Email tab offering to try again. Retrying is safe
 *       because every read here happens <em>before</em> {@code sendEmail}, so a retry cannot resend anything
 *       Notify has accepted; and when the attempts run out the row fails with a {@code TEMPORARY} verdict, which
 *       those indexes count exactly as they counted a {@code TECHNICAL} one -- so the protection against a second
 *       deed is unchanged.</li>
 *   <li><strong>A defect in this service is {@link NotificationFailure#TECHNICAL}</strong>: a personalisation
 *       holding no id for a key its template declares, a document larger than Notify will carry, a client that
 *       refuses one for a reason nobody enumerated. No attempt answers any of those differently, so a retry would
 *       only delay the note that tells a caseworker to look.</li>
 *   <li><strong>An unconfigured retention is {@code PERMANENT}</strong>, for exactly the reason a missing reply-to
 *       is: no retry configures anything, and {@link NotifyProperties#isProvisioned} already tells the Email tab
 *       so before a caseworker composes.</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentUploads {

    /**
     * The largest document Notify will take, and the reason the size is checked here rather than by the client.
     *
     * <p>{@code prepareUpload} throws a {@link NotificationClientException} carrying status 413 and the message
     * "File is larger than 2MB", which {@link NotificationFailure#classify(int)} sends to
     * {@link NotificationFailure#TECHNICAL} by its default branch. That verdict is right, but arrived at by
     * accident and with a message that names no document -- and a caseworker reading the Email tab needs to know
     * <em>which</em> file and <em>how</em> big. So the size is measured first and the refusal says both.
     * {@code notify-4a} decision 8.
     */
    static final int MAX_UPLOAD_BYTES = 2 * 1024 * 1024;

    private final DocumentService documents;
    private final NotifyProperties properties;

    public DocumentUploads(DocumentService documents, NotifyProperties properties) {
        this.documents = documents;
        this.properties = properties;
    }

    /**
     * The personalisation to send, with every document key resolved to an upload.
     *
     * <p>A template with no document keys is answered with the row's own map untouched, so nothing about the
     * three emails that carry no file changes -- not even the identity of the map they are sent with.
     *
     * @param notification the claimed row, whose personalisation is read and not modified
     * @param template the row's template, which declares which keys are documents
     */
    ResolvedDocuments resolve(Notification notification, EmailTemplate template) {
        Map<String, Object> frozen = notification.getPersonalisation();
        if (template.documentKeys().isEmpty()) {
            return new ResolvedDocuments.Ready(frozen);
        }

        Optional<RetentionPeriodDuration> retention = properties.retention();
        if (retention.isEmpty()) {
            // The number is a business decision about how long an applicant has to print, gather two witnesses and
            // find a commissioner of oaths, so this service will not choose one. Permanent because no retry
            // configures it, and the tab already reports the template unprovisioned.
            log.error("Notification {} for case {} is {}, which carries a document, but "
                          + "notify.document-retention-weeks is unset or outside the range Notify accepts",
                      notification.getId(), notification.getDeedPollId(), template.key());
            return new ResolvedDocuments.Refused(new SendOutcome.Failed(
                NotificationFailure.PERMANENT,
                "no usable document retention period is configured, and the " + template.key()
                    + " email carries a document"));
        }

        Map<String, Object> resolved = new LinkedHashMap<>(frozen);
        for (String key : template.documentKeys()) {
            ResolvedDocuments one = upload(notification, key, retention.get());
            if (one instanceof ResolvedDocuments.Refused refused) {
                return refused;
            }
            resolved.putAll(((ResolvedDocuments.Ready) one).personalisation());
        }
        return new ResolvedDocuments.Ready(resolved);
    }

    /**
     * One document key resolved, or the refusal that stops the whole send.
     *
     * <p>One missing document refuses the email rather than sending it with a link short: the template names the
     * key, so Notify would refuse a personalisation without it anyway -- and a send that quietly dropped the deed
     * would be an applicant told to sign a document they were never given.
     *
     * <p>Answers {@link ResolvedDocuments.Ready} holding this key alone, which {@link #resolve} merges into the
     * copy it is building: the alternative was a second sealed type for one value, or a nullable return with the
     * failure beside it.
     */
    private ResolvedDocuments upload(Notification notification, String key, RetentionPeriodDuration retention) {
        Object reference = notification.getPersonalisation().get(key);
        if (!(reference instanceof String id) || id.isBlank()) {
            // The row was enqueued without the document reference its template declares, which is a defect in this
            // service rather than an operational failure -- so it is logged at error and named as what it is.
            // Technical rather than permanent all the same: permanent rows can be replaced, and replacing this one
            // would enqueue a second deed for one change of name.
            log.error("Notification {} for case {} declares document key {} but its personalisation holds no "
                          + "document id", notification.getId(), notification.getDeedPollId(), key);
            return refused("the " + key + " document reference is missing from this email");
        }

        DeedPollSupportingDocument row;
        byte[] content;
        try {
            // Case-scoped, so a reference to another case's document is a NotFoundError rather than a document.
            DocumentDownload download = documents.downloadDocument(notification.getDeedPollId(), id);
            row = download.document();
            try (InputStream stream = download.stream()) {
                // One byte more than the cap, so an oversized document is detected without the whole of it being
                // held in memory to find that out.
                content = stream.readNBytes(MAX_UPLOAD_BYTES + 1);
            }
        } catch (IOException | RuntimeException unreadable) {
            // Includes NotFoundError for a row or blob that is not there, and UncheckedIOException for a store
            // that would not read. Temporary, which is decision 7's "must not poison the case" taken literally:
            // TECHNICAL would fail the row on the first attempt, and a failed row still holds its exclusion-group
            // slot, so the re-send is an idempotent 200 on a row that is never going anywhere.
            //
            // NotFoundError is not split out and classified terminally, even though a blob that is really gone
            // will not come back: an unmounted volume answers exactly the same way -- LocalFilesystemDocumentStore
            // throws NotFoundError for a path that is not a regular file -- so this service cannot tell the two
            // apart, and the shape it cannot tell apart is the one decision 7 is about. The cost of guessing
            // "transient" is eight reads over three hours and a later note; the cost of guessing "gone" is an
            // applicant who is never sent their deed.
            //
            // Catching it is only half of containing it: downloadDocument is @Transactional and participates in
            // the drain's transaction, so Spring's default would mark that transaction rollback-only before this
            // block ever ran -- taking down rows Notify had already accepted in the same batch. Its
            // `noRollbackFor` is what makes this catch mean what it says; see DocumentService.downloadDocument.
            log.warn("Notification {} for case {} could not read its {} document {}",
                     notification.getId(), notification.getDeedPollId(), key, id, unreadable);
            return unavailable("the " + key + " document could not be read");
        }

        if (content.length > MAX_UPLOAD_BYTES) {
            // Before sendEmail, and naming the document and its size, because the client's own refusal names
            // neither (decision 8). The document's name and not the applicant's filename: this sentence is
            // displayed to a caseworker on the Email tab. The size is the recorded column rather than what was
            // read, because readNBytes deliberately stops one byte over the cap and so cannot say how big.
            log.warn("Notification {} for case {} carries {} ({}, {}), which is larger than the {} bytes Notify "
                         + "takes", notification.getId(), notification.getDeedPollId(), row.getName(), key,
                     size(row), MAX_UPLOAD_BYTES);
            return refused("the " + key + " document \"" + row.getName() + "\" (" + size(row) + ") is more than "
                + "the " + MAX_UPLOAD_BYTES + " bytes GOV.UK Notify will send");
        }

        try {
            // The filename an applicant sees when they save it, which is the document's originalName -- what
            // DeedFilename composed -- rather than its storage key or its type. `true` is decision 4.
            return new ResolvedDocuments.Ready(Map.of(
                key, NotificationClient.prepareUpload(content, filename(row), true, retention)));
        } catch (NotificationClientException notAccepted) {
            // The client refuses a document it will not carry, and the only documented reason is the size this
            // method has already measured -- so reaching here means the client refuses it for a reason nobody
            // enumerated. Technical, which is what NotificationFailure.classify answers for it anyway.
            log.error("Notify would not prepare the {} document of notification {} for case {}",
                      key, notification.getId(), notification.getDeedPollId(), notAccepted);
            return refused("GOV.UK Notify would not accept the " + key + " document (status "
                + notAccepted.getHttpResult() + ")");
        }
    }

    /**
     * How big the document is, in the words a caseworker reads.
     *
     * <p>The {@code sizeBytes} column, which {@code uploadDocument} records from the file it stored and no patch
     * can change. A row without one still has to produce a readable parenthesis -- the refusal is displayed to a
     * caseworker -- so it says the size is unrecorded rather than printing {@code null}.
     */
    private static String size(DeedPollSupportingDocument document) {
        Integer bytes = document.getSizeBytes();
        return bytes == null ? "size unrecorded" : bytes + " bytes";
    }

    /** The name the applicant's own computer will show, falling back to the recorded name. */
    private static String filename(DeedPollSupportingDocument document) {
        String original = document.getOriginalName();
        return original == null || original.isBlank() ? document.getName() : original;
    }

    /**
     * A refusal no further attempt would answer differently: this service, or the document itself, is wrong.
     *
     * <p>Terminal, and terminal is the point -- but not {@code PERMANENT}, so the row keeps its exclusion-group
     * slot and the case cannot be given a second deed to swear.
     */
    private static ResolvedDocuments.Refused refused(String reason) {
        return new ResolvedDocuments.Refused(new SendOutcome.Failed(NotificationFailure.TECHNICAL, reason));
    }

    /**
     * A refusal the next drain should try again: the document could not be read <em>now</em>.
     *
     * <p>The one verdict {@link NotificationFailure#retryable()} admits, which is why it is a separate factory and
     * not a second argument -- the difference between this and {@link #refused} is whether an applicant eventually
     * gets their deed, and the class javadoc is where the argument lives.
     */
    private static ResolvedDocuments.Refused unavailable(String reason) {
        return new ResolvedDocuments.Refused(new SendOutcome.Failed(NotificationFailure.TEMPORARY, reason));
    }
}
