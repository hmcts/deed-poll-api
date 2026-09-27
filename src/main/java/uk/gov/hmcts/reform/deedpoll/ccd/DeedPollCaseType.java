package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollAccessProfile.DEED_POLL_SYSTEM;
import static uk.gov.hmcts.reform.deedpoll.ccd.DeedPollCaseState.Registered;

/**
 * The minimal deed poll case type: one state, one event that links an existing record, two fields, and one access
 * profile granted create and read.
 *
 * <p>The ids come from {@link CcdProperties} and are never written here (plan 02, decision 5). The display names are
 * text, not identifiers, and carry no onboarding weight. Everything the definition contains is pinned by
 * {@code DeedPollDefinitionTest}, so growth is deliberate: a new field, event or grant fails that test until the
 * pinned copy is updated alongside it.
 */
@Component
public class DeedPollCaseType implements CCDConfig<DeedPollCaseData, DeedPollCaseState, DeedPollAccessProfile> {

    /** Links an existing relational record to a new CCD case; it does not originate one. */
    public static final String LINK_DEED_POLL = "linkDeedPoll";

    private final CcdProperties properties;
    private final LinkDeedPollHandler linkDeedPoll;

    public DeedPollCaseType(CcdProperties properties, LinkDeedPollHandler linkDeedPoll) {
        this.properties = properties;
        this.linkDeedPoll = linkDeedPoll;
    }

    @Override
    public void configureDecentralised(
        DecentralisedConfigBuilder<DeedPollCaseData, DeedPollCaseState, DeedPollAccessProfile> builder) {

        builder.caseType(properties.caseTypeId(), "Deed poll", "Deed poll change of name");
        builder.jurisdiction(properties.jurisdictionId(), "Deed poll", "Deed poll enrolment");

        DeedPollRoleMapping.from(properties).byIdamRole().forEach((idamRole, profile) ->
            mapIdamRole(builder, idamRole, profile));

        // The SDK adds a caseHistory viewer field and a History tab to every case type, and grants every role that
        // holds an event CRU on that field. A system actor reads no history, and "create and read, nothing else"
        // would otherwise carry an update grant nobody asked for.
        builder.omitHistoryForRoles(DEED_POLL_SYSTEM);

        // Definition store refuses an import whose search inputs, search results or work-basket results are empty
        // (work-basket inputs alone may be). Nobody searches this case type, so each holds only the case reference,
        // a metadata column that needs no case field and no grant. Found by the first cftlib import (plan 02, Task 8).
        builder.searchInputFields().caseReferenceField();
        builder.searchResultFields().caseReferenceField();
        builder.workBasketResultFields().caseReferenceField();

        // The SDK derives state grants from events, but gives a creating event's destination state none -- it grants
        // a state change only where there is a state to leave -- and without one data-store answers every read of a
        // Registered case 404. Stated here instead, and still only create and read. Also found by cftlib.
        builder.grant(Registered, Permission.CR, DEED_POLL_SYSTEM);

        builder.decentralisedEvent(LINK_DEED_POLL, linkDeedPoll::submit)
            .initialState(Registered)
            .name("Link deed poll")
            .description("Create the CCD case for an existing deed poll record")
            .grant(Permission.CR, DEED_POLL_SYSTEM)
            .fields()
            .mandatory(DeedPollCaseData::getDeedPollId)
            .mandatory(DeedPollCaseData::getDeedPollReference);
    }

    /**
     * Maps one IDAM role onto an access profile with a {@code RoleToAccessProfiles} row. The entries come from
     * {@link DeedPollRoleMapping}, which the {@code /ccd-persistence/**} chain applies to its caller as well.
     *
     * <p>The row is keyed on a role outside the access-profile enum, which the SDK's model supports -- the row holds a
     * {@link HasRole} and reads only its name -- but its builder method is typed on the enum. Hence the raw call:
     * it is erased to {@link HasRole} at runtime, and the enum is the one place a configured value cannot go.
     * {@code legacyIdamRole} writes it as {@code idam:<role>}, the form data-store gives an IDAM role.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mapIdamRole(ConfigBuilder<?, ?, ?> builder, String idamRole, DeedPollAccessProfile profile) {
        HasRole role = new HasRole() {
            @Override
            public String getRole() {
                return idamRole;
            }

            @Override
            public String getCaseTypePermissions() {
                return "";
            }
        };
        ((ConfigBuilder) builder).caseRoleToAccessProfile(role)
            .legacyIdamRole()
            .accessProfiles(profile.getRole());
    }
}
