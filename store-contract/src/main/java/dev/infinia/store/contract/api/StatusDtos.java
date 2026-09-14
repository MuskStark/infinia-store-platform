package dev.infinia.store.contract.api;

import java.util.List;

/**
 * DTOs for the public service-status page (需求：store 服务监控页, modeled on
 * the npm status page): an overall indicator, per-component 90-day uptime
 * history and the past-incidents feed.
 */
public final class StatusDtos {

    private StatusDtos() {}

    /** Indicator ladder values shared by every layer that judges status. */
    public static final String OPERATIONAL = "operational";
    public static final String DEGRADED = "degraded";
    public static final String PARTIAL_OUTAGE = "partial_outage";
    public static final String MAJOR_OUTAGE = "major_outage";
    public static final String NO_DATA = "no_data";

    /**
     * Indicator ladder used by the overall page and every component/day:
     * {@code operational}, {@code degraded}, {@code partial_outage},
     * {@code major_outage}; history days without data report {@code no_data}.
     */
    public record StatusPageDto(
            String indicator,
            List<ComponentDto> components,
            String checkedAt) {
    }

    /**
     * One component's live state plus its 90-day history. The observation
     * metadata fields (added for the real-time status feed) are nullable so
     * older producers/consumers interoperate during a rolling upgrade:
     * {@code observedAt} is the freshest observation feeding the indicator,
     * {@code lastSuccessAt} the last operational one, {@code pending} marks a
     * conflicting observation awaiting confirmation (确认中), and {@code stale}
     * marks an observation older than the validity window.
     */
    public record ComponentDto(
            String key,
            String indicator,
            /** Uptime percentage over the observed window, e.g. 99.98; null when no data. */
            Double uptime90d,
            List<DayDto> history,
            String observedAt,
            String lastSuccessAt,
            Boolean pending,
            Boolean stale) {

        public ComponentDto(String key, String indicator, Double uptime90d, List<DayDto> history) {
            this(key, indicator, uptime90d, history, null, null, null, null);
        }
    }

    /**
     * One UTC day of a component's history (90 days back to today).
     * {@code coveragePercent} (share of the day covered by valid observations)
     * and {@code sampled} (legacy per-poll sampling statistics) are nullable:
     * interval-based days carry coverage, pre-cutover days carry
     * {@code sampled=true}, and both are absent for simple producers.
     */
    public record DayDto(
            String date,
            String indicator,
            Double uptimePercent,
            Double coveragePercent,
            Boolean sampled) {

        public DayDto(String date, String indicator, Double uptimePercent) {
            this(date, indicator, uptimePercent, null, null);
        }
    }

    public record IncidentDto(
            String incidentId,
            String component,
            String title,
            /** outage | degraded */
            String impact,
            /** investigating | resolved */
            String status,
            String startedAt,
            String resolvedAt,
            String updatedAt) {
    }

    /**
     * Where the standalone monitor's public status page lives (ADR-011). The
     * store SPA's /status deep link hands off to this address; {@code url} is
     * null when the deployment has not configured one.
     */
    public record MonitorLinkDto(String url) {
    }
}
