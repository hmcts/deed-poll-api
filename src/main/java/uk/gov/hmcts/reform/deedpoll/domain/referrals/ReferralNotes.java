package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The text of the case notes a referral writes as it moves through its lifecycle: raised, claimed, directed,
 * actioned, withdrawn, taken back from a judge who stopped working on it, and -- where the office asked the
 * applicant whether to proceed after a refused non-publication request -- the answer that came back.
 *
 * <p><strong>Why a referral writes a note at all.</strong> There is no timeline concept in this domain --
 * only the human-authored Notes grid -- and the referral has to be visible where caseworkers already look,
 * so MVP reuses the grid rather than inventing a second history nobody reads. The note is a record of the
 * event, not the referral: it is written once and never revised, and the referral's own row is what a client
 * reads for the current state.
 *
 * <p><strong>There is one note per successful transition, and the earlier reasoning for having fewer is
 * superseded.</strong> The first three plans wrote notes only where a column did not already carry the fact --
 * raised, withdrawn, and a lapse, which erases the claim it is about -- on the grounds that a note duplicating
 * {@code claimedBy} or the direction columns could later disagree with them. Business testing rejected that: a
 * caseworker reading the Notes grid saw a referral leave and come back with nothing in between, because the
 * referral's own row states only where it is <em>now</em> and the grid is the only place that says when each
 * step happened. So {@link #claimed}, {@link #directionRecorded} and {@link #directionActioned} exist, and
 * the duplication is accepted on the same terms as the lapse note's: the prose is history, the row is the
 * current state, and nothing parses either. See {@code docs/plans/referrals-api-5-lifecycle-case-notes.md}.
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

    /**
     * Dates read by a caseworker in a grid, so the day rather than the instant.
     *
     * <p>{@link Locale#UK} rather than the default, for the reason {@code ResolvedValues} pins its own:
     * {@code MMMM} names the month in {@code Locale.getDefault(FORMAT)}, so on an image whose default locale
     * is not English every note this class writes would carry a month named in that language, stored for good
     * on a timeline nothing re-renders.
     */
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK);

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
     * <p>Takes the stored referral rather than an id, for the reason every composer below does -- see
     * {@link #identifies}.
     */
    public static String withdrawn(Referral referral) {
        return "Referral to the judiciary withdrawn. It was " + identifies(referral) + ".";
    }

    /**
     * The note for a judge taking a referral out of the shared pool.
     *
     * <p>Says that the judiciary has it rather than naming the judge who claimed it. The note's
     * {@code createdBy} is that judge -- {@code CurrentUser.noteAuthor()} is how it is written -- so naming
     * them in the prose as well would put the same name in the grid twice, and the lapse note names its judge
     * precisely because a lapse leaves no author to read it off.
     */
    public static String claimed(Referral referral) {
        return "Referral " + identifies(referral) + ", claimed by the judiciary.";
    }

    /**
     * The note for a judge's direction coming back: what they decided, and whether it goes in the Gazette.
     *
     * <p><strong>A summary, and deliberately not a copy.</strong> It does not quote
     * {@code directionsText}, which {@link ReferralSchemas#MAX_DIRECTIONS_LENGTH} allows to be twice
     * {@link NoteForm#MAX_NOTE_LENGTH} -- so copying it would make a valid direction fail the note it
     * triggers and be rolled back, and truncating it would put an incomplete second version of a judicial
     * direction on the timeline that reads as though it were the whole of it. The referral's own page shows
     * the full text, so the note points at it instead.
     *
     * <p>The decision and the Gazette answer are quoted rather than summarised away, because those two are
     * what a caseworker scanning the grid needs in order to know whether anything is waiting for them.
     *
     * <p>The Gazette answer is spelled out as Yes or No and never abbreviated to the presence of a word:
     * publication puts the applicant's former and new names in a public notice, so "not published" and
     * "nothing recorded" must not read alike. A referral whose column is somehow null therefore says so,
     * rather than being read as No -- {@link #decision} takes the same line for the same reason. Neither is
     * reachable through the direction transition, which always stores both.
     */
    public static String directionRecorded(Referral referral) {
        return "Judicial direction recorded for referral " + identifies(referral) + "."
            + " Decision: " + decision(referral) + "."
            + " Publish to the London Gazette: " + gazette(referral) + "."
            + " See the referral for the full direction.";
    }

    /**
     * The note for a judge revising the direction they gave: what they now decide, and whether it now goes in the
     * Gazette.
     *
     * <p>{@link #directionRecorded}'s summary, for its reasons: never the direction text, which may be longer than a
     * note may be, and the two answers spelled out. It does not say what the answers were before: the earlier note
     * is a few rows down in the same grid, and the audit trail holds both values. The prose differs from the
     * recorded note's in one word so a caseworker scanning the grid can tell a revision from a first direction,
     * which is what tells them the direction they may already be acting on has changed.
     */
    public static String directionRevised(Referral referral) {
        return "Judicial direction revised for referral " + identifies(referral) + "."
            + " Decision: " + decision(referral) + "."
            + " Publish to the London Gazette: " + gazette(referral) + "."
            + " See the referral for the full direction.";
    }

    /**
     * The note for the office acknowledging that a returned direction has been carried out.
     *
     * <p>The one lifecycle step that no column records at all after the fact: closing writes
     * {@code state = Closed} and the case's {@code currentReferralState} back to null, so without this the
     * timeline would show a direction arriving and then nothing, and "has anyone actioned this?" would have
     * no answer on the case. It does not repeat the decision -- the direction note above is a few rows up in
     * the same grid.
     */
    public static String directionActioned(Referral referral) {
        return "Judicial direction for referral " + identifies(referral) + ", marked as actioned.";
    }

    /**
     * The note for the applicant's answer to the confirmation email, and what the caseworker said about it.
     *
     * <p><strong>A case note rather than an edit to the referral's own {@code notes} column.</strong> That column
     * is the caseworker's text from when the referral was raised -- the explanation {@code Other} requires and the
     * general referral note -- and appending to it would rewrite what was sent to the judiciary and break the cap
     * {@link ReferralSchemas#MAX_NOTES_LENGTH} exists to keep. The answer is an event, so it goes where every
     * other referral event goes.
     *
     * <p>It names when the applicant was asked as well as what they said, because the wait is the point: the whole
     * feature exists so that a case waiting for this reply is not forgotten, and the grid is where a caseworker
     * reads how long the round trip took. A referral somehow carrying an answer but no request date says so rather
     * than inventing one -- unreachable through the service, which refuses an answer nobody asked for, and the
     * same line {@link #decision} takes for the same reason.
     *
     * <p>The outcome is spelled out rather than named alone, because {@code Withdrawn} is a word this domain
     * already uses for a referral the office pulled back: here it is the <em>applicant</em> withdrawing their
     * application, which is a different event with a refund behind it, and a grid entry reading only "Withdrawn"
     * would be read as the other one.
     *
     * <p><strong>This note reaches the judiciary, and the caseworker's own words with it.</strong> It is on the
     * case's timeline, and {@code JudicialReferralDetail} returns that timeline to any judge who may see a
     * referral on the case -- which, for the referral this note is about, is the judge who refused the
     * non-publication request. So the outcome and the free text below are legible to them, exactly as
     * {@link #claimLapsed}'s name is. That is the same decision rather than an oversight: the judge refused
     * publication on the applicant's stated wishes, so whether the applicant then went ahead, withdrew or asked
     * for a refund is the result of their own direction, and a judge who directed a case is entitled to read what
     * became of it. The confirmation <em>columns</em> are a different matter and are not published on that surface
     * -- see {@code ConfirmationSecurityTest}'s disclosure rules, which are about fields and say so.
     *
     * <p>The consequence for the caseworker's text is worth stating rather than discovering: it is office prose
     * about the applicant's reply that a judge may read, so it is not the place for anything the office would not
     * put in front of one. If that ever becomes intolerable the fix is to stop writing it here, not to filter the
     * timeline -- the note model carries no discriminator a projection could exclude one kind of note by.
     *
     * @param referral the referral as it was stored, carrying the outcome and the request date
     * @param note what the caseworker wrote about the reply, absent where they wrote nothing
     */
    public static String confirmationRecorded(Referral referral, Optional<String> note) {
        StringBuilder text = new StringBuilder("Applicant's confirmation recorded for referral ");
        text.append(identifies(referral)).append(". Asked on ").append(asked(referral));
        text.append(". Answer: ").append(outcome(referral)).append('.');
        // The caseworker's own text on its own line with no full stop added, for the reason the raised note's is:
        // it is prose they wrote, and one that already ended in a stop would read as "Friday..".
        note.ifPresent(written -> text.append("\nNotes: ").append(written));
        return text.toString();
    }

    /**
     * Which referral this note is about, in terms a caseworker can recognise: when it was raised, and what
     * for.
     *
     * <p>One helper for every composer that needs it, because the notes have to be pairable in the grid: a
     * case may carry several referrals over its life, and a claim note that identified its referral
     * differently from the direction note on the same referral would be two histories rather than one.
     *
     * <p><strong>Never the id.</strong> It is a uuid, and a caseworker cannot recognise one or match two of
     * them by eye. The raised date and reasons are what they can. Nothing of the applicant's is quoted
     * either: the note is already nested under their case, so their name in it would be a second copy of
     * personal data with a second lifetime.
     *
     * <p>A referral with no reasons is a backfilled one -- the source store had no such field -- so it says
     * so rather than trailing off after an empty list.
     *
     * <p>Reads as a fragment ("raised on ...") rather than a sentence, so each composer decides what precedes
     * it. That is what lets the withdrawal note keep the exact words it has always had.
     */
    private static String identifies(Referral referral) {
        String reasons = referral.getReasons().isEmpty()
            ? "no reasons recorded"
            : String.join(", ", referral.getReasons());
        return "raised on " + DAY.format(referral.getCreatedOn()) + ", reasons: " + reasons;
    }

    /**
     * The decision by name, or that it was never recorded -- see {@link #directionRecorded}.
     */
    private static String decision(Referral referral) {
        return referral.getDecision() == null ? "not recorded" : referral.getDecision().name();
    }

    /**
     * The answer in the words a caseworker needs, or that it was never recorded.
     *
     * <p>An exhaustive switch rather than the member name alone: a third outcome must make this fail to compile,
     * because a grid entry saying only what the enum calls it is exactly what this method exists to avoid.
     */
    private static String outcome(Referral referral) {
        ConfirmationOutcome answer = referral.getConfirmationOutcome();
        if (answer == null) {
            return "not recorded";
        }
        return switch (answer) {
            case Confirmed -> answer.name() + " -- the applicant wants the deed enrolled, knowing it will be "
                + "published in the London Gazette";
            case Withdrawn -> answer.name() + " -- the applicant does not want to proceed and asks for a refund";
        };
    }

    /**
     * When the applicant was asked, or that the request was never recorded -- see {@link #confirmationRecorded}.
     */
    private static String asked(Referral referral) {
        LocalDateTime requested = referral.getConfirmationRequestedAt();
        return requested == null ? "a date that was not recorded" : DAY.format(requested);
    }

    /** The Gazette answer as Yes or No, or that it was never recorded. */
    private static String gazette(Referral referral) {
        Boolean publish = referral.getPublishToGazette();
        if (publish == null) {
            return "not recorded";
        }
        return publish ? "Yes" : "No";
    }

    private static String names(CreateReferralInput dto) {
        return dto.reasons().stream().map(ReferralReason::name).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
