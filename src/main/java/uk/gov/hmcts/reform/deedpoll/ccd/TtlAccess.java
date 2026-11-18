package uk.gov.hmcts.reform.deedpoll.ccd;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

/**
 * The {@code TTL} field's grant: CRU to the system profile, which fires the disposal events, and nothing to staff
 * (plan 20261118-dm-application-deletion, Task 5). There is no {@code TTL_profile} mapping, because nothing here
 * suspends or overrides a disposal.
 */
public class TtlAccess implements HasAccessControl {

    @Override
    public SetMultimap<HasRole, Permission> getGrants() {
        return ImmutableSetMultimap.of(
            DeedPollAccessProfile.DEED_POLL_SYSTEM, Permission.C,
            DeedPollAccessProfile.DEED_POLL_SYSTEM, Permission.R,
            DeedPollAccessProfile.DEED_POLL_SYSTEM, Permission.U);
    }
}
