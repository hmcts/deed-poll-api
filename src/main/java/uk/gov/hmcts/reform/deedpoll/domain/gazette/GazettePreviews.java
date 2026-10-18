package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Capability;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteRequestRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteSettingsRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class GazettePreviews {
    private final DeedPollRepository cases;
    private final GazetteRequestRepository requests;
    private final GazetteSettingsRepository settings;
    private final GazetteConfiguration configuration;

    public GazettePreviews(DeedPollRepository cases, GazetteRequestRepository requests,
                           GazetteSettingsRepository settings, GazetteConfiguration configuration) {
        this.cases = cases;
        this.requests = requests;
        this.settings = settings;
        this.configuration = configuration;
    }

    /** A consistent read snapshot; admission later rechecks under write locks. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GazettePreview preview(CurrentUser caller, List<String> selected) {
        caller.require(Capability.ACCESS_GAZETTE);
        List<String> ids = GazetteSelection.ids(selected);
        GazetteSettings recipient = settings.read(false);
        if (recipient.recipientEmail() == null) {
            throw new GazetteRefusal(GazetteRefusal.Code.RECIPIENT_NOT_CONFIGURED);
        }
        List<GazetteInput> inputs = new ArrayList<>();
        for (String id : ids) {
            DeedPoll row = cases.findById(id).orElse(null);
            GazetteSelection.ready(id, row, requests.reserved(id));
            inputs.add(GazetteInput.from(row));
        }
        inputs.sort(order());
        int adults = (int) inputs.stream().filter(input -> input.kind().equals("adult")).count();
        configuration.forGroups(adults, inputs.size() - adults);
        LocalDateTime expiry = AuditClock.now().plusMinutes(30).withNano(0);
        return new GazettePreview(recipient.recipientEmail(), recipient.revision(), inputs.size(), adults,
            inputs.size() - adults,
            inputs.stream().map(i -> new GazetteMember(i.caseId(), i.referenceNumber())).toList(),
            GazetteConfirmation.issue(inputs, expiry), expiry);
    }

    static Comparator<GazetteInput> order() {
        return Comparator.comparing(GazetteInput::dateEnroled, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(GazetteInput::caseId);
    }
}
