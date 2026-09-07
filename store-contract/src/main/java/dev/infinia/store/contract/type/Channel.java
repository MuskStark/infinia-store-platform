package dev.infinia.store.contract.type;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/** Release channels with rollout semantics (design §6.1). */
public enum Channel {
    STABLE,
    RC,
    BETA,
    ALPHA,
    NIGHTLY,
    PRIVATE;

    private static final String VALID_VALUES = Arrays.stream(values())
            .map(c -> c.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));

    /**
     * Parses a wire-format channel name (case-insensitive). Unlike {@link #valueOf}
     * the failure message lists the accepted values, so API consumers get an
     * actionable problem detail instead of "No enum constant".
     */
    public static Channel parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                    "channel must be one of: " + VALID_VALUES);
        }
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown channel '" + input.trim() + "' — must be one of: " + VALID_VALUES);
        }
    }
}
