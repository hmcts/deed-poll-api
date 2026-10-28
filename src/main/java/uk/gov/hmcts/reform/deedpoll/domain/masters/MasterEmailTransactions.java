package uk.gov.hmcts.reform.deedpoll.domain.masters;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.hmcts.reform.deedpoll.domain.referrals.ReferralWindow;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.Referral;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationBackoff;
import uk.gov.hmcts.reform.deedpoll.notify.NotificationFailure;
import uk.gov.hmcts.reform.deedpoll.notify.SendOutcome;
import uk.gov.hmcts.reform.deedpoll.repository.MasterEmailRepository;
import uk.gov.hmcts.reform.deedpoll.repository.MasterRepository;
import uk.gov.hmcts.reform.deedpoll.repository.ReferralRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The Master email ledger's short database steps (plan D3), each its own transaction. Called through the Spring
 * proxy by {@link MasterEmailDelivery}, never around the Notify call.
 *
 * <p>The case outbox's rules, copied: a claim has an owner, a lease and a counted attempt; {@code Sending} is
 * committed before the one call; only a known temporary refusal returns a row to {@code Pending}, backed off by
 * {@link NotificationBackoff}, for at most its eight attempts; and a row whose send may have started is
 * {@code Failed}, never sent again automatically (ADR-0007). Nothing here logs an address.
 */
@Slf4j
@Service
public class MasterEmailTransactions {

    private final MasterEmailRepository emails;
    private final MasterRepository masters;
    private final ReferralRepository referrals;
    private final MasterEmailLinks links;

    public MasterEmailTransactions(MasterEmailRepository emails, MasterRepository masters,
                                   ReferralRepository referrals, MasterEmailLinks links) {
        this.emails = emails;
        this.masters = masters;
        this.referrals = referrals;
        this.links = links;
    }

    /**
     * One email for this Master and period, unless they already have one: {@code Pending}, or {@code Skipped} when
     * the kind's template is unprovisioned, so provisioning it later never sends what was owed before.
     *
     * @return the state written, or empty when the row already existed
     */
    @Transactional
    public Optional<MasterEmailState> enqueue(UUID masterId, MasterEmailKind kind, String periodKey,
                                              List<UUID> referralIds) {
        MasterEmailState state = links.isProvisioned(kind) ? MasterEmailState.Pending : MasterEmailState.Skipped;
        return emails.enqueue(UUID.randomUUID(), masterId, kind, periodKey, referralIds, state, AuditClock.now())
            ? Optional.of(state) : Optional.empty();
    }

    /**
     * What one closed window's digest step did.
     *
     * @param emails how many Masters were given a digest row
     * @param state the state those rows were written in: {@code Pending}, or {@code Skipped} when the digest template
     *     is unprovisioned
     * @param unassigned referrals counted towards the window with no Masters-list entry (pre-V43 claims), not listed
     */
    public record DigestEnqueued(int emails, MasterEmailState state, int unassigned) {
    }

    /**
     * A closed window's digests (plan D3, step 2), in one transaction: its {@code deedpoll_window_digest_run} row,
     * then one {@code DIGEST} row per Master with referrals still {@code Claimed} in it, listing them in the Master's
     * page order. The run row and the emails commit together, so a pod that fails part-way leaves the window for the
     * next run, and a pod that finds the run row already written enqueues nothing.
     *
     * @return empty when another run already took this window
     */
    @Transactional
    public Optional<DigestEnqueued> enqueueDigests(ReferralWindow window) {
        if (!emails.recordDigestRun(window.date(), window.slot(), AuditClock.now())) {
            return Optional.empty();
        }
        Map<UUID, List<UUID>> byMaster = new LinkedHashMap<>();
        int unassigned = 0;
        for (MasterEmailRepository.WindowReferral referral : emails.claimedInWindow(window.date(), window.slot())) {
            if (referral.masterId() == null) {
                unassigned++;
            } else {
                byMaster.computeIfAbsent(referral.masterId(), master -> new ArrayList<>()).add(referral.referralId());
            }
        }
        MasterEmailState state = links.isProvisioned(MasterEmailKind.DIGEST)
            ? MasterEmailState.Pending : MasterEmailState.Skipped;
        String periodKey = MasterEmailPersonalisation.digestPeriod(window);
        int written = 0;
        for (Map.Entry<UUID, List<UUID>> master : byMaster.entrySet()) {
            if (enqueue(master.getKey(), MasterEmailKind.DIGEST, periodKey, master.getValue()).isPresent()) {
                written++;
            }
        }
        return Optional.of(new DigestEnqueued(written, state, unassigned));
    }

    /**
     * What one reminder run's step did.
     *
     * @param emails how many Masters were given a reminder row
     * @param state the state those rows were written in: {@code Pending}, or {@code Skipped} when the reminder
     *     template is unprovisioned
     * @param reminders how many referrals' reminders were claimed
     */
    public record RemindersEnqueued(int emails, MasterEmailState state, int reminders) {
    }

    /**
     * A reminder run (plan D3, step 3), in one transaction: claims every reminder due at {@code now}, then gives each
     * Master with one claimed a {@code REMINDER} row listing <strong>every</strong> referral still waiting for their
     * direction (D-4), not only the ones that fell due. The claims and the emails commit together, so a run that
     * fails part-way claims nothing.
     *
     * @param periodKey the run's key, which no other run shares
     * @throws IllegalStateException when a Master already has a reminder under {@code periodKey}, which rolls the
     *     run's claims back rather than leave them claimed with no email
     */
    @Transactional
    public RemindersEnqueued enqueueReminders(LocalDateTime now, Duration interval, String periodKey) {
        List<MasterEmailRepository.DueReminder> due = emails.claimDueReminders(now, interval);
        Set<UUID> byMaster = new LinkedHashSet<>();
        due.forEach(reminder -> byMaster.add(reminder.masterId()));
        MasterEmailState state = links.isProvisioned(MasterEmailKind.REMINDER)
            ? MasterEmailState.Pending : MasterEmailState.Skipped;
        for (UUID master : byMaster) {
            if (enqueue(master, MasterEmailKind.REMINDER, periodKey, emails.outstandingFor(master)).isEmpty()) {
                throw new IllegalStateException("A reminder run's key was used twice");
            }
        }
        return new RemindersEnqueued(byMaster.size(), state, due.size());
    }

    @Transactional(readOnly = true)
    public List<UUID> candidates(int limit) {
        return emails.claimable(AuditClock.now(), limit);
    }

    /**
     * {@code Sending} rows whose claim expired, as {@code Failed}: the worker committed send authority and never
     * reported back, so Notify may have the email.
     */
    @Transactional
    public int failAbandoned() {
        int failed = emails.failAbandoned(NotificationFailure.TECHNICAL.name(), AuditClock.now());
        if (failed > 0) {
            log.error("{} Master emails were abandoned after their send started; investigate before any resend",
                      failed);
        }
        return failed;
    }

    /**
     * Claims one row, or settles one that may not be claimed: a template unprovisioned since it was written is
     * {@code Skipped}, a row with its attempts used up is {@code Failed}.
     */
    @Transactional
    public Optional<MasterEmailClaim> claim(UUID id) {
        LocalDateTime now = AuditClock.now();
        MasterEmailRepository.Row row = emails.lock(id).orElse(null);
        if (row == null || row.state() != MasterEmailState.Pending
            || row.leaseUntil() != null && row.leaseUntil().isAfter(now)
            || row.nextAttemptAt() != null && row.nextAttemptAt().isAfter(now)) {
            return Optional.empty();
        }
        if (row.sendStartedAt() != null) {
            emails.fail(id, MasterEmailState.Failed, NotificationFailure.TECHNICAL.name(), now);
            log.error("Master email {} may have been sent already; investigate before any resend", id);
            return Optional.empty();
        }
        if (!links.isProvisioned(row.kind())) {
            emails.fail(id, MasterEmailState.Skipped, null, now);
            log.info("Master email {} skipped: template {} is not provisioned", id, row.kind().templateKey());
            return Optional.empty();
        }
        if (NotificationBackoff.exhausted(row.attempts())) {
            emails.fail(id, MasterEmailState.Failed, NotificationFailure.TEMPORARY.name(), now);
            log.warn("Master email {} failed: its claims expired {} times without a completed attempt", id,
                     row.attempts());
            return Optional.empty();
        }
        UUID owner = UUID.randomUUID();
        if (!emails.claim(id, owner, NotificationBackoff.leaseUntil(now))) {
            return Optional.empty();
        }
        return Optional.of(new MasterEmailClaim(id, owner, row.attempts() + 1, row.masterId(), row.kind(),
                                                row.periodKey(), row.referralIds()));
    }

    /**
     * The email, read now: the Masters list's current address and name, and the listed referrals as they are.
     *
     * @return empty when none of the referrals is left to list (their cases were deleted)
     * @throws IllegalStateException when the Master is missing, which V44's foreign key forbids
     */
    @Transactional(readOnly = true)
    public Optional<MasterEmailContent> content(MasterEmailClaim claim) {
        Master master = masters.find(claim.masterId())
            .orElseThrow(() -> new IllegalStateException("A Master email names no Masters-list entry"));
        Map<UUID, Referral> found = referrals.findWithCaseByIdIn(claim.referralIds()).stream()
            .collect(Collectors.toMap(Referral::getId, Function.identity()));
        List<MasterEmailPersonalisation.Line> lines = claim.referralIds().stream()
            .map(found::get)
            .filter(Objects::nonNull)
            .map(referral -> new MasterEmailPersonalisation.Line(
                referral.getId(), referral.getDeedPoll().getDisplayReference(), referral.getReasons(),
                referral.getUrgency(), referral.getCreatedOn()))
            .toList();
        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new MasterEmailContent(
            links.templateId(claim.kind()), master.email(),
            MasterEmailPersonalisation.of(master.name(), claim.kind(), claim.periodKey(), lines, links)));
    }

    /**
     * Commits {@code Sending} for this claim, which is the authority to make the one call.
     */
    @Transactional
    public boolean startSend(MasterEmailClaim claim) {
        return emails.startSend(claim.id(), claim.owner(), claim.attempt(), AuditClock.now());
    }

    /** Nothing it listed is left, so nothing is sent. */
    @Transactional
    public void skip(MasterEmailClaim claim) {
        if (emails.settle(claim.id(), claim.owner(), claim.attempt(), MasterEmailState.Skipped, null,
                          AuditClock.now())) {
            log.info("Master email {} skipped: none of its referrals is left to list", claim.id());
        }
    }

    /**
     * Settles the attempt.
     *
     * @return whether Notify accepted it and the row says so
     */
    @Transactional
    public boolean complete(MasterEmailClaim claim, SendOutcome outcome) {
        LocalDateTime now = AuditClock.now();
        boolean settled;
        boolean sent = false;
        switch (outcome) {
            case SendOutcome.Sent accepted -> {
                settled = emails.sent(claim.id(), claim.owner(), claim.attempt(),
                                      accepted.providerNotificationId(), now);
                sent = settled;
            }
            case SendOutcome.Failed failed -> {
                NotificationFailure failure = failed.failure();
                if (failure.retryable() && !NotificationBackoff.exhausted(claim.attempt())) {
                    settled = emails.retryLater(claim.id(), claim.owner(), claim.attempt(), failure.name(),
                                                NotificationBackoff.nextAttemptAt(now, claim.attempt()));
                    log.warn("Master email {} will be tried again ({}, attempt {})", claim.id(), failure,
                             claim.attempt());
                } else {
                    settled = emails.settle(claim.id(), claim.owner(), claim.attempt(), MasterEmailState.Failed,
                                            failure.name(), now);
                    log.error("Master email {} failed ({}, attempt {}); investigate before any resend", claim.id(),
                              failure, claim.attempt());
                }
            }
        }
        if (!settled) {
            // Enough to find the provider's receipt, and nothing about the recipient.
            switch (outcome) {
                case SendOutcome.Sent accepted -> log.error(
                    "Master email {} attempt {} settlement rejected; Notify accepted it as {}; investigate",
                    claim.id(), claim.attempt(), accepted.providerNotificationId());
                case SendOutcome.Failed failed -> log.error(
                    "Master email {} attempt {} settlement rejected; outcome {}; investigate before any resend",
                    claim.id(), claim.attempt(), failed.failure());
            }
        }
        return sent;
    }
}
