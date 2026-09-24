package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteForm;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two case notes an email writes: one when this service accepts it, and one when it terminally fails.
 *
 * <p>Container-free, so CI runs it -- and the length tests are the reason the prose is composed in a class of
 * its own rather than inline in the service and the drain. The failure note is written inside the drain's batch
 * transaction, so a note the notes validator refused would roll back rows Notify had already accepted.
 */
class EmailNotesTest {

    @Test
    @DisplayName("a sent note names the email and points at the Emails tab for its delivery status")
    void sentNoteNamesTheEmailAndPointsAtTheTab() {
        assertThat(EmailNotes.sent(EmailTemplate.APPLICATION_REJECTED))
            .isEqualTo("Application rejected email sent. See the Emails tab for its delivery status.");
    }

    @Test
    @DisplayName("a failure note names the email and points at the Emails tab for the reason")
    void failureNoteNamesTheEmailAndPointsAtTheTab() {
        assertThat(EmailNotes.couldNotBeSent(EmailTemplate.REQUEST_CONFIRMATION_TO_PROCEED))
            .isEqualTo("Request confirmation to proceed email could not be sent."
                + " See the Emails tab for the reason.");
    }

    @Test
    @DisplayName("both notes fit the notes column for every template there is")
    void bothNotesFitTheNotesColumnForEveryTemplate() {
        // The claim this class exists for, and it iterates the enum rather than pinning one worst case: there
        // are two templates today and five planned, so a template added later with a verbose display name has
        // to fail here rather than have its note refused half way through a drain -- which would roll back
        // rows Notify had already accepted, and the next drain would send them again.
        for (EmailTemplate template : EmailTemplate.values()) {
            for (String text : List.of(EmailNotes.sent(template), EmailNotes.couldNotBeSent(template))) {
                assertThat(text.length()).as(text).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
                assertThat(NoteForm.validateNoteText(text)).as(text).isEmpty();
            }
        }
    }

    @Test
    @DisplayName("every template's notes are the same two sentences, with only the name differing")
    void everyTemplatesNotesAreTheSameTwoSentences() {
        // Nothing branches on which email it is: no template mentions its documents (Notify inserts a
        // time-limited link rather than attaching a file, so a permanent note saying "attached" would be
        // wrong), and none says anything the tab beside it is the record of.
        for (EmailTemplate template : EmailTemplate.values()) {
            String name = template.displayName();

            assertThat(EmailNotes.sent(template))
                .isEqualTo(name + " email sent. See the Emails tab for its delivery status.");
            assertThat(EmailNotes.couldNotBeSent(template))
                .isEqualTo(name + " email could not be sent. See the Emails tab for the reason.");
        }
    }

    @Test
    @DisplayName("the stored name composes the same note the template does, for every template")
    void storedNameComposesTheSameNoteTheTemplateDoes() {
        // The drain reads a member name off the row and the enqueue holds the member itself, so a failure note
        // and the sent note beside it have to name the email identically or one send would read as two.
        for (EmailTemplate template : EmailTemplate.values()) {
            assertThat(EmailNotes.couldNotBeSent(template.name()))
                .as("%s", template)
                .isEqualTo(EmailNotes.couldNotBeSent(template));
        }
    }

    @Test
    @DisplayName("an unresolvable stored name still composes a note, using the raw name")
    void unresolvableStoredNameStillComposesANote() {
        // Reachable rather than theoretical: a member renamed after rows were written is what byName answers
        // empty for, and the sender reports such a row as a permanent failure -- so the unresolvable case and
        // the failure-note case coincide. The Email tab already degrades by showing the raw stored name, and
        // this does the same, so the note and the row beside it read consistently.
        String renamed = "FORMS_FOR_COMPLETION_APPLICANT";
        assertThat(EmailTemplate.byName(renamed)).isEmpty();

        assertThat(EmailNotes.couldNotBeSent(renamed))
            .isEqualTo(renamed + " email could not be sent. See the Emails tab for the reason.");
    }

    @Test
    @DisplayName("a pathological stored name is cut to 200 characters, and the prose still follows it")
    void pathologicalStoredNameStillComposesAnAcceptableNote() {
        // template is unbounded `text` in the database and the notes column is not. Nothing writes a name like
        // this today -- the value comes from the enum -- but "not reachable" is the wrong standard for a note
        // written inside the drain's batch transaction, where a refusal costs rows Notify has accepted.
        //
        // The whole composed note is asserted rather than only its length, so the truncation bound itself is
        // pinned: a note that merely fitted the column would also pass if the 200 became 1,900 or if the cut
        // took the sentence with it, and the margin this note relies on would shrink with nothing failing.
        String note = EmailNotes.couldNotBeSent("T".repeat(5000));

        assertThat(note)
            .isEqualTo("T".repeat(200) + " email could not be sent. See the Emails tab for the reason.");
        assertThat(note.length()).isLessThanOrEqualTo(NoteForm.MAX_NOTE_LENGTH);
        assertThat(NoteForm.validateNoteText(note)).isEmpty();
    }

    @Test
    @DisplayName("a missing stored name still reads as a note rather than as an empty one")
    void missingStoredNameStillReadsAsANote() {
        // Unreachable -- the column is NOT NULL -- but a composer that interpolated the stored value would put
        // the word "null", or nothing at all, onto a timeline that is never revised. The null case is also what
        // stops `displayName` throwing where a throw would cost rows Notify has accepted.
        for (String noName : Arrays.asList(null, "", "   ")) {
            assertThat(EmailNotes.couldNotBeSent(noName))
                .as("a row storing %s", noName == null ? "null" : "blank")
                .isEqualTo("Unrecognised email could not be sent. See the Emails tab for the reason.");
        }
    }
}
