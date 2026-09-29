package uk.gov.hmcts.reform.deedpoll.entity.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a supporting document's remote storage has got to (plan 03a).
 *
 * <p><strong>Not a port</strong>, like {@link CcdCreationAttemptStatus}, on which it is modelled: the member names
 * are the values {@code V25__document_storage_state.sql} stores in {@code "storageState"}, and that migration's CHECK
 * lists them in this order.
 *
 * <p><strong>The whole transition rule is here, with no database in it</strong>, so that Docker-free CI runs it. The
 * one edge that depends on the row rather than the state -- an uncertain upload sent again -- is conditional on the
 * row's upload count, which {@code DocumentStorageRule.mayUploadAgain} decides; {@link #canMoveTo} says only that the
 * edge exists.
 *
 * <p>The principle is plan 02's: a lost response is never read as success. What may have reached CDAM is recorded
 * as uncertain, committed before anything else happens, and it is resolved by an attach -- which is idempotent for
 * the same document and case -- or, for an upload, by exactly one fresh upload while the bytes are still buffered.
 */
public enum DocumentStorageState {

    /** On the filesystem store. Nothing remote was ever involved. Terminal. */
    Local,

    /** Claimed for an upload and committed; nothing has been sent to CDAM yet. */
    UploadPending,

    /**
     * An upload was sent and its answer was lost -- a timeout or an unreadable response -- so it may have reached
     * CDAM. Never served and never reported as stored. Sent again at most once, and only while the row has sent
     * fewer than two; after that it waits, visibly, and the caseworker uploads the file again as a new document. A
     * copy that did land is never attached, so it expires on CDAM's upload TTL.
     */
    UploadUncertain,

    /** CDAM answered the upload: its document id and hash token are on the row, and it is not yet attached. */
    Uploaded,

    /** The attach was sent and its answer was lost. Sent again, because attaching to the same case is idempotent. */
    AttachUncertain,

    /** Attached to the record's CCD case. Terminal for plan 03a, and the only state a download serves from CDAM. */
    Attached,

    /** Definitely nothing stored against the case: CDAM refused the upload, or refused the attach. Terminal. */
    Failed;

    /**
     * The states a document in this state may move to.
     *
     * <p>{@code UploadUncertain -> UploadPending} is the one step backwards, and it is conditional: legal only while
     * the row has sent fewer than {@code DocumentStorageRule.MAX_UPLOAD_ATTEMPTS} uploads, which this method cannot
     * see.
     *
     * <p>{@code Uploaded -> Failed} is a definite refusal of the attach: the upload that answered is never attached,
     * so it expires on CDAM's upload TTL, and nothing is stored against the case. {@code AttachUncertain} has no such
     * edge: once an attach may have landed, only a confirmed attach settles it.
     *
     * @return the legal targets, empty for {@link #Local}, {@link #Attached} and {@link #Failed}
     */
    public Set<DocumentStorageState> successors() {
        return switch (this) {
            case UploadPending -> EnumSet.of(UploadUncertain, Uploaded, Failed);
            case UploadUncertain -> EnumSet.of(UploadPending);
            case Uploaded -> EnumSet.of(AttachUncertain, Attached, Failed);
            case AttachUncertain -> EnumSet.of(Attached);
            case Local, Attached, Failed -> EnumSet.noneOf(DocumentStorageState.class);
        };
    }

    /**
     * Whether a document in this state may move to {@code target}.
     */
    public boolean canMoveTo(DocumentStorageState target) {
        return successors().contains(target);
    }

    /**
     * Whether the row holds CDAM's hash token in this state -- exactly while an attach is still to be confirmed.
     *
     * <p>{@code V25}'s {@code cdamHashToken_transient} CHECK states the same predicate, and
     * {@code DocumentStorageStateTest} holds the two together.
     */
    public boolean holdsHashToken() {
        return this == Uploaded || this == AttachUncertain;
    }

    /**
     * Whether the document is stored and may be served: from the filesystem when {@link #Local}, from CDAM only when
     * {@link #Attached}. Every other state is either still in flight or reported as needing attention.
     */
    public boolean isStored() {
        return this == Local || this == Attached;
    }

    /**
     * Whether the document is parked in a state nothing will move it out of on its own, and so has to be reported:
     * an upload whose answer was lost. Its one fresh upload happens inside the request that sent the first, while the
     * bytes are buffered, so a row found {@link #UploadUncertain} afterwards -- two lost answers, or a process that
     * died between them -- is never sent again. {@link #AttachUncertain} is not here, because it is retried.
     */
    public boolean needsAttention() {
        return this == UploadUncertain;
    }
}
