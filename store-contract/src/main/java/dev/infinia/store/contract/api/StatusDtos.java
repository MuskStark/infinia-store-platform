package dev.infinia.store.contract.api;

import java.util.List;

/**
 * DTOs for the public service-status page (需求：store 服务监控页, modeled on
 * the npm status page): an overall indicator, per-component 90-day uptime
 * history and the past-incidents feed.
 */
public final class StatusDtos {

    private StatusDtos() {}

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

    public record ComponentDto(
            String key,
            String indicator,
            /** Uptime percentage over the observed window, e.g. 99.98; null when no data. */
            Double uptime90d,
            List<DayDto> history) {
    }

    /** One UTC day of a component's history (90 days back to today). */
    public record DayDto(
            String date,
            String indicator,
            Double uptimePercent) {
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
}
