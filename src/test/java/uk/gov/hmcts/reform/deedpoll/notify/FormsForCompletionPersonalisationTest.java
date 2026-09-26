package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the two forms-for-completion emails send, resolved from a case.
 *
 * <p>Container-free. Two of the claims here are ones Notify punishes silently: a key the template names and the
 * personalisation omits is a permanent refusal on the drain, and a Gazette flag sent as text would make the
 * template's conditional render for every applicant.
 */
class FormsForCompletionPersonalisationTest {

    private static final String DEED_ID = "aa6d1f80-0000-4000-8000-0000000000de";

    /**
     * What a caseworker who wrote nothing in the notes box sends: {@link EmailSlots#parse} omits a blank slot.
     */
    private static final Map<EmailSlot, String> NO_SLOTS = Map.of();

    @Test
    @DisplayName("resolves the reference, the applicant's FORMER names, the Gazette flag and the deed")
    void resolvesTheCasesValues() {
        FormsForCompletionPersonalisation resolved =
            FormsForCompletionPersonalisation.resolve(populatedCase(), DEED_ID, NO_SLOTS);

        assertThat(resolved.toMap()).containsExactlyInAnyOrderEntriesOf(Map.of(
            "reference_number", "KBDP2026-00001",
            "online_application_reference", "DP-2026-0042",
            // The OLD name, deliberately: the new name is not theirs until the deed is sealed, and this email
            // carries the instrument that changes it. "Improving" this to the new name is the mistake the source
            // document's own wording guards against.
            "old_forename", "Alex",
            "old_surname", "Okonjo",
            "publish_to_london_gazette", true,
            "deed_document", DEED_ID,
            // The optional notes box, empty rather than absent when the caseworker wrote nothing.
            "additional_notes", ""));
        // And in the declared order, which is the order the stored jsonb reads in -- worth having when the question
        // asked of an outbox row is "what was this applicant sent".
        assertThat(resolved.toMap().keySet())
            .containsExactlyElementsOf(EmailTemplate.FORMS_FOR_COMPLETION_ADULT.personalisationKeys());
    }

    @Test
    @DisplayName("sends the rendered reference an applicant has seen, never the integer serial")
    void sendsTheRenderedReference() {
        DeedPoll deedPoll = populatedCase();
        deedPoll.setReferencenumber(1);

        // The same value the deed itself prints, so the email and the document a caseworker compares agree. The
        // serial is an internal component of the rendered reference and is not what the applicant was ever shown.
        assertThat(FormsForCompletionPersonalisation.resolve(deedPoll, DEED_ID, NO_SLOTS).referenceNumber())
            .isEqualTo("KBDP2026-00001")
            .isNotEqualTo("1");
    }

    @Test
    @DisplayName("a null column becomes an empty string, because Notify refuses a send with a key missing")
    void nullColumnsBecomeEmptyStrings() {
        // Every one of these is nullable on the case, and online_application_reference is nullable in practice --
        // a paper application has none. An absent key would turn a blank column into a permanent failure on the
        // drain, where nobody is waiting to read it; an empty string renders as nothing, which is what blank means.
        Map<String, Object> resolved =
            FormsForCompletionPersonalisation.resolve(new DeedPoll(), DEED_ID, NO_SLOTS).toMap();

        assertThat(resolved)
            .containsKeys(FormsForCompletionPersonalisation.RESOLVED_KEYS.toArray(String[]::new))
            .doesNotContainValue(null);
        assertThat(resolved.get("online_application_reference")).isEqualTo("");
        assertThat(resolved.get("reference_number")).isEqualTo("");
        assertThat(resolved.get("old_forename")).isEqualTo("");
        assertThat(resolved.get("old_surname")).isEqualTo("");
    }

    @Test
    @DisplayName("the Gazette key is always present and is a boolean, because the template branches on it")
    void theGazetteKeyIsAlwaysPresentAndBoolean() {
        // Decision 10. The column is a non-null boolean defaulting to true, so the key is never empty -- and it
        // reaches Notify as a Boolean rather than as "true", because ((publish_to_london_gazette??...)) tests it.
        // A string would make the conditional render for every applicant, including the ones not being published.
        DeedPoll publishing = populatedCase();
        DeedPoll notPublishing = populatedCase();
        notPublishing.setPublishToLondonGazette(false);

        assertThat(FormsForCompletionPersonalisation.resolve(publishing, DEED_ID, NO_SLOTS).toMap())
            .containsEntry("publish_to_london_gazette", true);
        assertThat(FormsForCompletionPersonalisation.resolve(notPublishing, DEED_ID, NO_SLOTS).toMap())
            .containsEntry("publish_to_london_gazette", false);
        // And on a case where nothing has been set at all, which is the entity's own default.
        assertThat(FormsForCompletionPersonalisation.resolve(new DeedPoll(), DEED_ID, NO_SLOTS).toMap())
            .containsEntry("publish_to_london_gazette", true);
    }

    @Test
    @DisplayName("a preview carries a description of the deed rather than a document id")
    void previewCarriesADescriptionOfTheDeed() {
        // A preview writes nothing, so there is no stored deed to reference -- and there could be no link anyway,
        // because Notify creates one only when it is given the bytes. Prose rather than an empty string, so a
        // caseworker can see where the link will appear instead of finding a gap.
        assertThat(FormsForCompletionPersonalisation
                       .resolve(populatedCase(), FormsForCompletionPersonalisation.DEED_NOT_YET_GENERATED,
                                NO_SLOTS)
                       .toMap())
            .containsEntry("deed_document", FormsForCompletionPersonalisation.DEED_NOT_YET_GENERATED);
        assertThat(FormsForCompletionPersonalisation.DEED_NOT_YET_GENERATED).isNotBlank();
    }

    @Test
    @DisplayName("the caseworker's notes are sent under additional_notes, and no notes is an empty string")
    void carriesTheCaseworkersNotes() {
        // The same key ③ and ④ send, so one Notify placeholder spelling serves every template with a notes box.
        assertThat(FormsForCompletionPersonalisation
                       .resolve(populatedCase(), DEED_ID,
                                Map.of(EmailSlot.ADDITIONAL_NOTES, "Please sign in black ink."))
                       .toMap())
            .containsEntry("additional_notes", "Please sign in black ink.");
        // Present and empty rather than absent: a template naming ((additional_notes)) and a personalisation
        // omitting it is a 400 from Notify, which on the drain is a permanent failure nobody is waiting to read.
        assertThat(FormsForCompletionPersonalisation.resolve(populatedCase(), DEED_ID, NO_SLOTS).toMap())
            .containsEntry("additional_notes", "");
    }

    @Test
    @DisplayName("toMap writes every record component, so a component added without a key fails here")
    void toMapWritesEveryComponent() {
        // The property that makes this record's personalisation enumerable rather than hand-maintained: a value
        // this service resolves and never sends is worse than one it never resolves, because the template would
        // render a blank where the applicant expected their reference.
        assertThat(FormsForCompletionPersonalisation.resolve(populatedCase(), DEED_ID, NO_SLOTS).toMap())
            .hasSize(FormsForCompletionPersonalisation.class.getRecordComponents().length);
    }

    @Test
    @DisplayName("both templates declare exactly these keys, resolved and none settable by a client")
    void bothTemplatesDeclareTheseKeys() {
        for (EmailTemplate template : Arrays.asList(EmailTemplate.FORMS_FOR_COMPLETION_ADULT,
                                                    EmailTemplate.FORMS_FOR_COMPLETION_MINOR)) {
            assertThat(template.resolvedKeys())
                .isEqualTo(FormsForCompletionPersonalisation.RESOLVED_KEYS);
            // One slot, the notes box ③ and ④ already carry (notify-4c Task 2) rather than a second name for it,
            // and optional: nothing pre-populates it and the email reads complete without it.
            assertThat(template.slots()).containsExactly(EmailSlot.ADDITIONAL_NOTES);
            assertThat(EmailSlot.ADDITIONAL_NOTES.required()).isFalse();
            assertThat(template.personalisationKeys())
                .containsExactly(Stream.concat(template.resolvedKeys().stream(), Stream.of("additional_notes"))
                                     .toArray(String[]::new));
            assertThat(template.resolves("additional_notes")).isFalse();
            // The document key is resolved too, which is what stops a client nominating which document an
            // applicant is sent.
            assertThat(template.resolves("deed_document")).isTrue();
            assertThat(template.documentKeys()).containsExactly("deed_document");
        }
    }

    @Test
    @DisplayName("the four common keys are spelled as every other template spells them")
    void theCommonKeysAreSpelledAsElsewhere() {
        // Not new names, deliberately: the source documents reference the same Dynamics tokens as ③ and ⑤, so a
        // content designer moving a paragraph between templates renames nothing.
        assertThat(FormsForCompletionPersonalisation.REFERENCE_NUMBER)
            .isEqualTo(MissingDocumentsPersonalisation.REFERENCE_NUMBER);
        assertThat(FormsForCompletionPersonalisation.ONLINE_APPLICATION_REFERENCE)
            .isEqualTo(MissingDocumentsPersonalisation.ONLINE_APPLICATION_REFERENCE);
        assertThat(FormsForCompletionPersonalisation.OLD_FORENAME)
            .isEqualTo(MissingDocumentsPersonalisation.OLD_FORENAME);
        assertThat(FormsForCompletionPersonalisation.OLD_SURNAME)
            .isEqualTo(MissingDocumentsPersonalisation.OLD_SURNAME);
    }

    private static DeedPoll populatedCase() {
        DeedPoll deedPoll = new DeedPoll();
        deedPoll.setId("case-1");
        deedPoll.setDisplayReference("KBDP2026-00001");
        deedPoll.setOnlineApplicationReference("DP-2026-0042");
        deedPoll.setOldForename("Alex");
        deedPoll.setOldSurname("Okonjo");
        deedPoll.setNewForename("Robin");
        deedPoll.setNewSurname("Okonkwo-Hart");
        return deedPoll;
    }
}
