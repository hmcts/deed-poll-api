package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.Map;

/**
 * What came of turning one row's document references into Notify uploads: a personalisation ready to send, or the
 * failure that stops the send.
 *
 * <p>Sealed, with a record per answer, for the reason {@link SendOutcome} is: a single record carrying a
 * personalisation and a nullable failure would admit "here is what to send, and here is why it cannot be sent",
 * and the caller reads this to decide whether an applicant gets their deed.
 *
 * <p>Package-private. A document upload is a Notify concept -- it is a token in a personalisation map, not a
 * document-domain operation -- so nothing outside this package has a reason to name it.
 */
sealed interface ResolvedDocuments {

    /**
     * Every document key resolved, as the map {@code sendEmail} is given.
     *
     * <p><strong>A copy, and it is never written back.</strong> The frozen row still holds the document ids; this
     * map holds upload tokens, which are time-limited and would make the row unsendable a second time as well as
     * putting a token at rest in the database. {@code notify-4a} decision 3.
     *
     * @param personalisation the row's values with each document id replaced by its upload
     */
    record Ready(Map<String, Object> personalisation) implements ResolvedDocuments {
    }

    /**
     * A document this email carries could not be turned into an upload, so nothing is sent.
     *
     * @param outcome the verdict and the sentence to record on the row
     */
    record Refused(SendOutcome.Failed outcome) implements ResolvedDocuments {
    }
}
