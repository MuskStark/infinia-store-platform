package dev.infinia.store.app.upstream;

import dev.infinia.store.app.service.UpstreamSyncService;
import dev.infinia.store.domain.port.PublishingRepositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Indexes enabled sources that have never populated the catalog. This only reads
 * upstream metadata; artifact repositories are still fetched exclusively by the
 * download path and are never persisted by the store.
 */
@Component
public class UpstreamCatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCatalogBootstrap.class);

    private final PublishingRepositories.UpstreamSourceRepository upstreams;
    private final UpstreamSyncService sync;

    public UpstreamCatalogBootstrap(
            PublishingRepositories.UpstreamSourceRepository upstreams,
            UpstreamSyncService sync) {
        this.upstreams = upstreams;
        this.sync = sync;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void indexNeverSyncedSources() {
        upstreams.findAll().stream()
                .filter(source -> source.enabled()
                        && (source.lastSyncAt() == null
                                || !Boolean.TRUE.equals(source.lastSyncOk())))
                .forEach(source -> {
                    UpstreamSyncService.SyncResult result = sync.sync(source.id());
                    log.info("Initial upstream metadata index {}: imported={}, skipped={}, "
                                    + "failed={}", source.name(), result.imported(),
                            result.skipped(), result.failed());
                });
    }
}
