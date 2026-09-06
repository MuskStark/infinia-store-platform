package dev.infinia.monitor.service;

import java.util.Collection;

/** The status indicator ladder shared by every monitor component. */
public final class Indicators {

    public static final String OPERATIONAL = "operational";
    public static final String DEGRADED = "degraded";
    public static final String PARTIAL_OUTAGE = "partial_outage";
    public static final String MAJOR_OUTAGE = "major_outage";
    public static final String NO_DATA = "no_data";

    private Indicators() {}

    /** Worst-of aggregation over the whole ladder. */
    public static String worst(Collection<String> indicators) {
        int rank = 0;
        for (String indicator : indicators) {
            rank = Math.max(rank, rank(indicator));
        }
        return switch (rank) {
            case 1 -> DEGRADED;
            case 2 -> PARTIAL_OUTAGE;
            case 3 -> MAJOR_OUTAGE;
            default -> OPERATIONAL;
        };
    }

    public static int rank(String indicator) {
        return switch (indicator) {
            case DEGRADED -> 1;
            case PARTIAL_OUTAGE -> 2;
            case MAJOR_OUTAGE -> 3;
            default -> 0;
        };
    }
}
