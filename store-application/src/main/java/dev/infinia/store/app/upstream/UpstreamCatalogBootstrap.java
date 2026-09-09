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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Indexes enabled sources that have never populated the catalog. The sync
 * materializes every imported payload into the store's blob storage so
 * downstream delivery surfaces real, verifiable digests (audit 3.1).
 *
 * <p>Before indexing, the store seeds its default upstream sources (SkillHub,
 * WorkBuddy's open skill platform) so a deployment aggregates them without a
 * manual registration. The seeding is idempotent per source name and can be
 * turned off with {@code store.upstream.defaults.enabled=false}.</p>
 *
 * <p>A failed sync would otherwise degrade the status page's upstream
 * component until the next reboot; the hourly retry lets a transient
 * upstream outage (a 503, a network blip) self-heal back to green.</p>
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
        syncFailedSources("Initial upstream metadata index");
    }

    /**
     * Retries enabled sources whose last sync failed, hourly — a dead-looking
     * upstream component otherwise sticks until the next reboot, painting the
     * whole page yellow while the store itself is perfectly healthy.
     */
    @Scheduled(initialDelayString = "${store.upstream.retry-interval-ms:3600000}",
            fixedDelayString = "${store.upstream.retry-interval-ms:3600000}")
    public void retryFailedSyncs() {
        syncFailedSources("Upstream retry");
    }

    private void syncFailedSources(String logLabel) {
        upstreams.findAll().stream()
                .filter(source -> source.enabled()
                        && (source.lastSyncAt() == null
                                || !Boolean.TRUE.equals(source.lastSyncOk())))
                .forEach(source -> {
                    UpstreamSyncService.SyncResult result = sync.sync(source.id());
                    log.info("{} {}: imported={}, skipped={}, failed={}", logLabel,
                            source.name(), result.imported(), result.skipped(),
                            result.failed());
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
