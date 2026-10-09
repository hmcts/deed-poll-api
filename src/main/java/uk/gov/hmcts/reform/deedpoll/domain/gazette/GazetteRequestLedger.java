package uk.gov.hmcts.reform.deedpoll.domain.gazette;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.reform.deedpoll.domain.ConflictError;
import uk.gov.hmcts.reform.deedpoll.domain.InvalidInputError;
import uk.gov.hmcts.reform.deedpoll.domain.NotFoundError;
import uk.gov.hmcts.reform.deedpoll.domain.auth.CurrentUser;
import uk.gov.hmcts.reform.deedpoll.domain.auth.Role;
import uk.gov.hmcts.reform.deedpoll.domain.notes.CreateNoteInput;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteAuthor;
import uk.gov.hmcts.reform.deedpoll.domain.notes.NoteService;
import uk.gov.hmcts.reform.deedpoll.entity.AuditClock;
import uk.gov.hmcts.reform.deedpoll.entity.DeedPoll;
import uk.gov.hmcts.reform.deedpoll.entity.enums.DeedPollState;
import uk.gov.hmcts.reform.deedpoll.repository.DeedPollRepository;
import uk.gov.hmcts.reform.deedpoll.repository.GazetteRequestRepository;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Internal durable primitives, without a sender or HTTP admission endpoint.
 * The next slice must add preview fingerprint verification inside the locked admission below.
 * Until then the fingerprint is trusted internal input; this service has no HTTP or Notify caller.
 */
@Service
public class GazetteRequestLedger {
    private final GazetteRequestRepository requests;
    private final DeedPollRepository cases;
    private final NoteService notes;
    private final TransactionTemplate transaction;

    public GazetteRequestLedger(GazetteRequestRepository requests, DeedPollRepository cases, NoteService notes,
                                PlatformTransactionManager transactions) {
        this.requests = requests;
        this.cases = cases;
        this.notes = notes;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Reserves a trusted, already confirmed selection. There is deliberately no controller calling this yet.
     * Payload identity is stored here; preview generation/expiry and fingerprint verification belong to slice 2.
     */
    public Reservation reserve(CurrentUser caller, UUID key, List<String> selected, String fingerprint,
                               int recipientRevision, GazetteDeliveryConfiguration configuration) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        String subject = caller.requireUserId();
        List<String> ids = selection(selected);
        if (key == null || fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 512
            || recipientRevision < 0) {
            throw new InvalidInputError("Invalid Gazette request identity.");
        }
        String identity = identity(ids, fingerprint, recipientRevision);
        return commit(() -> {
            requests.lockKey(key);
            GazetteRequest existing = requests.byKey(key).orElse(null);
            if (existing != null) {
                if (!existing.payloadHash().equals(identity) || !existing.submittedById().equals(subject)) {
                    throw new ConflictError("Gazette request key was already used for another submission.");
                }
                return new Reservation(existing, null);
            }
            if (requests.legacyKey(key)) {
                throw new ConflictError("Gazette request key belongs to historical sent evidence.");
            }
            GazetteSettings settings = requests.lockSettings();
            if (settings.recipientEmail() == null || settings.revision() != recipientRevision) {
                throw new ConflictError("Reload and confirm the London Gazette recipient before submitting.");
            }
            Objects.requireNonNull(configuration, "Gazette configuration is required for a new request");
            List<DeedPoll> members = new ArrayList<>();
            for (String id : ids) {
                if (!requests.lockCase(id)) {
                    throw new NotFoundError("Gazette case not found");
                }
                DeedPoll record = cases.findById(id).orElseThrow();
                requireReady(record);
                if (requests.reserved(id)) {
                    throw new ConflictError("A selected case already belongs to a Gazette publication request.");
                }
                members.add(record);
            }
            int adults = (int) members.stream().filter(record -> kind(record).equals("adult")).count();
            LocalDateTime now = AuditClock.now();
            GazetteRequest row = new GazetteRequest(UUID.randomUUID(), key, identity, subject, caller.name(), now,
                settings.recipientEmail(), settings.revision(), adults, members.size() - adults, configuration,
                UUID.randomUUID(), now.plusMinutes(5), GazetteRequestState.Preparing, null, null, null);
            requests.insert(row, fingerprint);
            for (DeedPoll record : members) {
                requests.member(row.id(), record.getId(), record.getDisplayReference(), kind(record));
                note(record.getId(), row, "London Gazette publication request submitted.");
            }
            return new Reservation(row, new Claim(row.id(), row.owner()));
        });
    }

    public GazetteRequest read(CurrentUser caller, UUID id) {
        caller.requireAnyRole(Role.DEEDPOLL_ADMIN);
        return requests.read(id, false).orElseThrow(() -> new NotFoundError("Gazette request not found"));
    }

    /** Commits send authority once, after every required file has been prepared. */
    public boolean startSend(Claim claim, List<GazetteFileEvidence> files) {
        return commit(() -> {
            GazetteRequest row = owned(claim);
            if (!active(row, GazetteRequestState.Preparing)) {
                return false;
            }
            requireFiles(row, files);
            for (GazetteFileEvidence file : files) {
                requests.file(row.id(), file);
            }
            requests.start(row.id(), AuditClock.now());
            return true;
        });
    }

    public boolean preparationFailed(Claim claim) {
        return commit(() -> {
            GazetteRequest row = owned(claim);
            if (!active(row, GazetteRequestState.Preparing)) {
                return false;
            }
            fail(row, GazetteRequestState.NotSent, "PREPARATION_FAILED");
            return true;
        });
    }

    /** Only the existing explicitly known refusal statuses prove a started send was not accepted. */
    public boolean providerRefused(Claim claim, int status) {
        if (status != 400 && status != 403 && status != 429) {
            throw new IllegalArgumentException("This status does not prove Notify refused the email");
        }
        return commit(() -> {
            GazetteRequest row = owned(claim);
            if (!active(row, GazetteRequestState.Sending)) {
                return false;
            }
            fail(row, GazetteRequestState.NotSent, "PROVIDER_REFUSED");
            return true;
        });
    }

    public boolean uncertain(Claim claim) {
        return commit(() -> {
            GazetteRequest row = owned(claim);
            if (!active(row, GazetteRequestState.Sending)) {
                return false;
            }
            fail(row, GazetteRequestState.Uncertain, "SEND_OUTCOME_UNKNOWN");
            return true;
        });
    }

    /** Local acceptance settlement only. This method never invokes the provider. */
    public boolean sent(Claim claim, UUID providerId, Integer version) {
        Objects.requireNonNull(providerId);
        if (version != null && version < 1) {
            throw new IllegalArgumentException("Invalid provider template version");
        }
        return commit(() -> {
            GazetteRequest row = owned(claim);
            if (row == null) {
                return false;
            }
            if (row.providerNotificationId() != null && (!providerId.equals(row.providerNotificationId())
                || !Objects.equals(version, row.templateVersion()))) {
                throw new ConflictError("Conflicting Gazette provider receipt; investigate before any resend.");
            }
            if (row.state() == GazetteRequestState.Sent) {
                return true;
            }
            if (row.state() == GazetteRequestState.Sending && !row.leaseUntil().isAfter(AuditClock.now())) {
                fail(row, GazetteRequestState.Uncertain, "SEND_OUTCOME_UNKNOWN");
                requests.receipt(row.id(), providerId, version);
                return false;
            }
            if (row.state() == GazetteRequestState.Uncertain) {
                requests.receipt(row.id(), providerId, version);
                return false;
            }
            if (!active(row, GazetteRequestState.Sending)) {
                return false;
            }
            LocalDateTime now = AuditClock.now();
            requests.receipt(row.id(), providerId, version);
            requests.sentEvidence(row, now);
            for (String id : requests.members(row.id())) {
                note(id, row, "Notice of publication sent to the London Gazette.");
            }
            requests.finish(row.id(), GazetteRequestState.Sent, null, now);
            return true;
        });
    }

    /** One recovery transition; the bounded scanner is deliberately a later slice. */
    public boolean expire(UUID id) {
        return commit(() -> {
            GazetteRequest row = requests.read(id, true).orElse(null);
            if (row == null || row.leaseUntil().isAfter(AuditClock.now())) {
                return false;
            }
            if (row.state() == GazetteRequestState.Preparing) {
                fail(row, GazetteRequestState.NotSent, "PRE_SEND_EXPIRED");
                return true;
            }
            if (row.state() == GazetteRequestState.Sending) {
                fail(row, GazetteRequestState.Uncertain, "SEND_OUTCOME_UNKNOWN");
                return true;
            }
            return false;
        });
    }

    private GazetteRequest owned(Claim claim) {
        GazetteRequest row = requests.read(claim.batchId(), true).orElse(null);
        return row != null && row.owner().equals(claim.owner()) ? row : null;
    }

    private static boolean active(GazetteRequest row, GazetteRequestState expected) {
        return row != null && row.state() == expected && row.leaseUntil().isAfter(AuditClock.now());
    }

    private void fail(GazetteRequest row, GazetteRequestState state, String reason) {
        requests.finish(row.id(), state, reason, AuditClock.now());
        for (String id : requests.members(row.id())) {
            note(id, row, state == GazetteRequestState.Uncertain
                ? "London Gazette email outcome is uncertain. Investigate before any resend."
                : "London Gazette publication request was not sent.");
        }
    }

    private void note(String id, GazetteRequest row, String text) {
        notes.createNote(id, new CreateNoteInput(text), new NoteAuthor(row.submittedByName()));
    }

    private <T> T commit(Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Gazette ledger operations require their own committed transaction");
        }
        return transaction.execute(status -> action.get());
    }

    private static List<String> selection(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
            || ids.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 255)
            || new HashSet<>(ids).size() != ids.size()) {
            throw new InvalidInputError("Select between 1 and 100 distinct Gazette cases.");
        }
        return ids.stream().sorted().toList();
    }

    private static void requireReady(DeedPoll record) {
        if (!record.isPublishToLondonGazette() || record.getDateNoticeSentToLondonGazette() != null
            || record.getState() != DeedPollState.Enroled || record.getDisplayReference() == null
            || record.getDisplayReference().isBlank()) {
            throw new ConflictError("A selected case is no longer ready for Gazette publication.");
        }
        kind(record);
    }

    private static String kind(DeedPoll record) {
        if (record.getType() == null) {
            throw new ConflictError("Correct missing case types before Gazette publication.");
        }
        return switch (record.getType()) {
            case Adult, AdultTransgender -> "adult";
            case Minor, MinorTransgender -> "minor";
            case Unknown, DeedOfRelinquishment -> throw new ConflictError(
                "A selected case type cannot be included in Gazette publication.");
        };
    }

    private static void requireFiles(GazetteRequest row, List<GazetteFileEvidence> files) {
        if (files == null || files.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Missing Gazette file evidence");
        }
        int required = (row.adultCount() > 0 ? 1 : 0) + (row.minorCount() > 0 ? 1 : 0);
        if (files.size() != required || files.stream().map(GazetteFileEvidence::kind).distinct().count() != required) {
            throw new IllegalArgumentException("Prepare every required Gazette file exactly once");
        }
        for (GazetteFileEvidence file : files) {
            int expected = file.kind().equals("adult") ? row.adultCount() : row.minorCount();
            if (file.caseCount() != expected) {
                throw new IllegalArgumentException("Gazette file membership does not match the request");
            }
        }
    }

    private static String identity(List<String> ids, String fingerprint, int revision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            List<String> values = new ArrayList<>(List.of(Integer.toString(revision), fingerprint));
            values.addAll(ids);
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Claim(UUID batchId, UUID owner) {
        public Claim {
            Objects.requireNonNull(batchId);
            Objects.requireNonNull(owner);
        }
    }

    /** Only a fresh reservation grants the calling operation a claim; a repeated key never does. */
    public record Reservation(GazetteRequest request, Claim claim) {
    }
}
