package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ports every test block in {@code reference/ts/deed-poll/form-presentation.test.ts} -- the privacy
 * matrix, editability, the PRIVATE banner and the three conditional tabs.
 */
class FormPresentationTest {

    private static final PresentationContext ADMIN_NEW = new PresentationContext(true, true);
    private static final PresentationContext ADMIN_EXISTING = new PresentationContext(true, false);
    private static final PresentationContext USER_NEW = new PresentationContext(false, true);
    private static final PresentationContext USER_EXISTING = new PresentationContext(false, false);

    @Nested
    @DisplayName("defaultPrivacy -- create matrix")
    class DefaultPrivacy {

        @Test
        @DisplayName("admin + Minor Transgender (new) gives Private")
        void adminPlusMinorTransgenderGivesPrivate() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.MinorTransgender, null, ADMIN_NEW))
                .isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("admin + non-MinorTG (new) leaves the current value unchanged")
        void adminPlusNonMinorTransgenderLeavesCurrentValue() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.Adult, Privacy.Private, ADMIN_NEW))
                .isEqualTo(Privacy.Private);
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.Adult, null, ADMIN_NEW))
                .isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("non-admin + Adult/AdultTG/Minor (new) gives Normal")
        void nonAdminPlusAdultOrMinorGivesNormal() {
            for (DeedPollType type : List.of(DeedPollType.Adult, DeedPollType.AdultTransgender, DeedPollType.Minor)) {
                assertThat(FormPresentation.defaultPrivacy(type, null, USER_NEW)).isEqualTo(Privacy.Normal);
            }
        }

        @Test
        @DisplayName("non-admin + Minor Transgender (new) gives Private")
        void nonAdminPlusMinorTransgenderGivesPrivate() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.MinorTransgender, null, USER_NEW))
                .isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("non-admin + Deed of Relinquishment (new) gives Normal")
        void nonAdminPlusRelinquishmentGivesNormal() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.DeedOfRelinquishment, null, USER_NEW))
                .isEqualTo(Privacy.Normal);
        }

        @Test
        @DisplayName("Unknown / null type (new) is left unchanged")
        void unknownOrNullTypeIsLeftUnchanged() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.Unknown, Privacy.Private, USER_NEW))
                .isEqualTo(Privacy.Private);
            assertThat(FormPresentation.defaultPrivacy(null, Privacy.Private, ADMIN_NEW))
                .isEqualTo(Privacy.Private);
        }

        @Test
        @DisplayName("existing records are never auto-set (value preserved)")
        void existingRecordsAreNeverAutoSet() {
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.MinorTransgender, Privacy.Normal, USER_EXISTING))
                .isEqualTo(Privacy.Normal);
            assertThat(FormPresentation.defaultPrivacy(DeedPollType.MinorTransgender, Privacy.Normal, ADMIN_EXISTING))
                .isEqualTo(Privacy.Normal);
        }
    }

    @Nested
    @DisplayName("privacyEditable")
    class PrivacyEditable {

        @Test
        @DisplayName("admin can always edit")
        void adminCanAlwaysEdit() {
            assertThat(FormPresentation.privacyEditable(DeedPollType.Adult, ADMIN_NEW)).isTrue();
            assertThat(FormPresentation.privacyEditable(DeedPollType.Adult, ADMIN_EXISTING)).isTrue();
        }

        @Test
        @DisplayName("non-admin + new + Relinquishment can edit")
        void nonAdminNewRelinquishmentCanEdit() {
            assertThat(FormPresentation.privacyEditable(DeedPollType.DeedOfRelinquishment, USER_NEW)).isTrue();
        }

        @Test
        @DisplayName("non-admin + new + other type is locked")
        void nonAdminNewOtherTypeIsLocked() {
            assertThat(FormPresentation.privacyEditable(DeedPollType.Adult, USER_NEW)).isFalse();
            assertThat(FormPresentation.privacyEditable(DeedPollType.MinorTransgender, USER_NEW)).isFalse();
        }

        @Test
        @DisplayName("non-admin + existing is locked")
        void nonAdminExistingIsLocked() {
            assertThat(FormPresentation.privacyEditable(DeedPollType.DeedOfRelinquishment, USER_EXISTING)).isFalse();
        }
    }

    @Nested
    @DisplayName("tabVisibility")
    class TabVisibility {

        @Test
        @DisplayName("hides Referral tab when refer-to-master is No")
        void hidesReferralTabWhenReferToMasterIsNo() {
            assertThat(FormPresentation.tabVisibility(PresentationInput.builder()
                .referToMasterChoice(ReferToMaster.No)
                .build()).referralToMaster()).isFalse();
        }

        @Test
        @DisplayName("shows Referral tab for Yes or unset")
        void showsReferralTabForYesOrUnset() {
            assertThat(FormPresentation.tabVisibility(PresentationInput.builder()
                .referToMasterChoice(ReferToMaster.Yes)
                .build()).referralToMaster()).isTrue();
            assertThat(FormPresentation.tabVisibility(PresentationInput.builder().build()).referralToMaster())
                .isTrue();
        }

        @Test
        @DisplayName("Enroled state shows Enrolment, hides Return")
        void enroledStateShowsEnrolmentHidesReturn() {
            FormPresentation.TabVisibility tabs = FormPresentation.tabVisibility(
                PresentationInput.builder().state(DeedPollState.Enroled).build());
            assertThat(tabs.enrolment()).isTrue();
            assertThat(tabs.returnTab()).isFalse();
        }

        @Test
        @DisplayName("Returned state hides Enrolment, shows Return")
        void returnedStateHidesEnrolmentShowsReturn() {
            FormPresentation.TabVisibility tabs = FormPresentation.tabVisibility(
                PresentationInput.builder().state(DeedPollState.Returned).build());
            assertThat(tabs.enrolment()).isFalse();
            assertThat(tabs.returnTab()).isTrue();
        }

        @Test
        @DisplayName("any other state shows both Enrolment and Return")
        void anyOtherStateShowsBoth() {
            List<DeedPollState> others = List.of(
                DeedPollState.Received,
                DeedPollState.AwaitingSignature,
                DeedPollState.ReferredToMaster);
            for (DeedPollState state : others) {
                FormPresentation.TabVisibility tabs = FormPresentation.tabVisibility(
                    PresentationInput.builder().state(state).build());
                assertThat(tabs.enrolment()).isTrue();
                assertThat(tabs.returnTab()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("build -- full model")
    class Build {

        @Test
        @DisplayName("shows the PRIVATE banner when effective privacy is Private")
        void showsThePrivateBannerWhenPrivate() {
            FormPresentation model = FormPresentation.build(
                PresentationInput.builder().type(DeedPollType.MinorTransgender).build(),
                USER_NEW);
            assertThat(model.privacy().value()).isEqualTo(Privacy.Private);
            assertThat(model.privacy().showBanner()).isTrue();
            assertThat(model.privacy().bannerText()).isEqualTo("This record is PRIVATE!");
            assertThat(model.privacy().editable()).isFalse();
        }

        @Test
        @DisplayName("no banner for a Normal record and no banner text")
        void noBannerForANormalRecord() {
            FormPresentation model = FormPresentation.build(
                PresentationInput.builder().type(DeedPollType.Adult).privacy(Privacy.Normal).build(),
                USER_NEW);
            assertThat(model.privacy().showBanner()).isFalse();
            // The reference asserts `undefined`; Java has only null for an absent display value.
            assertThat(model.privacy().bannerText()).isNull();
        }
    }
}
