package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        RepoFetcher.SyncScope scope = new RepoFetcher.SyncScope();
        String marketplaceUrl = source.marketplaceUrl();
        JsonNode document;
        String repoUrl = null;
        if (marketplaceUrl.matches("^https://github\\.com/[^/]+/[^/]+/?$")) {
            repoUrl = marketplaceUrl;
            Map<String, byte[]> repoFiles = fetcher.repoFiles(repoUrl, scope);
            byte[] manifest = repoFiles.get(".claude-plugin/marketplace.json");
            if (manifest == null) {
                throw new IOException("no .claude-plugin/marketplace.json in " + repoUrl);
            }
            document = new String(manifest, StandardCharsets.UTF_8).isEmpty() ? null
                    : tools.jackson.databind.json.JsonMapper.builder().build()
                            .readTree(manifest);
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
                // Official collection form: skills[] paths inside the marketplace repo.
                Map<String, byte[]> repoFiles = fetcher.repoFiles(repoUrl, scope);
                for (JsonNode skillPath : plugin.path("skills")) {
                    NormalizedItem item = skillFromDirectory(source, repoUrl, repoFiles,
                            skillPath.asText());
                    if (item != null && seen.add(item.externalId())) {
                        items.add(item);
                    }
                }
            } else {
                String entryRepo = sourceNode.path("url").asString(null);
                String subPath = sourceNode.path("path").asString("");
                if (entryRepo == null) {
                    continue;
                }
                Map<String, byte[]> repoFiles = fetcher.repoFiles(entryRepo, scope);
                String pluginName = plugin.path("name").asString();
                // Object sources may point at the whole repo, a named dir or a
                // subpath; probe the same candidates the legacy importer used.
                for (String candidate : List.of(subPath, slug(pluginName), "")) {
                    if (candidate == null) {
                        continue;
                    }
                    NormalizedItem item = skillFromDirectory(source, entryRepo, repoFiles,
                            candidate);
                    if (item != null && seen.add(item.externalId())) {
                        items.add(item);
                        break;
                    }
                }
            }
        }
        // Full-repository sweep (plan §4: the repo, not the manifest, is the catalog).
        if (repoUrl != null) {
            Map<String, byte[]> repoFiles = fetcher.repoFiles(repoUrl, scope);
            for (String path : repoFiles.keySet()) {
                if (path.startsWith("skills/") && path.endsWith("/SKILL.md")
                        && path.indexOf('/', 7) == path.lastIndexOf('/')) {
                    NormalizedItem item = skillFromDirectory(source, repoUrl, repoFiles,
                            path.substring(0, path.length() - "/SKILL.md".length()));
                    if (item != null && seen.add(item.externalId())) {
                        items.add(item);
                    }
                }
            }
        }
        return items;
    }

    private NormalizedItem skillFromDirectory(UpstreamSource source, String repoUrl,
            Map<String, byte[]> repoFiles, String dir) {
        String clean = dir.replaceAll("^\\./", "").replaceAll("^/+", "").replaceAll("/+$", "");
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
