package dev.infinia.store.app.upstream;

import dev.infinia.store.domain.model.UpstreamSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Upstream aggregation SPI (plan §3.2): discover entries from a source and
 * normalize them into store-publishable items. Implementations only discover
 * and fetch — publishing always flows through the scan → review → sign
 * pipeline; no adapter may bypass it.
 */
public interface UpstreamAdapter {

    String type();

    /** Detected/pinned revision context shared by all items of one discovery. */
    record SourceContext(String repoUrl, String ref, String commitSha) {}

    /**
     * One normalized upstream entry. SKILL items carry rooted files
     * (SKILL.md at the root); MCP items carry the reviewable template bytes.
     */
    record NormalizedItem(
            String externalId,
            String kind,
            String name,
            String slug,
            String description,
            String version,
            String sourcePath,
            String sourceUrl,
            Map<String, byte[]> skillFiles,
            byte[] mcpTemplate,
            String license) {
    }

    List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException;

    String AUTO = "AUTO";
    String CLAUDE_MARKETPLACE = "CLAUDE_MARKETPLACE";
    String SKILL_REPOSITORY = "SKILL_REPOSITORY";
    String MCP_REGISTRY = "MCP_REGISTRY";
}
