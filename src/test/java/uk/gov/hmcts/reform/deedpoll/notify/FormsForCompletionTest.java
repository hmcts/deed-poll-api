package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedRouting;
import uk.gov.hmcts.reform.deedpoll.domain.deeds.DeedTemplate;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;

import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which forms email a case type is offered, and which deed that email carries.
 *
 * <p><strong>Container-free, because this is the rule that must not regress unnoticed.</strong> Decision 3's
 * failure is an {@code AdultTransgender} applicant swearing a minor's deed: they complete it, take it to two
 * witnesses and a commissioner of oaths, and the court cannot seal it. CI has no Docker, so the rule is stated
 * here rather than only where a case and a template meet a database.
 *
 * <p><strong>The routing is asserted as derived, not merely as correct.</strong> Two of these tests hold
 * {@link FormsForCompletion} against {@code DeedDocumentTypes.caseTypes} through {@link DeedRouting} rather than
 * against a list of their own -- the rule is written once, and a test that wrote it a second time would be the copy
 * decision 3 forbids.
 */
class FormsForCompletionTest {

    @Test
    @DisplayName("an adult application is offered the adult forms email and never the minor one")
    void anAdultApplicationTakesTheAdultEmail() {
        assertThat(FormsForCompletion.emailFor(DeedPollType.Adult))
            .contains(EmailTemplate.FORMS_FOR_COMPLETION_ADULT);
        // The member the plan is emphatic about: "type == Minor" reads correctly and is wrong, and the mirror of
        // that mistake would send a transgender adult applicant a child's deed.
        assertThat(FormsForCompletion.emailFor(DeedPollType.AdultTransgender))
            .contains(EmailTemplate.FORMS_FOR_COMPLETION_ADULT)
            .isNotEqualTo(Optional.of(EmailTemplate.FORMS_FOR_COMPLETION_MINOR));
    }

    @Test
    @DisplayName("a child's application is offered the minor forms email, transgender or not")
    void childsApplicationTakesTheMinorEmail() {
        assertThat(FormsForCompletion.emailFor(DeedPollType.Minor))
            .contains(EmailTemplate.FORMS_FOR_COMPLETION_MINOR);
        assertThat(FormsForCompletion.emailFor(DeedPollType.MinorTransgender))
            .contains(EmailTemplate.FORMS_FOR_COMPLETION_MINOR)
            .isNotEqualTo(Optional.of(EmailTemplate.FORMS_FOR_COMPLETION_ADULT));
    }

    @Test
    @DisplayName("a relinquishment, an unknown type and an unset type are offered neither")
    void theOtherThreeAreOfferedNeither() {
        // Three absences and each is a decision. A relinquishment is a different instrument whose forms nobody has
        // identified (notify-4a flag 5); Unknown means the type has not been established; and a null type is the
        // same position as Unknown rather than a fourth case. Offering the adult email to any of them would be the
        // wording default ResolvedValues.isMinor makes, which is deliberately not the forms default: a sentence is
        // merely impersonal if wrong, and the wrong deed is sworn.
        assertThat(FormsForCompletion.emailFor(DeedPollType.DeedOfRelinquishment)).isEmpty();
        assertThat(FormsForCompletion.emailFor(DeedPollType.Unknown)).isEmpty();
        assertThat(FormsForCompletion.emailFor(null)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(DeedPollType.class)
    @DisplayName("the email a type is offered is the one that carries the deed that type takes")
    void theEmailCarriesTheDeedTheTypeTakes(DeedPollType type) {
        // Derived rather than restated: the expected answer comes from DeedRouting, which comes from
        // DeedDocumentTypes.caseTypes -- the one statement of which case type takes which deed. So this asserts the
        // two switches agree with the lists rather than asserting the lists again.
        Optional<DeedTemplate> deed = DeedRouting.deedFor(type);
        Optional<EmailTemplate> email = FormsForCompletion.emailFor(type);

        assertThat(email.isPresent()).as("%s takes a deed if and only if it takes a forms email", type)
            .isEqualTo(deed.isPresent());
        assertThat(email.flatMap(FormsForCompletion::deedOf))
            .as("the %s email carries the deed %s takes", email.map(EmailTemplate::key).orElse("no"), type)
            .isEqualTo(deed);
    }

    @ParameterizedTest
    @EnumSource(EmailTemplate.class)
    @DisplayName("only the two forms emails carry a deed, and each carries exactly the one it declares documents for")
    void onlyTheFormsEmailsCarryADeed(EmailTemplate template) {
        // The two halves of "this template carries a document" have to agree: documentKeys() is what freezes an id
        // into the row and what the drain resolves, and deedOf() is what the send generates. A template with one
        // and not the other either sends an email with no deed or generates a deed nothing links to.
        assertThat(FormsForCompletion.deedOf(template).isPresent())
            .as("%s declares document keys if and only if it carries a deed", template)
            .isEqualTo(!template.documentKeys().isEmpty());
    }

    @Test
    @DisplayName("no two templates carry the same deed, so a deed identifies its email")
    void noTwoTemplatesCarryTheSameDeed() {
        // The property that lets emailFor derive one answer. Two emails carrying LOC020 would make "which email is
        // this case offered" have two answers, and the one that reached the applicant would be whichever the switch
        // happened to name first.
        assertThat(Arrays.stream(EmailTemplate.values())
                       .map(FormsForCompletion::deedOf)
                       .flatMap(Optional::stream)
                       .toList())
            .doesNotHaveDuplicates()
            .containsExactlyInAnyOrder(DeedTemplate.values());
    }
}
