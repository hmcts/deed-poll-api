package uk.gov.hmcts.reform.deedpoll.domain.masters;

import java.util.List;
import java.util.UUID;

/**
 * One claimed Master email: the claim's identity and the row's frozen inputs, with no managed entity.
 *
 * @param attempt the attempt this claim was granted for, which every later transition names with the owner
 */
public record MasterEmailClaim(UUID id, UUID owner, int attempt, UUID masterId, MasterEmailKind kind,
                               String periodKey, List<UUID> referralIds) {

    public MasterEmailClaim {
        referralIds = List.copyOf(referralIds);
    }
}
