package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.notify.GazetteNotify;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationFailure;

import java.util.List;
import java.util.UUID;

/** Only the admitting caller can prepare/send; every repeat is a status lookup with no delivery work. */
@Slf4j
@Service
public class GazetteDelivery {
    private final GazetteRequestLedger ledger;
    private final GazetteSpreadsheet spreadsheets;
    private final GazetteNotify notify;

    public GazetteDelivery(GazetteRequestLedger ledger, GazetteSpreadsheet spreadsheets, GazetteNotify notify) {
        this.ledger = ledger;
        this.spreadsheets = spreadsheets;
        this.notify = notify;
    }

    public UUID submit(CurrentUser caller, UUID key, List<String> ids, String fingerprint, int revision) {
        GazetteRequestLedger.Reservation reservation = ledger.admit(caller, key, ids, fingerprint, revision);
        if (reservation.claim() != null) {
            deliver(reservation);
        }
        return reservation.request().id();
    }

    private void deliver(GazetteRequestLedger.Reservation reservation) {
        GazetteRequest row = reservation.request();
        GazetteRequestLedger.Claim claim = reservation.claim();
        GazetteNotify.Prepared prepared;
        try {
            prepared = notify.prepare(row, spreadsheets.generateInputs(reservation.inputs()));
        } catch (RuntimeException failed) {
            settle(row.id(), () -> ledger.preparationFailed(claim));
            return;
        }
        try {
            if (!ledger.startSend(claim, prepared.evidence())) {
                settle(row.id(), () -> ledger.expire(row.id()));
                return;
            }
        } catch (RuntimeException unknownCommit) {
            // Commit acknowledgement could have been lost. Never call Notify or erase possible send authority.
            log.warn("Gazette batch {} send authority was not confirmed; recovery required", row.id());
            return;
        }
        GazetteNotify.Receipt receipt;
        try {
            receipt = notify.send(row, prepared);
        } catch (GazetteNotify.Failure failed) {
            settle(row.id(), () -> {
                if (NotificationFailure.classify(failed.status()) == NotificationFailure.TECHNICAL) {
                    ledger.uncertain(claim);
                } else {
                    ledger.providerRefused(claim, failed.status());
                }
                ledger.expire(row.id());
            });
            return;
        } catch (RuntimeException unknown) {
            settle(row.id(), () -> {
                ledger.uncertain(claim);
                ledger.expire(row.id());
            });
            return;
        }
        try {
            ledger.sent(claim, receipt.notificationId(), receipt.templateVersion());
        } catch (RuntimeException unknownCommit) {
            // Correlation evidence only. No content, recipient, request key, subject, token or exception prose.
            log.error("Gazette batch {} accepted receipt {} template version {}; settlement unconfirmed",
                row.id(), receipt.notificationId(), receipt.templateVersion());
            settle(row.id(), () -> {
                ledger.uncertain(claim);
                ledger.expire(row.id());
                // Retain the receipt in Uncertain; this never invokes Notify or upgrades Uncertain to Sent.
                ledger.sent(claim, receipt.notificationId(), receipt.templateVersion());
            });
        }
    }

    private static void settle(UUID batch, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException unavailable) {
            log.warn("Gazette batch {} settlement unconfirmed; recovery required", batch);
        }
    }
}
