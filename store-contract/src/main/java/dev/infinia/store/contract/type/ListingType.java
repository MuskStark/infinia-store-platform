package dev.infinia.store.contract.type;

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

    public static ListingType fromUriScheme(String value) {
        for (ListingType t : values()) {
            if (t.name().equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown listing type: " + value);
    }
}
