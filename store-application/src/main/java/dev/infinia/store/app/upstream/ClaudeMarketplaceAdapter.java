package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * CLAUDE_MARKETPLACE adapter (plan §3.1): consumes
 * {@code .claude-plugin/marketplace.json} from a plain URL or a GitHub repo,
 * supports object sources ({@code source.url}/git-subdir) and the official
 * Anthropic collection form ({@code source:"./"} + {@code skills[]}), then
 * sweeps the repository for skill directories the manifest forgot.
 */
@Component
public class ClaudeMarketplaceAdapter implements UpstreamAdapter {

    @Override
    public String type() {
        return CLAUDE_MARKETPLACE;
    }

    @Override
    public List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException {
        String marketplaceUrl = source.marketplaceUrl();
        JsonNode document;
        String repoUrl = null;
        if (marketplaceUrl.matches("^https://github\\.com/[^/]+/[^/]+/?$")) {
            repoUrl = marketplaceUrl;
            document = fetcher.githubJsonFile(repoUrl, ".claude-plugin/marketplace.json");
        } else {
            document = fetcher.fetchJson(marketplaceUrl);
        }

        List<NormalizedItem> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonNode plugin : document.path("plugins")) {
            if (!plugin.isObject() || plugin.path("name").asString(null) == null) {
                continue;
            }
            JsonNode sourceNode = plugin.path("source");
            if (sourceNode.isTextual()) {
                // Textual sources are paths in the marketplace repository. The
                // manifest can enumerate skills without downloading that repo.
                if (repoUrl == null) {
                    continue;
                }
                boolean viaSkillPaths = false;
                for (JsonNode skillPath : plugin.path("skills")) {
                    viaSkillPaths = true;
                    addMetadata(items, seen, plugin, repoUrl, skillPath.asText());
                }
                if (!viaSkillPaths) {
                    addMetadata(items, seen, plugin, repoUrl, sourceNode.asText());
                }
            } else {
                String entryRepo = sourceNode.path("url").asString(null);
                if (entryRepo != null) {
                    addMetadata(items, seen, plugin, entryRepo,
                            sourceNode.path("path").asString(""));
                }
            }
        }
        return items;
    }

    @Override
    public NormalizedItem materialize(UpstreamSource source, NormalizedItem discovered,
            RepoFetcher fetcher) throws IOException, InterruptedException {
        Map<String, byte[]> repoFiles = fetcher.repoFiles(discovered.sourceUrl(),
                new RepoFetcher.SyncScope());
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(discovered.sourcePath());
        candidates.add(slug(discovered.name()));
        candidates.add("");
        // Archive feeds often omit a subpath. Probe discovered SKILL.md directories
        // only after the explicit metadata candidates, at download time.
        repoFiles.keySet().stream()
                .filter(p -> p.equals("SKILL.md") || p.endsWith("/SKILL.md"))
                .map(p -> p.equals("SKILL.md") ? ""
                        : p.substring(0, p.length() - "/SKILL.md".length()))
                .sorted().forEach(candidates::add);
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            NormalizedItem payload = skillFromDirectory(source, discovered.sourceUrl(),
                    repoFiles, candidate);
            if (payload != null) {
                return new NormalizedItem(discovered.externalId(), discovered.kind(),
                        discovered.name(), discovered.slug(), discovered.description(),
                        discovered.version(), discovered.sourcePath(), discovered.sourceUrl(),
                        payload.skillFiles(), null, discovered.license());
            }
        }
        throw new IOException("No SKILL.md for " + discovered.externalId());
    }

    @Override
    public MaterializedPayload materializeToDirectory(UpstreamSource source,
            NormalizedItem discovered, RepoFetcher fetcher, Path workspace)
            throws IOException, InterruptedException {
        Path repository = fetcher.repoDirectory(discovered.sourceUrl(),
                workspace.resolve("repository"));
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(cleanPath(discovered.sourcePath()));
        if (discovered.sourcePath() != null
                && cleanPath(discovered.sourcePath()).startsWith("skills/")) {
            candidates.add(cleanPath(discovered.sourcePath()).substring("skills/".length()));
        }
        candidates.add(slug(discovered.name()));
        candidates.add("");
        try (var paths = Files.walk(repository)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> "SKILL.md".equals(String.valueOf(p.getFileName())))
                    .map(Path::getParent)
                    .map(repository::relativize)
                    .map(Path::toString)
                    .sorted()
                    .forEach(candidates::add);
        }
        for (String candidate : candidates) {
            Path directory = repository.resolve(candidate == null ? "" : candidate)
                    .normalize();
            if (directory.startsWith(repository)
                    && Files.isRegularFile(directory.resolve("SKILL.md"))) {
                return new MaterializedPayload(discovered, directory, null);
            }
        }
        throw new IOException("No SKILL.md for " + discovered.externalId());
    }

    private static void addMetadata(List<NormalizedItem> items, java.util.Set<String> seen,
            JsonNode plugin, String repoUrl, String path) {
        String clean = cleanPath(path);
        String name = plugin.path("name").asString("skill");
        String identity = clean.isBlank() ? slug(name) : clean;
        String externalId = "claude:" + identity;
        if (!seen.add(externalId)) {
            return;
        }
        String version = plugin.path("version").asString(null);
        String license = plugin.path("license").asString(null);
        items.add(new NormalizedItem(externalId, "SKILL", name,
                slug(identity.substring(identity.lastIndexOf('/') + 1)),
                clamp(plugin.path("description").asString(""), 480),
                version, clean, repoUrl, null, null, license));
    }

    private NormalizedItem skillFromDirectory(UpstreamSource source, String repoUrl,
            Map<String, byte[]> repoFiles, String dir) {
        String clean = cleanPath(dir);
        // Collection entries carry "./skills/xlsx" paths; the sweep yields "xlsx".
        // Both describe the same skill — normalize identity so they dedupe, while
        // files stay rooted at the real repository directory.
        String rootDir = clean;
        byte[] skillMd = repoFiles.get(rootDir + "/SKILL.md");
        if (skillMd == null && clean.startsWith("skills/")) {
            rootDir = clean.substring("skills/".length());
            skillMd = repoFiles.get(rootDir + "/SKILL.md");
        }
        if (skillMd == null) {
            return null;
        }
        // Identity/slug use the directory name; the files stay rooted at rootDir.
        String identity = rootDir.startsWith("skills/")
                ? rootDir.substring("skills/".length()) : rootDir;
        String name = frontmatterField(skillMd, "name");
        if (name == null || name.isBlank()) {
            name = identity.substring(identity.lastIndexOf('/') + 1);
        }
        String description = clamp(frontmatterField(skillMd, "description"), 480);
        Map<String, byte[]> files = new LinkedHashMap<>();
        String prefix = rootDir + "/";
        for (Map.Entry<String, byte[]> e : repoFiles.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                files.put(e.getKey().substring(prefix.length()), e.getValue());
            }
        }
        String slug = identity.isEmpty() ? slug(name) : slug(identity);
        return new NormalizedItem("claude:" + (identity.isEmpty() ? slug : identity), "SKILL",
                name, slug, description,
                frontmatterField(skillMd, "version"), identity, repoUrl, files, null,
                frontmatterField(skillMd, "license"));
    }

    private static String cleanPath(String path) {
        return path == null ? ""
                : path.replaceAll("^\\./", "").replaceAll("^/+", "")
                        .replaceAll("/+$", "");
    }

    static String frontmatterField(byte[] skillMd, String field) {
        if (skillMd == null) {
            return null;
        }
        String md = new String(skillMd, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?m)^" + Pattern.quote(field) + ":\\s*(.*)$").matcher(md);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    static String clamp(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max - 1) + "…" : value;
    }

    static String slug(String value) {
        String slug = value.toLowerCase().replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "skill" : slug.substring(0, Math.min(60, slug.length()));
    }
}
