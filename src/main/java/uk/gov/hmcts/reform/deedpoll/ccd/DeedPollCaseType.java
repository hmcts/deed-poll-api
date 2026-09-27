package uk.gov.hmcts.reform.deedpoll.ccd;

import org.springframework.stereotype.Component;
import uk.gov.hmcts.ccd.sdk.api.CCDConfig;
import uk.gov.hmcts.ccd.sdk.api.ConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.DecentralisedConfigBuilder;
import uk.gov.hmcts.ccd.sdk.api.EventPayload;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;
import uk.gov.hmcts.ccd.sdk.api.callback.SubmitResponse;

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

    public DeedPollCaseType(CcdProperties properties) {
        this.properties = properties;
    }

    @Override
    public void configureDecentralised(
        DecentralisedConfigBuilder<DeedPollCaseData, DeedPollCaseState, DeedPollAccessProfile> builder) {

        builder.caseType(properties.caseTypeId(), "Deed poll", "Deed poll change of name");
        builder.jurisdiction(properties.jurisdictionId(), "Deed poll", "Deed poll enrolment");

        mapSystemRole(builder, properties.systemRole());

        // The SDK adds a caseHistory viewer field and a History tab to every case type, and grants every role that
        // holds an event CRU on that field. A system actor reads no history, and "create and read, nothing else"
        // would otherwise carry an update grant nobody asked for.
        builder.omitHistoryForRoles(DEED_POLL_SYSTEM);

        builder.decentralisedEvent(LINK_DEED_POLL, this::linkDeedPoll)
            .initialState(Registered)
            .name("Link deed poll")
            .description("Create the CCD case for an existing deed poll record")
            .grant(Permission.CR, DEED_POLL_SYSTEM)
            .fields()
            .mandatory(DeedPollCaseData::getDeedPollId)
            .mandatory(DeedPollCaseData::getDeedPollReference);
    }

    /**
     * Refuses every submission until plan 02 Task 6 binds the reference. Nothing can reach it before then:
     * {@code /ccd-persistence/**} is neither mapped nor permitted.
     */
    private SubmitResponse<DeedPollCaseState> linkDeedPoll(EventPayload<DeedPollCaseData, DeedPollCaseState> payload) {
        throw new UnsupportedOperationException(LINK_DEED_POLL + " has no submit handler yet (plan 02, Task 6)");
    }

    /**
     * Maps the configured IDAM role onto {@link DeedPollAccessProfile#DEED_POLL_SYSTEM} with a
     * {@code RoleToAccessProfiles} row.
     *
     * <p>The row is keyed on a role outside the access-profile enum, which the SDK's model supports -- the row holds a
     * {@link HasRole} and reads only its name -- but its builder method is typed on the enum. Hence the raw call:
     * it is erased to {@link HasRole} at runtime, and the enum is the one place a configured value cannot go.
     * {@code legacyIdamRole} writes it as {@code idam:<role>}, the form data-store gives an IDAM role.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mapSystemRole(ConfigBuilder<?, ?, ?> builder, String idamRole) {
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
            .accessProfiles(DEED_POLL_SYSTEM.getRole());
    }
}
