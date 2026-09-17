package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.format.DateTimeFormatter;

/**
 * The text of the case notes a referral writes as it is raised and withdrawn.
 *
 * <p><strong>Why a referral writes a note at all.</strong> There is no timeline concept in this domain --
 * only the human-authored Notes grid -- and the referral has to be visible where caseworkers already look,
 * so MVP reuses the grid rather than inventing a second history nobody reads. The note is a record of the
 * event, not the referral: it is written once and never revised, and the referral's own row is what a client
 * reads for the current state.
 *
 * <p><strong>Composed here rather than in the service</strong>, for one reason worth the class: the notes
 * column is bounded at {@link NoteForm#MAX_NOTE_LENGTH} and the referral's own free text is not bounded by
 * the database at all, so the two limits have to agree or a long enough referral is stored and <em>then</em>
 * refused by the note it triggers. {@link ReferralSchemas#MAX_NOTES_LENGTH} and
 * {@link ReferralSchemas#MAX_URGENCY_REASON_LENGTH} are chosen so the longest possible referral still
 * composes a note the notes validator accepts, and {@code ReferralNotesTest} pins that arithmetic against
 * the worst case rather than leaving it to the reader.
 *
 * <p><strong>The text is prose for a caseworker, not a wire format.</strong> Nothing parses it -- the
 * referral row carries every field a client needs -- so it may be reworded freely, and a change here does
 * not migrate the notes already written.
 */
public final class ReferralNotes {

    /** Dates read by a caseworker in a grid, so the day rather than the instant. */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private ReferralNotes() {
    }

    /**
     * The note for a referral being raised: what it says, and why it is urgent if it is.
     *
     * <p>The reasons are named rather than counted, because "referred for three reasons" is the one summary
     * that tells the next caseworker nothing they wanted.
     */
    public static String raised(CreateReferralInput dto) {
        StringBuilder text = new StringBuilder("Referred to the judiciary. Reasons: ");
        text.append(names(dto));
        text.append('.');
        // Each piece of the caseworker's own text goes on its own line, rather than being run into the
        // sentence before it with a full stop this class would have to add: their text is prose they wrote,
        // and one that already ended in a stop would read as "Friday..".
        if (dto.urgency() == Urgency.Urgent) {
            dto.urgencyReason().ifPresent(reason -> text.append("\nMarked urgent: ").append(reason));
        }
        dto.notes().ifPresent(notes -> text.append("\nNotes: ").append(notes));
        return text.toString();
    }

    /**
     * The note for a referral being withdrawn, naming when it was raised and what for.
     *
     * <p>Said again rather than left to the reader to pair up with the earlier note: a case may carry several
     * referrals over its life, and a bare "referral withdrawn" in a grid of thirty notes does not say which.
     *
     * <p>Takes the stored referral rather than an id, because the id is a uuid a caseworker cannot recognise
     * and the raised date and reasons are what they can.
     *
     * <p>A referral with no reasons is a backfilled one -- the source store had no such field -- so it says
     * so rather than trailing off after an empty list.
     */
    public static String withdrawn(Referral referral) {
        String reasons = referral.getReasons().isEmpty()
            ? "no reasons recorded"
            : String.join(", ", referral.getReasons());
        return "Referral to the judiciary withdrawn. It was raised on "
            + DAY.format(referral.getCreatedOn())
            + ", reasons: " + reasons + ".";
    }

    private static String names(CreateReferralInput dto) {
        return dto.reasons().stream().map(ReferralReason::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
