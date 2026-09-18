package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.format.DateTimeFormatter;

/**
 * The text of the case notes a referral writes as it is raised, withdrawn, and taken back from a judge who
 * stopped working on it.
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

    /**
     * How much of a judge's display name a lapse note will quote.
     *
     * <p>The audit columns' own bound, {@code varchar(200)}, rather than a number chosen here: a name too
     * long for {@code createdBy} is already too long to be a name, and matching it means the note quotes
     * exactly as much of it as the rest of the schema keeps.
     */
    private static final int MAX_JUDGE_NAME_LENGTH = 200;

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
     * The note for a claim the inactivity sweep took back, naming the judge who was holding it.
     *
     * <p><strong>The only event on the timeline that no column records.</strong> Claiming a referral is
     * visible in {@code claimedBy} and deciding one in the direction columns, so neither needs a note to be
     * discoverable; a lapse <em>erases</em> the claim it is about, so without this the case would show a
     * referral that had quietly gone back into the pool with nothing to say it ever left. It is authored by
     * the service account rather than by a caseworker, because no person did it.
     *
     * <p><strong>Naming the judge is deliberate and it is not only for the office (Steve, 2026-09-18). Do not
     * remove the name to narrow what the judiciary can see.</strong> This note reaches other judges: it is on the
     * case's timeline, and {@code JudicialReferralDetail} returns that timeline to any judge who can see a
     * referral on the case -- so the judge who picks a lapsed referral out of the pool learns who had it before
     * them. That is the point rather than a leak. A lapse says nothing about why: the previous judge may have
     * been part-way through a complex point of law, and the one picking it up can now go and ask them instead of
     * starting again or duplicating their work. An anonymous "a claim lapsed" would cost exactly that
     * conversation. The residual disclosure -- that a judge's inactivity is legible to colleagues -- was weighed
     * and accepted for that reason; see flag 6 of {@code docs/plans/completed/referrals-api-3-judicial-surface.md}.
     *
     * <p>The judge's name is bounded here rather than trusted. It arrives from a token, the column holding
     * it is unbounded {@code text}, and the notes column is not -- so a pathological display name would make
     * the sweep compose a note the notes validator refuses, and the refusal would roll back the release
     * rather than the note. Truncating is the safe failure: the note still says a claim lapsed.
     *
     * @param judge the display name the claim was held under, blank or null on a row that somehow has none
     */
    public static String claimLapsed(String judge) {
        String held = judge == null || judge.isBlank()
            ? "an unnamed judge"
            : judge.substring(0, Math.min(judge.length(), MAX_JUDGE_NAME_LENGTH));
        return "Referral claim by " + held + " lapsed after inactivity. The referral has been returned to "
            + "the judicial pool and can be picked up again.";
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
