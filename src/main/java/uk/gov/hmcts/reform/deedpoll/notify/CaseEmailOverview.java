package uk.gov.hmcts.reform.deedpoll.notify;

import java.util.List;

/**
 * Everything the Email tab needs from one read.
 *
 * @param availability every known email type and whether it applies
 * @param history every recorded email attempt, newest first
 * @param slotDefaults server-resolved starting values for each applicable template
 */
public record CaseEmailOverview(
    List<EmailAvailability> availability,
    List<CaseCorrespondence> history,
    List<EmailSlotDefaults> slotDefaults
) {

    public CaseEmailOverview {
        availability = List.copyOf(availability);
        history = List.copyOf(history);
        slotDefaults = List.copyOf(slotDefaults);
    }
}
