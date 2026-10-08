package uk.gov.hmcts.reform.deedpoll.ccd;

import com.google.common.collect.ImmutableSetMultimap;
import com.google.common.collect.SetMultimap;
import uk.gov.hmcts.ccd.sdk.api.HasAccessControl;
import uk.gov.hmcts.ccd.sdk.api.HasRole;
import uk.gov.hmcts.ccd.sdk.api.Permission;

/**
 * Field-only grants: system deletion (plan 03a, task 5) and staff document reads.
 *
 * <p>The SDK gives an event's fields the event's grant, and gives the event's state the same grant, so granting the
 * event D would grant a delete on the case's state too, which nothing needs. Data-store wants D only on the
 * collection, to take an item off it (CompoundAccessControlService.itemDeletedAndHasDeleteAccess), so D is added to the
 * field alone, and the generator unions it with the event's CRU.
 */
public class SupportingDocumentsAccess implements HasAccessControl {

    @Override
    public SetMultimap<HasRole, Permission> getGrants() {
        return ImmutableSetMultimap.of(
            DeedPollAccessProfile.DEED_POLL_SYSTEM, Permission.D,
            DeedPollAccessProfile.DEED_POLL_READER, Permission.R);
    }
}
