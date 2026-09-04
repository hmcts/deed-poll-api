package uk.gov.hmcts.reform.deedpoll.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ports the {@code InvalidInputError} blocks of {@code reference/ts/errors.test.ts}.
 *
 * <p>The reference's remaining blocks cover four error classes and a {@code zodErrorToFieldErrors}
 * helper that are not ported yet -- see {@link InvalidInputError} for why -- and will be ported with
 * the modules that need them. The "discriminating name" block ports as a claim about the class rather
 * than about a {@code name} property: in Java the type is what a mapper switches on.
 */
class InvalidInputErrorTest {

    @Test
    @DisplayName("should remain a RuntimeException so an unrecognised error still reaches a generic handler")
    void shouldRemainARuntimeException() {
        assertThat(new InvalidInputError("bad")).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("should be catchable by its own type, which is what a mapper switches on")
    void shouldBeCatchableByItsOwnType() {
        assertThatThrownBy(() -> {
            throw new InvalidInputError("bad");
        }).isExactlyInstanceOf(InvalidInputError.class);
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
