package dev.infinia.store.app.service;

import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Release.ArtifactInfo;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.infrastructure.export.LocalGitExporter;
import dev.infinia.store.scanner.SafeZip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Exports SKILL and MCP listings as FengYu-consumable ecosystem packages
 * (design §6.2: unified catalog, type-specific installers).
 *
 * The FengYu host installs skills and MCP servers only through its
 * CLAUDE/CODEX/GROK source types: it git-clones the package, reads the
 * ecosystem manifest (version + skills paths + mcpServers block) and imports
 * skills plus disabled MCP server definitions. This service materializes each
 * published listing as a tiny local git repo carrying a
 * {@code .claude-plugin/plugin.json}, so a single CLAUDE source registered in
 * the host covers skills AND MCP templates — the same store, same lifecycle
 * (publish → review → signed release) as plugins.
 */
@Service
public class EcosystemExportService {

    private static final Logger log = LoggerFactory.getLogger(EcosystemExportService.class);

    private final ReleaseRepository releases;
    private final ListingRepository listings;
    private final BlobStorage blobs;
    private final LocalGitExporter exporter;
    private final ObjectMapper mapper = new ObjectMapper();

    public EcosystemExportService(ReleaseRepository releases, ListingRepository listings,
            BlobStorage blobs, LocalGitExporter exporter) {
        this.releases = releases;
        this.listings = listings;
        this.blobs = blobs;
        this.exporter = exporter;
    }

    /** One CLAUDE marketplace entry per exported listing. */
    public record MarketplaceEntry(String name, String description, String category,
            String homepage, List<String> keywords, Author author, Source source) {}

    public record Author(String name) {}

    public record Source(String source, String url) {}

    /**
     * Builds the CLAUDE marketplace document, exporting/refreshing every eligible
     * listing's git repo first so the served document always matches disk state.
     */
    public Map<String, Object> marketplace() {
        List<MarketplaceEntry> plugins = new ArrayList<>();
        exportType(ListingType.MCP, plugins);
        exportType(ListingType.SKILL, plugins);
        // Stable order for consumers that diff the marketplace between refreshes.
        plugins.sort((a, b) -> a.name().compareTo(b.name()));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("plugins", plugins);
        return document;
    }

    private void exportType(ListingType type, List<MarketplaceEntry> sink) {
        Map<UUID, Release> latest = new HashMap<>();
        for (Release release : releases.findVisibleByType(type)) {
            latest.merge(release.listingId, release,
                    (a, b) -> a.version.compareTo(b.version) >= 0 ? a : b);
        }
        if (latest.isEmpty()) {
            return;
        }
        Map<UUID, Listing> listingById = new HashMap<>();
        for (Listing listing : listings.findByIds(new ArrayList<>(latest.keySet()))) {
            listingById.put(listing.id, listing);
        }
        for (Map.Entry<UUID, Release> e : latest.entrySet()) {
            Listing listing = listingById.get(e.getKey());
            if (listing == null || !listing.isPubliclyVisible()) {
                continue;
            }
            try {
                String repoKey = type.name().toLowerCase() + "-" + listing.id;
                Map<String, byte[]> files = type == ListingType.MCP
                        ? mcpPackageFiles(listing, e.getValue())
                        : skillPackageFiles(listing, e.getValue());
                if (files == null) {
                    continue;
                }
                exporter.export(repoKey, files,
                        listing.namespace + "." + listing.slug + " " + e.getValue().version);
                sink.add(new MarketplaceEntry(
                        listing.namespace + "-" + listing.slug,
                        listing.summary("en"),
                        listing.category,
                        null,
                        listing.tags,
                        new Author(listing.namespace),
                        new Source("url", exporter.repoUrl(repoKey))));
            } catch (IOException | RuntimeException ex) {
                log.warn("Ecosystem export failed for {}: {}",
                        listing.coordinate(), ex.getMessage());
            }
        }
    }

    /**
     * MCP template → package with an mcpServers block. Host semantics: imported
     * servers stay disabled until the user fills the required secrets and enables
     * them — exactly the store's MCP policy (design §6.4, ADR-004).
     */
    private Map<String, byte[]> mcpPackageFiles(Listing listing, Release release)
            throws IOException {
        ArtifactInfo artifact = packageArtifact(release);
        if (artifact == null) {
            return null;
        }
        JsonNode template;
        try (InputStream in = blobs.open(artifact.blobKey())) {
            template = mapper.readTree(in.readAllBytes());
        }
        ObjectNode server = mapper.createObjectNode();
        String urlTemplate = template.path("urlTemplate").asString(null);
        String transport = template.path("transport").asString("STREAMABLE_HTTP");
        if (urlTemplate != null && !"STDIO".equals(transport)) {
            server.put("url", urlTemplate);
            JsonNode secrets = template.path("requiredSecrets");
            if (secrets.isArray() && !secrets.isEmpty()) {
                ObjectNode headers = mapper.createObjectNode();
                for (JsonNode secret : secrets) {
                    // Placeholder — the host marks imported servers disabled and the
                    // user replaces these before enabling (secrets stay local).
                    headers.put(secret.path("name").asString("authorization"),
                            "REQUIRED_SECRET");
                }
                server.set("headers", headers);
            }
        } else {
            log.info("Skipping STDIO MCP template {} (no remote URL to declare)",
                    listing.coordinate());
            return null;
        }
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("name", listing.namespace + "." + listing.slug);
        manifest.put("version", release.version.toString());
        ObjectNode servers = mapper.createObjectNode();
        servers.set(listing.namespace + "." + listing.slug, server);
        manifest.set("mcpServers", servers);

        Map<String, byte[]> files = new TreeMap<>();
        files.put(".claude-plugin/plugin.json",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        return files;
    }

    /** Skill package: the .fys SKILL.md re-rooted plus a declaring manifest. */
    private Map<String, byte[]> skillPackageFiles(Listing listing, Release release)
            throws IOException {
        ArtifactInfo artifact = packageArtifact(release);
        if (artifact == null) {
            return null;
        }
        byte[] fys;
        try (InputStream in = blobs.open(artifact.blobKey())) {
            fys = in.readAllBytes();
        }
        Map<String, SafeZip.ExtractedFile> extracted =
                SafeZip.extract(new ByteArrayInputStream(fys), SafeZip.Limits.defaults());
        SafeZip.ExtractedFile skillMd = extracted.get("SKILL.md");
        if (skillMd == null) {
            log.warn("Skill {} has no root SKILL.md; skipping ecosystem export",
                    listing.coordinate());
            return null;
        }
        String skillDir = "skills/" + listing.namespace + "." + listing.slug;
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("name", listing.namespace + "." + listing.slug);
        manifest.put("version", release.version.toString());
        manifest.putArray("skills").add(skillDir);

        Map<String, byte[]> files = new TreeMap<>();
        files.put(".claude-plugin/plugin.json",
                mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        files.put(skillDir + "/SKILL.md", skillMd.content());
        return files;
    }

    private ArtifactInfo packageArtifact(Release release) {
        return release.artifacts.stream()
                .filter(a -> a.kind() == ArtifactKind.PACKAGE)
                .findFirst()
                .orElse(release.artifacts.isEmpty() ? null : release.artifacts.get(0));
    }
}
