package uk.gov.hmcts.reform.deedpoll.domain.referrals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ConfirmationOutcome;
import uk.gov.hmcts.reform.deedpoll.entity.enums.MasterDecision;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Urgency;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The case notes a referral writes at each step of its lifecycle: raised, claimed, directed, actioned,
 * withdrawn, and taken back from an inactive judge.
 *
 * <p>Container-free, so CI runs it -- and the length tests below are the reason this class exists rather than
 * the text being composed inline in the service.
 */
class ReferralNotesTest {

    /** Every reason there is, the worst case for a note that names them all. */
    private static final List<String> EVERY_REASON =
        Arrays.stream(ReferralReason.values()).map(Enum::name).toList();

    private static CreateReferralInput input(Map<String, Object> raw) {
        return ReferralSchemas.parseCreateReferral(raw);
    }

    private static Referral referral(List<String> reasons, LocalDateTime createdOn) {
        Referral referral = new Referral();
        referral.setReasons(reasons);
        referral.setCreatedOn(createdOn);
        return referral;
    }

    /**
     * A referral the office has asked to confirm, with the answer recorded on it.
     *
     * @param requested when the confirmation email was owed, or null for a row that somehow carries an answer
     *     with no request behind it
     */
    private static Referral asked(List<String> reasons,
                                  LocalDateTime createdOn,
                                  LocalDateTime requested,
                                  ConfirmationOutcome outcome) {

        Referral referral = referral(reasons, createdOn);
        referral.setConfirmationRequestedAt(requested);
        referral.setConfirmationOutcome(outcome);
        return referral;
    }

    /**
     * A referral as {@code decide} leaves it: state aside, the direction columns all written.
     */
    private static Referral decided(
        List<String> reasons, LocalDateTime createdOn, MasterDecision decision, boolean gazette) {

        Referral referral = referral(reasons, createdOn);
        referral.setDecision(decision);
        referral.setPublishToGazette(gazette);
        return referral;
    }

    @Test
    @DisplayName("names every reason on a raised referral rather than counting them")
    void namesEveryReason() {
        String text = ReferralNotes.raised(input(Map.of(
            "reasons", List.of("Minor", "SeparatedOrEstranged"))));

        assertThat(text).isEqualTo("Referred to a Master. Reasons: Minor, SeparatedOrEstranged.");
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
        assertThat(text).isEqualTo("Referred to a Master. Reasons: Other."
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

        assertThat(text).isEqualTo("Referral to a Master withdrawn."
            + " It was raised on 1 September 2026, reasons: Minor, Other.");
    }

    @Test
    @DisplayName("a withdrawn backfilled referral says its reasons were never recorded")
    void withdrawnBackfilledReferralSaysSo() {
        // The source store had no reasons field, so a migrated referral has none -- and the note has to read
        // as history rather than trailing off after an empty list.
        String text = ReferralNotes.withdrawn(
            referral(List.of(), LocalDateTime.parse("2025-11-20T14:00:00")));

        assertThat(text).isEqualTo("Referral to a Master withdrawn."
            + " It was raised on 20 November 2025, reasons: no reasons recorded.");
    }

    @Test
    @DisplayName("a withdrawal note is acceptable to the notes column")
    void withdrawalNoteIsAcceptable() {
        String text =
            ReferralNotes.withdrawn(referral(EVERY_REASON, LocalDateTime.parse("2026-09-01T09:30:00")));

        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }

    @Test
    @DisplayName("a claim says which referral was claimed, without naming the judge the note is authored by")
    void claimSaysWhichReferralWasClaimed() {
        String text = ReferralNotes.claimed(
            referral(List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00")));

        assertThat(text)
            .isEqualTo("Referral raised on 1 September 2026, reasons: Minor, Other,"
                + " claimed by a Master.");
    }

    @Test
    @DisplayName("a claim on a backfilled referral says its reasons were never recorded")
    void claimOnBackfilledReferralSaysSo() {
        String text = ReferralNotes.claimed(referral(List.of(), LocalDateTime.parse("2025-11-20T14:00:00")));

        assertThat(text)
            .isEqualTo("Referral raised on 20 November 2025, reasons: no reasons recorded,"
                + " claimed by a Master.");
    }

    @Test
    @DisplayName("a direction records the decision, the Gazette answer and where the full text is")
    void directionRecordsTheDecisionAndTheGazetteAnswer() {
        String text = ReferralNotes.directionRecorded(decided(
            List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00"),
            MasterDecision.Approved, false));

        assertThat(text)
            .isEqualTo("Master’s direction recorded for referral raised on 1 September 2026,"
                + " reasons: Minor, Other. Decision: Approved."
                + " Publish to the London Gazette: No. See the referral for the full direction.");
    }

    @Test
    @DisplayName("a direction reports either decision and either Gazette answer, spelled out")
    void directionReportsEitherDecisionAndEitherGazetteAnswer() {
        // Both answers in both fields, because the two are independent: a rejection can still be gazetted and
        // an approval can be withheld, so neither may be inferred from the other.
        for (MasterDecision decision : MasterDecision.values()) {
            for (boolean gazette : new boolean[] {true, false}) {
                String text = ReferralNotes.directionRecorded(decided(
                    List.of("Minor"), LocalDateTime.parse("2026-09-01T09:30:00"), decision, gazette));

                assertThat(text)
                    .as("%s, gazette %s", decision, gazette)
                    .contains("Decision: " + decision.name() + ".")
                    .contains("Publish to the London Gazette: " + (gazette ? "Yes" : "No") + ".");
            }
        }
    }

    @Test
    @DisplayName("a direction with nothing recorded in its columns says so rather than reading as a refusal")
    void directionWithNothingRecordedSaysSo() {
        // Not reachable through decide(), which stores both columns from a payload where both were required --
        // but the columns are nullable for the referrals that have no direction, and a hand-repaired row read
        // as "Rejected, not published" would be a decision nobody made.
        String text = ReferralNotes.directionRecorded(
            referral(List.of("Minor"), LocalDateTime.parse("2026-09-01T09:30:00")));

        assertThat(text)
            .contains("Decision: not recorded.")
            .contains("Publish to the London Gazette: not recorded.");
    }

    @Test
    @DisplayName("a direction note never quotes the direction itself, however long it is")
    void directionNoteNeverQuotesTheDirectionItself() {
        // The claim decision 5 of the plan rests on. directionsText may be 4,000 characters and the notes
        // column holds 2,000, so quoting it would make a valid direction fail the note it triggers and be
        // rolled back -- and truncating it would put half a judicial direction on the timeline.
        String direction = "d".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
        Referral referral = decided(
            EVERY_REASON, LocalDateTime.parse("2026-09-01T09:30:00"), MasterDecision.Approved, true);
        referral.setDirectionsText(direction);

        String text = ReferralNotes.directionRecorded(referral);

        assertThat(direction.length()).isGreaterThan(NoteForm.MAX_NOTE_LENGTH);
        assertThat(text).doesNotContain("dd").contains("See the referral for the full direction.");
        assertThat(text.length()).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }

    @Test
    @DisplayName("a revised direction records the new decision and Gazette answer, in the recorded note's words")
    void revisedDirectionRecordsTheNewDecision() {
        String text = ReferralNotes.directionRevised(decided(
            List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00"),
            MasterDecision.Rejected, true));

        assertThat(text)
            .isEqualTo("Master’s direction revised for referral raised on 1 September 2026,"
                + " reasons: Minor, Other. Decision: Rejected."
                + " Publish to the London Gazette: Yes. See the referral for the full direction.");
    }

    @Test
    @DisplayName("a revised direction note never quotes the direction itself, however long it is")
    void revisedDirectionNoteNeverQuotesTheDirectionItself() {
        String direction = "d".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH);
        Referral referral = decided(
            EVERY_REASON, LocalDateTime.parse("2026-09-01T09:30:00"), MasterDecision.Approved, true);
        referral.setDirectionsText(direction);

        String text = ReferralNotes.directionRevised(referral);

        assertThat(text).doesNotContain("dd");
        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }

    @Test
    @DisplayName("a withdrawn direction names the decision it withdrew, and says the judge has the referral back")
    void withdrawnDirectionNamesTheWithdrawnDecision() {
        String text = ReferralNotes.directionWithdrawn(decided(
            List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00"),
            MasterDecision.Approved, false));

        assertThat(text)
            .isEqualTo("Master’s direction withdrawn for referral raised on 1 September 2026,"
                + " reasons: Minor, Other. The withdrawn decision was: Approved."
                + " Publish to the London Gazette: No. The referral is back with the Master to direct again.");
    }

    @Test
    @DisplayName("a withdrawn direction note never quotes the direction itself, however long it is")
    void withdrawnDirectionNoteNeverQuotesTheDirectionItself() {
        Referral referral = decided(
            EVERY_REASON, LocalDateTime.parse("2026-09-01T09:30:00"), MasterDecision.Approved, true);
        referral.setDirectionsText("d".repeat(ReferralSchemas.MAX_DIRECTIONS_LENGTH));

        String text = ReferralNotes.directionWithdrawn(referral);

        assertThat(text).doesNotContain("dd");
        assertThat(NoteForm.validateNoteText(text)).isEmpty();
    }

    @Test
    @DisplayName("an actioned direction says which referral's, and does not repeat the decision")
    void actionedDirectionSaysWhichReferrals() {
        String text = ReferralNotes.directionActioned(decided(
            List.of("Minor", "Other"), LocalDateTime.parse("2026-09-01T09:30:00"),
            MasterDecision.Approved, true));

        assertThat(text)
            .isEqualTo("Master’s direction for referral raised on 1 September 2026,"
                + " reasons: Minor, Other, marked as actioned.");
    }

    @Test
    @DisplayName("an actioned direction on a backfilled referral says its reasons were never recorded")
    void actionedDirectionOnBackfilledReferralSaysSo() {
        String text = ReferralNotes.directionActioned(
            referral(List.of(), LocalDateTime.parse("2025-11-20T14:00:00")));

        assertThat(text)
            .isEqualTo("Master’s direction for referral raised on 20 November 2025,"
                + " reasons: no reasons recorded, marked as actioned.");
    }

    @Test
    @DisplayName("every lifecycle note is acceptable to the notes column, for every referral there is")
    void everyLifecycleNoteIsAcceptableToTheNotesColumn() {
        // No generated note may make an otherwise valid transition fail: the note shares the transition's
        // transaction, so a refusal here would roll back the claim, direction or closure that caused it.
        for (List<String> reasons : List.of(EVERY_REASON, List.<String>of())) {
            Referral referral = decided(
                reasons, LocalDateTime.parse("2026-09-01T09:30:00"), MasterDecision.Rejected, true);

            for (String text : List.of(ReferralNotes.claimed(referral),
                                       ReferralNotes.directionRecorded(referral),
                                       ReferralNotes.directionActioned(referral),
                                       ReferralNotes.withdrawn(referral))) {
                assertThat(text.length())
                    .as("%s, reasons %s", text, reasons)
                    .isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
                assertThat(NoteForm.validateNoteText(text)).as(text).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("a confirmation note says which referral, when the applicant was asked, and what they said")
    void confirmationNoteSaysWhatTheApplicantSaid() {
        Referral referral = asked(
            List.of("NonPublication"), LocalDateTime.parse("2026-05-03T09:30:00"),
            LocalDateTime.parse("2026-05-10T11:00:00"), ConfirmationOutcome.Confirmed);

        assertThat(ReferralNotes.confirmationRecorded(referral, Optional.of("Replied by email.")))
            .isEqualTo("Applicant's confirmation recorded for referral raised on 3 May 2026,"
                + " reasons: NonPublication. Asked on 10 May 2026."
                + " Answer: Confirmed -- the applicant wants the deed enrolled, knowing it will be published in"
                + " the London Gazette."
                + "\nNotes: Replied by email.");
    }

    @Test
    @DisplayName("a withdrawal by the applicant is spelled out, so it does not read as a withdrawn referral")
    void applicantWithdrawalIsSpelledOut() {
        // Withdrawn is also a referral state -- the office pulling a referral back -- and this is the applicant
        // withdrawing their application with a refund behind it. A grid entry saying only "Withdrawn" would be
        // read as the other event.
        Referral referral = asked(
            List.of("NonPublication"), LocalDateTime.parse("2026-05-03T09:30:00"),
            LocalDateTime.parse("2026-05-10T11:00:00"), ConfirmationOutcome.Withdrawn);

        assertThat(ReferralNotes.confirmationRecorded(referral, Optional.empty()))
            .endsWith("Answer: Withdrawn -- the applicant does not want to proceed and asks for a refund.")
            .doesNotContain("\nNotes:");
    }

    @Test
    @DisplayName("a confirmation on a referral with no request date says so rather than inventing one")
    void confirmationWithNoRequestDateSaysSo() {
        // Not reachable through the service, which refuses an answer nobody asked for -- but the composer must
        // not throw on a row repaired by hand, because the note shares the recording's transaction.
        Referral referral = asked(
            List.of("NonPublication"), LocalDateTime.parse("2026-05-03T09:30:00"), null,
            ConfirmationOutcome.Confirmed);

        assertThat(ReferralNotes.confirmationRecorded(referral, Optional.empty()))
            .contains("Asked on a date that was not recorded.");
    }

    @Test
    @DisplayName("a confirmation with no outcome on it says so rather than composing an empty answer")
    void confirmationWithNoOutcomeSaysSo() {
        // The other half of the same defence, and unreachable the same way -- the service writes the outcome
        // before the note. A composer that dereferenced the missing member would throw inside the recording's
        // transaction, so the row the note is about would roll back over a sentence.
        Referral referral = asked(
            List.of("NonPublication"), LocalDateTime.parse("2026-05-03T09:30:00"),
            LocalDateTime.parse("2026-05-10T11:00:00"), null);

        assertThat(ReferralNotes.confirmationRecorded(referral, Optional.empty()))
            .endsWith("Answer: not recorded.");
    }

    @Test
    @DisplayName("the longest possible confirmation still composes a note the notes column accepts")
    void theLongestPossibleConfirmationStillFits() {
        // The claim MAX_CONFIRMATION_NOTE_LENGTH exists for: the caseworker's text is bounded by the schema and
        // the notes column is bounded by the database, so the two have to agree or an accepted note would be
        // refused half way through a write that had already recorded the outcome.
        for (ConfirmationOutcome outcome : ConfirmationOutcome.values()) {
            Referral referral = asked(
                EVERY_REASON, LocalDateTime.parse("2026-05-03T09:30:00"),
                LocalDateTime.parse("2026-05-10T11:00:00"), outcome);

            String text = ReferralNotes.confirmationRecorded(
                referral, Optional.of("c".repeat(ReferralSchemas.MAX_CONFIRMATION_NOTE_LENGTH)));

            assertThat(text.length()).as("%s", outcome).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
            assertThat(NoteForm.validateNoteText(text)).as("%s", outcome).isEmpty();
        }
    }

    @Test
    @DisplayName("a lapsed claim names the judge who was holding it and says it is back in the pool")
    void lapsedClaimNamesTheJudge() {
        // The judge is named because the release erases claimedBy: after the sweep, the note is the only
        // record on the case that this judge ever had it.
        assertThat(ReferralNotes.claimLapsed("District Judge Bell"))
            .isEqualTo("Referral claim by District Judge Bell lapsed after inactivity."
                + " The referral has been returned to the shared pool and can be picked up again.");
    }

    @Test
    @DisplayName("a lapsed claim with no name on it still reads as a lapse")
    void lapsedClaimWithNoNameStillReadsAsALapse() {
        // Not reachable through claim(), which always stamps a name -- but a row backfilled or repaired by
        // hand could hold a claim with none, and the sweep must still be able to say what happened.
        for (String noName : Arrays.asList(null, "", "   ")) {
            assertThat(ReferralNotes.claimLapsed(noName))
                .as("a claim held under %s", noName == null ? "null" : "blank")
                .startsWith("Referral claim by an unnamed Master lapsed after inactivity.");
        }
    }

    @Test
    @DisplayName("a lapse note is acceptable to the notes column however long the name on the claim is")
    void lapseNoteIsAcceptableHoweverLongTheNameIs() {
        // claimedBy is unbounded text and comes from a token, so the note it composes has to be bounded here
        // -- a note the validator refused would roll back the release rather than just the note, leaving the
        // claim stuck on a judge who has gone home.
        String text = ReferralNotes.claimLapsed("J".repeat(5000));

        assertThat(NoteForm.validateNoteText(text)).isEmpty();
        assertThat(text.length()).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
    }
}
