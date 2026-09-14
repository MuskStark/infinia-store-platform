package dev.infinia.monitor.service;

import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Placeholder transport until the SSE stream lands: drop-in bean so the probing
 * layer can be wired (and tested) without a live push channel. Replaced by the
 * real emitter-backed bus in the SSE phase.
 */
@Configuration
class StatusEventBusConfig {

    private static final Logger log = LoggerFactory.getLogger(StatusEventBusConfig.class);

    @Bean
    @ConditionalOnMissingBean(StatusEventBus.class)
    StatusEventBus loggingStatusEventBus() {
        return new StatusEventBus() {
            @Override
            public void componentUpdated(ComponentDto component, String overall, String checkedAt) {
                log.debug("component.updated {} -> {} (overall {})", component.key(),
                        component.indicator(), overall);
            }

            @Override
            public void incidentUpdated(IncidentDto incident) {
                log.debug("incident.updated {} {}", incident.incidentId(), incident.status());
            }

            @Override
            public void historyUpdated(String componentKey, Double uptime90d, List<DayDto> history) {
                log.debug("history.updated {} uptime={}", componentKey, uptime90d);
            }
        };
    }
}
