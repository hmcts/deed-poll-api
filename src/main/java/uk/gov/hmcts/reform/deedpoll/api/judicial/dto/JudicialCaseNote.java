package uk.gov.hmcts.reform.deedpoll.api.judicial.dto;

import uk.gov.hmcts.reform.deedpoll.entity.DeedPollNote;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One note from the application's timeline, as a judge reads it: what was recorded, by whom, and when.
 *
 * <p><strong>Read-only: there is no note route on {@code /judicial/**}.</strong> What a judge submits is the
 * direction, on the referral, and no judicial request carries note text. A judge's <em>name</em> does appear in
 * {@code createdBy} on this timeline, because claiming a referral and recording a direction each write one note
 * authored by the judge who did it -- see {@code docs/plans/referrals-api-5-lifecycle-case-notes.md} -- but the
 * prose is composed by {@code ReferralNotes}, not supplied. The timeline is here because it is where the office
 * writes what happened to an application -- the referral itself appears on it -- and a judge asked to direct a
 * case has to be able to read that history.
 *
 * <p><strong>Narrower than {@code NoteResponse}, and not by accident.</strong> The note's id is left out because
 * a judge addresses no note -- an id that no judicial route accepts would be a key published for nothing -- and
 * {@code modifiedBy}/{@code modifiedOn} are left out because they are the amendment audit trail rather than the
 * timeline: "this note was edited on Tuesday" is a fact about the office's record-keeping, which decision 8
 * excludes. {@code createdBy} stays, because an unattributed timeline is a timeline a judge cannot weigh.
 *
 * @param note the note text as stored
 * @param createdBy who wrote it, or null for a note that predates the column or was written by the service
 *     account
 * @param createdOn when it was written
 */
public record JudicialCaseNote(String note, String createdBy, LocalDateTime createdOn) {

    /** One stored note, narrowed to what a judge is shown. */
    public static JudicialCaseNote from(DeedPollNote note) {
        return new JudicialCaseNote(note.getNote(), note.getCreatedBy(), note.getCreatedOn());
    }

    /** The application's notes, in the order the domain fetched them -- newest first. */
    public static List<JudicialCaseNote> from(List<DeedPollNote> notes) {
        return notes.stream().map(JudicialCaseNote::from).toList();
    }
}
