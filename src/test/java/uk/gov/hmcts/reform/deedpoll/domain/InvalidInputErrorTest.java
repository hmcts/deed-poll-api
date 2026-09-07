package uk.gov.hmcts.reform.deedpoll.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports the {@code domain errors} blocks of {@code reference/ts/errors.test.ts}.
 *
 * <p>All five error classes are now ported, so the two class-level blocks cover all of them here rather than
 * only {@code InvalidInputError}. The one thing the reference file still has no counterpart for is
 * {@code zodErrorToFieldErrors}: there is no Zod here, and the schemas build {@link FieldError}s directly.
 *
 * <p>The "discriminating name" block ports as a claim about the class rather than about a {@code name}
 * property: in Java the type is what a mapper switches on.
 */
class InvalidInputErrorTest {

    @Test
    @DisplayName("should remain a RuntimeException so an unrecognised error still reaches a generic handler")
    void shouldRemainARuntimeException() {
        assertThat(new InvalidInputError("bad")).isInstanceOf(RuntimeException.class);
        assertThat(new NotFoundError("gone")).isInstanceOf(RuntimeException.class);
        assertThat(new ForbiddenError("nope")).isInstanceOf(RuntimeException.class);
        assertThat(new UnauthorizedError("who")).isInstanceOf(RuntimeException.class);
        assertThat(new ConflictError("in use")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("should be catchable by its own type, which is what a mapper switches on")
    void shouldBeCatchableByItsOwnType() {
        // The reference asserts a discriminating `name` string; the Java equivalent of that claim is that no
        // two of these are the same type, since DomainErrorHandler dispatches on the type alone.
        assertThatThrownBy(() -> {
            throw new InvalidInputError("bad");
        }).isExactlyInstanceOf(InvalidInputError.class);
        assertThatThrownBy(() -> {
            throw new NotFoundError("gone");
        }).isExactlyInstanceOf(NotFoundError.class);
        assertThatThrownBy(() -> {
            throw new ForbiddenError("nope");
        }).isExactlyInstanceOf(ForbiddenError.class);
        assertThatThrownBy(() -> {
            throw new UnauthorizedError("who");
        }).isExactlyInstanceOf(UnauthorizedError.class);
        assertThatThrownBy(() -> {
            throw new ConflictError("in use");
        }).isExactlyInstanceOf(ConflictError.class);
    }

    @Test
    @DisplayName("should default the field errors to empty rather than null")
    void shouldDefaultTheFieldErrorsToEmpty() {
        // A caller doing errors().forEach(...) must not have to null-check.
        assertThat(new InvalidInputError("bad").errors()).isEmpty();
    }

    @Test
    @DisplayName("should retain the field errors it was given")
    void shouldRetainTheFieldErrorsItWasGiven() {
        List<FieldError> errors = List.of(new FieldError("surname", "Enter a surname"));

        InvalidInputError error = new InvalidInputError("Validation failed", errors);

        assertThat(error.errors()).isEqualTo(errors);
        assertThat(error.getMessage()).isEqualTo("Validation failed");
    }

    @Test
    @DisplayName("should not share the caller's list, so the attributed errors cannot change after the throw")
    void shouldNotShareTheCallersList() {
        List<FieldError> errors = new ArrayList<>();
        errors.add(new FieldError("surname", "Enter a surname"));

        InvalidInputError error = new InvalidInputError("Validation failed", errors);
        errors.clear();

        assertThat(error.errors()).containsExactly(new FieldError("surname", "Enter a surname"));
    }
}
