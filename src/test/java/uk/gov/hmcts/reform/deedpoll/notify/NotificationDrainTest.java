package uk.gov.hmcts.reform.deedpoll.notify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.reform.deedpoll.entity.Notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Container-free checks of orchestration and authority ordering; database tests prove the commits. */
class NotificationDrainTest {
    private final NotificationDeliveryTransactions transactions = mock(NotificationDeliveryTransactions.class);
    private final NotificationSender sender = mock(NotificationSender.class);
    private final PreparedNotification.Delivery delivery = mock(PreparedNotification.Delivery.class);
    private final NotificationOutbox outbox = new NotificationOutbox(transactions, Optional.of(sender));
    private final NotificationClaim claim = new NotificationClaim(UUID.randomUUID(), UUID.randomUUID(), 1,
        "case", EmailTemplate.APPLICATION_REJECTED.name(), "synthetic@example.com", Map.of("reason", "original"));

    @BeforeEach
    void setup() {
        when(transactions.candidates(any(), anyInt())).thenReturn(List.of(claim.id()));
        when(transactions.claim(eq(claim.id()), any())).thenReturn(Optional.of(claim));
        when(sender.prepare(any())).thenReturn(new PreparedNotification.Ready(delivery));
        when(transactions.startSend(claim)).thenReturn(true);
        when(delivery.send()).thenReturn(SendOutcome.sent(null));
        when(transactions.complete(eq(claim), any())).thenReturn(true);
    }

    @Test
    void prepareThenCommitAuthorityThenSendThenCommitOutcome() {
        assertThat(outbox.drain()).isOne();
        var order = inOrder(transactions, sender, delivery);
        order.verify(transactions).candidates(any(LocalDateTime.class), eq(NotificationOutbox.DEFAULT_LIMIT));
        order.verify(transactions).claim(eq(claim.id()), any());
        order.verify(sender).prepare(any(Notification.class));
        order.verify(transactions).startSend(claim);
        order.verify(delivery).send();
        order.verify(transactions).complete(claim, SendOutcome.sent(null));
    }

    @Test
    void lostClaimNeverPreparesOrSends() {
        when(transactions.claim(eq(claim.id()), any())).thenReturn(Optional.empty());
        assertThat(outbox.drain()).isZero();
        verifyNoInteractions(sender, delivery);
    }

    @Test
    void lostSendAuthorityNeverCallsDeliveryOrSettlement() {
        when(transactions.startSend(claim)).thenReturn(false);
        assertThat(outbox.drain()).isZero();
        verifyNoInteractions(delivery);
        verify(transactions, never()).complete(any(), any());
    }

    @Test
    void preparationRefusalDoesNotMarkSendStarted() {
        SendOutcome refusal = SendOutcome.failed(NotificationFailure.TEMPORARY, "unreadable");
        when(sender.prepare(any())).thenReturn(new PreparedNotification.Refused(refusal));
        outbox.drain();
        verify(transactions, never()).startSend(any());
        verify(transactions).complete(claim, refusal);
        verifyNoInteractions(delivery);
    }

    @Test
    void throwingSenderIsTerminalUncertain() {
        when(delivery.send()).thenThrow(new IllegalStateException("private provider response"));
        outbox.drain();
        verify(transactions).complete(claim, SendOutcome.failed(NotificationFailure.TECHNICAL,
            "Delivery outcome is uncertain; investigate before any resend"));
    }

    @Test
    void commitFailureStopsBeforeClaimingTheNextRow() {
        UUID next = UUID.randomUUID();
        when(transactions.candidates(any(), anyInt())).thenReturn(List.of(claim.id(), next));
        when(transactions.complete(any(), any())).thenThrow(new IllegalStateException("commit lost"));
        assertThatThrownBy(outbox::drain).hasMessage("commit lost");
        verify(transactions, never()).claim(eq(next), any());
    }

    @Test
    void unwiredOrEmptyDrainsDoNothing() {
        assertThat(new NotificationOutbox(transactions, Optional.empty()).drain()).isZero();
        assertThat(outbox.drain(0)).isZero();
        assertThat(outbox.drain(-1)).isZero();
        verifyNoInteractions(transactions, sender, delivery);
    }

    @Test
    void snapshotIsIndependentOfEntityAndDoesNotPrintCorrespondence() {
        Notification row = claim.notification();
        row.setClaimOwner(claim.owner());
        row.setPersonalisation(new java.util.LinkedHashMap<>(claim.personalisation()));
        NotificationClaim frozen = NotificationClaim.from(row);
        row.getPersonalisation().put("reason", "edited");
        row.setRecipient("changed@example.com");
        assertThat(frozen.personalisation()).containsEntry("reason", "original");
        assertThat(frozen.recipient()).isEqualTo("synthetic@example.com");
        assertThatThrownBy(() -> frozen.personalisation().put("reason", "edited"))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(frozen.toString()).isEqualTo("NotificationClaim[redacted]");
    }
}
