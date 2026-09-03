package dev.infinia.store.app.upstream;

import dev.infinia.store.domain.model.UpstreamSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Upstream aggregation SPI. Discovery is metadata-only: it may read an upstream
 * catalog/manifest, but must not download the referenced repository/archive.
 * Payload materialization is a separate operation invoked only by a user-initiated
 * download.
 */
public interface UpstreamAdapter {

    String type();

    /** Detected/pinned revision context shared by all items of one discovery. */
    record SourceContext(String repoUrl, String ref, String commitSha) {}

    /**
     * One normalized upstream entry. During discovery {@code skillFiles} and
     * {@code mcpTemplate} are null. They are populated only by materialize().
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

    /** Disk-backed payload prepared inside one download request workspace. */
    record MaterializedPayload(NormalizedItem metadata, Path skillDirectory,
            Path mcpTemplate) {}

    List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException;

    /**
     * Fetches and normalizes one selected payload for immediate delivery. The
     * returned bytes are request-scoped and must never be written to blob storage.
     */
    default NormalizedItem materialize(UpstreamSource source, NormalizedItem discovered,
            RepoFetcher fetcher) throws IOException, InterruptedException {
        return discovered;
    }

    /**
     * Disk-backed materialization used by live delivery. Adapters with repository
     * payloads override this method so archives and extracted files never become
     * one large in-memory map.
     */
    default MaterializedPayload materializeToDirectory(UpstreamSource source,
            NormalizedItem discovered, RepoFetcher fetcher, Path workspace)
            throws IOException, InterruptedException {
        NormalizedItem materialized = materialize(source, discovered, fetcher);
        if ("MCP".equals(materialized.kind()) && materialized.mcpTemplate() != null) {
            Path template = workspace.resolve("mcp-template.json");
            Files.write(template, materialized.mcpTemplate());
            return new MaterializedPayload(materialized, null, template);
        }
        Path skill = workspace.resolve("skill");
        Files.createDirectories(skill);
        if (materialized.skillFiles() != null) {
            for (Map.Entry<String, byte[]> entry : materialized.skillFiles().entrySet()) {
                Path file = skill.resolve(entry.getKey()).normalize();
                if (!file.startsWith(skill)) {
                    throw new IOException("Skill path escapes workspace: " + entry.getKey());
                }
                Files.createDirectories(file.getParent());
                Files.write(file, entry.getValue());
            }
        }
        return new MaterializedPayload(materialized, skill, null);
    }

    String AUTO = "AUTO";
    String CLAUDE_MARKETPLACE = "CLAUDE_MARKETPLACE";
    String SKILL_REPOSITORY = "SKILL_REPOSITORY";
    String MCP_REGISTRY = "MCP_REGISTRY";
    String SKILLHUB_REGISTRY = "SKILLHUB_REGISTRY";
}
