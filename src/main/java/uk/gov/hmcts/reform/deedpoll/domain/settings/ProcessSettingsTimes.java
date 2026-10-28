package uk.gov.hmcts.reform.deedpoll.domain.settings;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A window time on the wire: {@code HH:mm}, 24-hour, on the quarter hour.
 */
public final class ProcessSettingsTimes {

    /** The published pattern, and the one a value must match. */
    public static final String PATTERN = "^([01][0-9]|2[0-3]):(00|15|30|45)$";

    private static final Pattern COMPILED = Pattern.compile(PATTERN);

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private ProcessSettingsTimes() {
    }

    public static String format(LocalTime time) {
        return time.format(FORMAT);
    }

    /** The time a value names, or empty if it is not a string in the published pattern. */
    public static Optional<LocalTime> parse(Object value) {
        if (value instanceof String text && COMPILED.matcher(text).matches()) {
            return Optional.of(LocalTime.parse(text, FORMAT));
        }
        return Optional.empty();
    }
}
