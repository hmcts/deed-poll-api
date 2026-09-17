package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case notes a referral writes as it is raised and withdrawn.
 *
 * <p>Container-free, so CI runs it -- and the length test below is the reason this class exists rather than
 * the text being composed inline in the service.
 */
class ReferralNotesTest {

    private static CreateReferralInput input(Map<String, Object> raw) {
        return ReferralSchemas.parseCreateReferral(raw);
    }

    private static Referral referral(List<String> reasons, LocalDateTime createdOn) {
        Referral referral = new Referral();
        referral.setReasons(reasons);
        referral.setCreatedOn(createdOn);
        return referral;
    }

    @Test
    @DisplayName("names every reason on a raised referral rather than counting them")
    void namesEveryReason() {
        String text = ReferralNotes.raised(input(Map.of(
            "reasons", List.of("Minor", "SeparatedOrEstranged"))));

        assertThat(text).isEqualTo("Referred to the judiciary. Reasons: Minor, SeparatedOrEstranged.");
    }

    @Test
    @DisplayName("says why an urgent referral is urgent, and carries the notes")
    void saysWhyAnUrgentReferralIsUrgent() {
        String text = ReferralNotes.raised(input(Map.of(
            "reasons", List.of("Other"),
            "notes", "The declarant is abroad until October",
            "urgency", "Urgent",
            "urgencyReason", "Hearing on Friday")));

        // Each piece of the caseworker's own text is on its own line, and no full stop is added after it: it
        // is prose they wrote, and one that already ended in a stop would read as "Friday..".
        assertThat(text).isEqualTo("Referred to the judiciary. Reasons: Other."
            + "\nMarked urgent: Hearing on Friday"
            + "\nNotes: The declarant is abroad until October");
    }

    @Test
    @DisplayName("says nothing about urgency on a normal referral")
    void saysNothingAboutUrgencyOnANormalReferral() {
        // The urgency reason is optional when the referral is Normal, and a caller may still have sent one --
        // which must not be reported as urgency the referral does not claim.
        String text = ReferralNotes.raised(input(Map.of(
            "reasons", List.of("Minor"),
            "urgencyReason", "Sent by a client that always fills this in")));

        assertThat(text).doesNotContain("urgent");
    }

    @Test
    @DisplayName("the longest possible referral still composes a note the notes column accepts")
    void theLongestPossibleReferralStillFits() {
        // The claim the two caps in ReferralSchemas exist for. The referral's free text is unbounded `text` in
        // the database and the note's column is not, so without this the arithmetic is a comment: a referral
        // long enough would be stored and then refused by the note it triggers, half way through the write.
        String text = ReferralNotes.raised(new CreateReferralInput(
            Arrays.asList(ReferralReason.values()),
            Optional.of("n".repeat(ReferralSchemas.MAX_NOTES_LENGTH)),
            Urgency.Urgent,
            Optional.of("u".repeat(ReferralSchemas.MAX_URGENCY_REASON_LENGTH))));

        assertThat(text.length()).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }

    @Test
    @DisplayName("a withdrawal names when the referral was raised and what for")
    void withdrawalNamesWhenItWasRaised() {
        String text = ReferralNotes.withdrawn(
            referral(List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00")));

        assertThat(text).isEqualTo("Referral to the judiciary withdrawn."
            + " It was raised on 1 September 2026, reasons: Minor, Other.");
    }

    @Test
    @DisplayName("a withdrawn backfilled referral says its reasons were never recorded")
    void withdrawnBackfilledReferralSaysSo() {
        // The source store had no reasons field, so a migrated referral has none -- and the note has to read
        // as history rather than trailing off after an empty list.
        String text = ReferralNotes.withdrawn(
            referral(List.of(), LocalDateTime.parse("2025-11-20T14:00:00")));

        assertThat(text).isEqualTo("Referral to the judiciary withdrawn."
            + " It was raised on 20 November 2025, reasons: no reasons recorded.");
    }

    @Test
    @DisplayName("a withdrawal note is acceptable to the notes column")
    void withdrawalNoteIsAcceptable() {
        List<String> everyReason = Arrays.stream(ReferralReason.values()).map(Enum::name).toList();

        String text =
            ReferralNotes.withdrawn(referral(everyReason, LocalDateTime.parse("2026-09-01T09:30:00")));

        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }
}
