package dev.infinia.store.app.upstream;

import dev.infinia.store.domain.model.UpstreamItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Adapter resolution at download time (audit P1-7): the type the SYNC resolved
 * for an item is authoritative — the download path must replay it instead of
 * re-probing the upstream document, which could disagree (an MCP registry source
 * resolved as CLAUDE_MARKETPLACE makes the externalId unfindable → 500).
 */
class UpstreamAdapterResolutionTest {

    private final UpstreamArtifactService service = new UpstreamArtifactService(
            null, null, null, null,
            List.of(new ClaudeMarketplaceAdapter(), new SkillRepositoryAdapter(),
                    new McpRegistryAdapter(), new SkillHubAdapter()),
            null, null, null, null, null);

    @Test
    void persistedAdapterTypeWinsOverSourceProbing() {
        // AUTO source whose URL looks like a Claude marketplace, but the sync
        // recorded MCP_REGISTRY (the registry document shape won at probe time).
        UpstreamSource source = autoSource("https://example.com/marketplace.json");
        UpstreamItem item = item(UpstreamAdapter.MCP_REGISTRY);

        assertEquals(UpstreamAdapter.MCP_REGISTRY, service.resolve(source, item).type(),
                "the persisted sync-time decision must be replayed");
    }

    @Test
    void persistedSkillHubTypeIsReplayed() {
        UpstreamSource source = autoSource("https://api.skillhub.cn/api/skills?pages=1");
        UpstreamItem item = item(UpstreamAdapter.SKILLHUB_REGISTRY);
        assertEquals(UpstreamAdapter.SKILLHUB_REGISTRY, service.resolve(source, item).type());
    }

    @Test
    void legacyRowsWithoutAdapterTypeFallBackToSourceShape() {
        UpstreamSource source = autoSource("https://api.skillhub.cn/api/skills?pages=1");
        UpstreamItem legacy = item(null);
        assertEquals(UpstreamAdapter.SKILLHUB_REGISTRY,
                service.resolve(source, legacy).type(),
                "rows persisted before adapter typing keep the legacy probe");
    }

    @Test
    void unknownRecordedTypeIsRejectedLoudly() {
        UpstreamSource source = autoSource("https://example.com/marketplace.json");
        UpstreamItem item = item("SOMETHING_ELSE");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.resolve(source, item));
        assertEquals("Unknown adapter type SOMETHING_ELSE recorded for upstream item "
                + item.id(), e.getMessage());
    }

    private static UpstreamSource autoSource(String url) {
        return new UpstreamSource(UUID.randomUUID(), "test", url, "agg", true,
                Instant.now(), true, null, "AUTO");
    }

    private static UpstreamItem item(String adapterType) {
        return new UpstreamItem(UUID.randomUUID(), UUID.randomUUID(), "external-1", null,
                "https://example.com", "path", null, null, "1.0.0", "cafebabe", adapterType,
                Instant.now(), Instant.now(), null);
    }
}
