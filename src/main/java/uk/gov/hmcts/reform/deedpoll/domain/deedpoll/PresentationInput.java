package uk.gov.hmcts.reform.deedpoll.domain.deedpoll;

import lombok.Builder;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollType;
import uk.gov.hmcts.reform.deedpoll.entity.enums.Privacy;
import uk.gov.hmcts.reform.deedpoll.entity.enums.ReferToMaster;

/**
 * The four fields the presentation model reads off a record.
 *
 * <p>Ported from the {@code PresentationInput} interface in
 * {@code reference/ts/deed-poll/form-presentation.ts}. All four are nullable: the model is built for
 * an unsaved record as readily as for a stored one, and "no type chosen yet" is a state the source
 * form has a branch for.
 */
@Builder
public record PresentationInput(
    DeedPollType type,
    Privacy privacy,
    ReferToMaster referToMasterChoice,
    DeedPollState state) {

    /** The presentation-relevant fields of a persisted case. */
    public static PresentationInput from(DeedPoll record) {
        return builder()
            .type(record.getType())
            .privacy(record.getPrivacy())
            .referToMasterChoice(record.getReferToMasterChoice())
            .state(record.getState())
            .build();
    }
}
