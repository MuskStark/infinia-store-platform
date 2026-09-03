package dev.infinia.store.app.upstream;

import dev.infinia.store.app.service.UpstreamSyncService;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.service.UuidV7;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Indexes enabled sources that have never populated the catalog. This only reads
 * upstream metadata; artifact repositories are still fetched exclusively by the
 * download path and are never persisted by the store.
 *
 * <p>Before indexing, the store seeds its default upstream sources (SkillHub,
 * WorkBuddy's open skill platform) so a deployment aggregates them without a
 * manual registration. The seeding is idempotent per source name and can be
 * turned off with {@code store.upstream.defaults.enabled=false}.</p>
 */
@Component
public class UpstreamCatalogBootstrap {

    private static final Logger log = LoggerFactory.getLogger(UpstreamCatalogBootstrap.class);

    private final PublishingRepositories.UpstreamSourceRepository upstreams;
    private final UpstreamSyncService sync;
    private final boolean defaultsEnabled;
    private final String skillhubUrl;

    public UpstreamCatalogBootstrap(
            PublishingRepositories.UpstreamSourceRepository upstreams,
            UpstreamSyncService sync,
            @Value("${store.upstream.defaults.enabled:true}") boolean defaultsEnabled,
            @Value("${store.upstream.defaults.skillhub-url:"
                    + "https://api.skillhub.cn/api/skills?pages=1}") String skillhubUrl) {
        this.upstreams = upstreams;
        this.sync = sync;
        this.defaultsEnabled = defaultsEnabled;
        this.skillhubUrl = skillhubUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void indexNeverSyncedSources() {
        if (defaultsEnabled) {
            seedDefault("SkillHub (WorkBuddy)", skillhubUrl, "skillhub",
                    UpstreamAdapter.SKILLHUB_REGISTRY);
        }
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

    /** Inserts the default source once per deployment; never touches admin rows. */
    private void seedDefault(String name, String marketplaceUrl, String namespace,
            String adapterType) {
        if (upstreams.findByName(name).isPresent()) {
            return;
        }
        upstreams.save(new UpstreamSource(UuidV7.generate(), name, marketplaceUrl,
                namespace, true, null, null, null, adapterType));
        log.info("Seeded default upstream source {} → {}", name, marketplaceUrl);
    }
}
