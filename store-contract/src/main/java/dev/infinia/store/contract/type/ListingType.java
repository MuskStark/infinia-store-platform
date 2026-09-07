package dev.infinia.store.contract.type;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The five artifact classes share one catalog but are routed to dedicated
 * type installers on the client (design §6.2).
 */
public enum ListingType {
    APP,
    PLUGIN,
    SKILL,
    MCP,
    FLOW;

    private static final String URI_SCHEME = "infinia";

    private static final String VALID_VALUES = Arrays.stream(values())
            .map(t -> t.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(", "));

    /**
     * Parses a wire-format listing type (case-insensitive). Unlike
     * {@link #valueOf} the failure message lists the accepted values, so a bad
     * {@code ?type=} query parameter surfaces as an actionable 400 problem
     * detail instead of a raw "No enum constant" error.
     */
    public static ListingType parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(
                    "type must be one of: " + VALID_VALUES);
        }
        try {
            return valueOf(input.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown type '" + input.trim() + "' — must be one of: " + VALID_VALUES);
        }
    }

    public static ListingType fromUriScheme(String value) {
        for (ListingType t : values()) {
            if (t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown listing type: " + value);
    }
}
