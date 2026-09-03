package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * SKILLHUB_REGISTRY adapter (plan §3.1): consumes the SkillHub Open API —
 * Tencent's open skill platform behind WorkBuddy (api.skillhub.cn). Discovery
 * pages the {@code /api/skills} catalog envelope (metadata-only); the payload
 * zip is fetched per download through {@code /api/v1/download}, which answers
 * 302 to a signed object-storage URL. Every redirect hop re-passes the SSRF
 * guard; the pinned upstream version keeps the payload consistent with the
 * synced catalog row.
 */
@Component
public class SkillHubAdapter implements UpstreamAdapter {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGES = 3;
    private static final long MAX_SKILL_ZIP_BYTES = 64L * 1024 * 1024;

    @Override
    public String type() {
        return SKILLHUB_REGISTRY;
    }

    /** AUTO detection: the SkillHub host or its catalog path. */
    public static boolean matches(String marketplaceUrl) {
        String lower = marketplaceUrl == null ? "" : marketplaceUrl.toLowerCase();
        return lower.contains("skillhub") || lower.contains("/api/skills");
    }

    @Override
    public List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException {
        URI listEndpoint = listEndpoint(source.marketplaceUrl());
        int pageSize = intQuery(source.marketplaceUrl(), "pageSize", DEFAULT_PAGE_SIZE);
        int pages = intQuery(source.marketplaceUrl(), "pages", DEFAULT_PAGES);
        String detailBase = apiBase(source.marketplaceUrl());

        List<NormalizedItem> items = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= pages; page++) {
            JsonNode envelope;
            try {
                envelope = fetcher.fetchJson(pageUrl(listEndpoint, page, pageSize));
            } catch (IOException e) {
                if (items.isEmpty()) {
                    throw e;
                }
                break; // earlier pages already collected; later pages are best-effort
            }
            JsonNode skills = envelope.path("data").path("skills");
            if (!skills.isArray() || skills.isEmpty()) {
                break;
            }
            for (JsonNode skill : skills) {
                String slug = skill.path("slug").asString(null);
                if (slug == null || slug.isBlank() || !seen.add(slug)) {
                    continue;
                }
                String name = firstNonBlank(skill.path("name").asString(null), slug);
                String description = firstNonBlank(
                        skill.path("description_zh").asString(null),
                        skill.path("description").asString(""));
                String homepage = firstNonBlank(skill.path("homepage").asString(null),
                        detailBase + "/api/v1/skills/" + slug);
                items.add(new NormalizedItem("skillhub:" + slug, "SKILL", name,
                        ClaudeMarketplaceAdapter.slug(slug),
                        ClaudeMarketplaceAdapter.clamp(description, 480),
                        skill.path("version").asString(null),
                        slug, homepage, null, null, null));
            }
            if (skills.size() < pageSize) {
                break;
            }
        }
        return items;
    }

    @Override
    public MaterializedPayload materializeToDirectory(UpstreamSource source,
            NormalizedItem discovered, RepoFetcher fetcher, Path workspace)
            throws IOException, InterruptedException {
        String slug = discovered.sourcePath();
        if (slug == null || slug.isBlank()) {
            throw new IOException("SkillHub entry has no slug: " + discovered.externalId());
        }
        StringBuilder url = new StringBuilder(apiBase(source.marketplaceUrl()))
                .append("/api/v1/download?slug=")
                .append(URLEncoder.encode(slug, StandardCharsets.UTF_8));
        if (discovered.version() != null
                && dev.infinia.store.contract.semver.SemVer.isValid(discovered.version())) {
            url.append("&version=")
                    .append(URLEncoder.encode(discovered.version(), StandardCharsets.UTF_8));
        }
        Path zip = workspace.resolve("skillhub-skill.zip");
        fetcher.fetchFileFollowingRedirects(url.toString(), zip, MAX_SKILL_ZIP_BYTES);
        Map<String, dev.infinia.store.scanner.SafeZip.ExtractedFile> files =
                dev.infinia.store.scanner.SafeZip.extract(
                        new ByteArrayInputStream(Files.readAllBytes(zip)),
                        dev.infinia.store.scanner.SafeZip.Limits.defaults());
        Files.deleteIfExists(zip);
        if (files.isEmpty()) {
            throw new IOException("SkillHub download for " + slug + " is empty");
        }
        Path skill = workspace.resolve("skill");
        for (var entry : new TreeMap<>(files).entrySet()) {
            Path file = skill.resolve(entry.getKey()).normalize();
            if (!file.startsWith(skill)) {
                throw new IOException("Skill path escapes workspace: " + entry.getKey());
            }
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue().content());
        }
        return new MaterializedPayload(discovered, skillRoot(skill, files.keySet()), null);
    }

    /** Root SKILL.md location, preferring the shallowest deterministic match. */
    private static Path skillRoot(Path skill, java.util.Set<String> paths)
            throws IOException {
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        for (String path : new java.util.TreeSet<>(paths)) {
            if (!"SKILL.md".equals(path) && !path.endsWith("/SKILL.md")) {
                continue;
            }
            int depth = path.split("/").length;
            if (depth < bestDepth) {
                best = path;
                bestDepth = depth;
            }
        }
        if (best == null) {
            throw new IOException("No SKILL.md in SkillHub package under " + skill);
        }
        return best.equals("SKILL.md") ? skill
                : skill.resolve(best.substring(0, best.length() - "/SKILL.md".length()));
    }

    // ---- URL handling: the admin registers either the API base URL or a
    // pre-filtered /api/skills URL; store-side knobs ride in the query string.

    private static URI listEndpoint(String marketplaceUrl) {
        String raw = marketplaceUrl.replaceAll("/+$", "");
        int queryAt = raw.indexOf('?');
        String pathPart = queryAt < 0 ? raw : raw.substring(0, queryAt);
        String query = queryAt < 0 ? "" : raw.substring(queryAt);
        String list = pathPart.endsWith("/api/skills") ? pathPart : pathPart + "/api/skills";
        return URI.create(list + query);
    }

    private static String apiBase(String marketplaceUrl) {
        URI uri = URI.create(marketplaceUrl.replaceAll("/+$", ""));
        return uri.getScheme() + "://" + uri.getRawAuthority();
    }

    /** Rebuilds the list query with the loop-driven page, preserving filters. */
    private static String pageUrl(URI endpoint, int page, int pageSize) {
        StringBuilder query = new StringBuilder("page=").append(page)
                .append("&pageSize=").append(pageSize);
        String existing = endpoint.getRawQuery();
        if (existing != null && !existing.isBlank()) {
            for (String pair : existing.split("&")) {
                String key = pair.split("=", 2)[0];
                if ("page".equals(key) || "pageSize".equals(key) || "pages".equals(key)) {
                    continue; // store-side knobs never reach the upstream
                }
                query.append('&').append(pair);
            }
        }
        return endpoint.getScheme() + "://" + endpoint.getRawAuthority()
                + endpoint.getRawPath() + "?" + query;
    }

    private static int intQuery(String marketplaceUrl, String key, int fallback) {
        String query = URI.create(marketplaceUrl).getRawQuery();
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            String[] keyValue = pair.split("=", 2);
            if (key.equals(keyValue[0]) && keyValue.length > 1) {
                try {
                    return Math.max(1, Integer.parseInt(keyValue[1]));
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
