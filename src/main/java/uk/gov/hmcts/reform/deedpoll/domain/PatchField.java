package uk.gov.hmcts.reform.deedpoll.domain;

import java.util.function.Consumer;

/**
 * One field of a PATCH: absent, cleared, or set to a value.
 *
 * <p>The reference gets these three states free from JavaScript -- a key that is missing, a key holding
 * {@code null}, and a key holding a value -- and every {@code .partial()} schema in it depends on the
 * distinction. {@code DeedPollPatch} keeps it by being a keyed map, which is the right shape for 68
 * fields; this is the same distinction for a record small enough to name its fields, where a keyed map
 * would cost the types.
 *
 * <p>A nullable Java field cannot express it, because absent and cleared would both be {@code null} --
 * and those mean opposite things: leave the value alone, versus remove it. {@link java.util.Optional}
 * cannot either, since it has no room for a legitimate {@code null}. Where a column is NOT NULL there is
 * no cleared state to represent and {@code Optional} is enough, which is why {@code UpdateNoteInput} and
 * {@code UpdateWitnessInput} use it; this type is for a patch whose column IS nullable.
 *
 * <p>It arrives with the documents port, whose {@code UpdateDocumentInput} covers three nullable storage
 * columns and three NOT NULL ones. Both use this, so one patch does not read in two idioms.
 *
 * @param present whether the patch named this field at all
 * @param value the value it named, which may be null -- a deliberate clear
 * @param <T> the field's type
 */
public record PatchField<T>(boolean present, T value) {

    private static final PatchField<?> ABSENT = new PatchField<>(false, null);

    /** The field was not part of the write, so it must be left exactly as it is. */
    @SuppressWarnings("unchecked")
    public static <T> PatchField<T> absent() {
        return (PatchField<T>) ABSENT;
    }

    /**
     * The field was written with a value. A null here is a clear -- see {@link #cleared}.
     */
    public static <T> PatchField<T> of(T value) {
        return new PatchField<>(true, value);
    }

    /** The field was written as null, which empties the column. */
    public static <T> PatchField<T> cleared() {
        return new PatchField<>(true, null);
    }

    /** True when the patch named this field as null, which is a write and not an omission. */
    public boolean isCleared() {
        return present && value == null;
    }

    /**
     * Runs the action if the patch named this field, with whatever it named -- including null.
     *
     * <p>Deliberately unlike {@code Optional.ifPresent}, which would skip the clear. Passing the null
     * through is the point: a setter called with null is how a nullable column is emptied.
     */
    public void ifPresent(Consumer<? super T> action) {
        if (present) {
            action.accept(value);
        }
    }
}
