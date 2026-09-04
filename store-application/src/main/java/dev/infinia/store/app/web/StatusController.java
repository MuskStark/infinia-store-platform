package dev.infinia.store.app.web;

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

    public StatusController(StatusService status) {
        this.status = status;
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
}
