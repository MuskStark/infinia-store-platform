package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.StatusService;
import dev.infinia.store.contract.api.StatusDtos;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Public service-status page (需求：store 服务监控页): anonymous like the
 * catalog — availability information must be reachable when things are broken,
 * including for clients that cannot sign in.
 */
@RestController
@RequestMapping("/api/v1/status")
public class StatusController {

    private final StatusService status;
    private final StoreProperties properties;

    public StatusController(StatusService status, StoreProperties properties) {
        this.status = status;
        this.properties = properties;
    }

    @GetMapping
    public StatusDtos.StatusPageDto status() {
        return status.page();
    }

    @GetMapping("/incidents")
    public List<StatusDtos.IncidentDto> incidents(
            @RequestParam(defaultValue = "50") int limit) {
        return status.incidents(limit);
    }

    /**
     * The monitor's public address at runtime (STORE_MONITOR_PUBLIC_URL) so the
     * bundled SPA can hand /status off without rebuilding per deployment.
     */
    @GetMapping("/monitor")
    public StatusDtos.MonitorLinkDto monitor() {
        return new StatusDtos.MonitorLinkDto(properties.monitorPublicUrl());
    }
}
