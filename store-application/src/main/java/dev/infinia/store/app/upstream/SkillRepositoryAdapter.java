package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.ClaudeMarketplaceAdapter;
import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SKILL_REPOSITORY adapter (plan §3.1, Codex / Agent Skills): any Git repo (or
 * plain URL) whose skill directories each carry a root {@code SKILL.md}. Every
 * directory becomes one SKILL listing; frontmatter follows the portable Agent
 * Skills conventions, Codex-only extras stay untouched inside the files.
 */
@Component
public class SkillRepositoryAdapter implements UpstreamAdapter {

    @Override
    public String type() {
        return SKILL_REPOSITORY;
    }

    @Override
    public List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException {
        RepoFetcher.SyncScope scope = new RepoFetcher.SyncScope();
        String url = source.marketplaceUrl();
        Map<String, byte[]> repoFiles = fetcher.repoFiles(url, scope);
        List<NormalizedItem> items = new ArrayList<>();
        // Directories at depth 1 or 2 containing SKILL.md (skills/<dir>, <dir>,
        // packages/<dir>); the shallowest wins so nested duplicates collapse.
        java.util.TreeSet<String> dirs = new java.util.TreeSet<>();
        for (String path : repoFiles.keySet()) {
            if (!path.endsWith("/SKILL.md") && !path.equals("SKILL.md")) {
                continue;
            }
            String dir = path.equals("SKILL.md") ? ""
                    : path.substring(0, path.length() - "/SKILL.md".length());
            if (dir.chars().filter(c -> c == '/').count() <= 1) {
                dirs.add(dir);
            }
        }
        for (String dir : dirs) {
            byte[] skillMd = repoFiles.get((dir.isEmpty() ? "" : dir + "/") + "SKILL.md");
            String name = ClaudeMarketplaceAdapter.frontmatterField(skillMd, "name");
            if (name == null || name.isBlank()) {
                name = dir.isEmpty() ? source.name() : dir.substring(dir.lastIndexOf('/') + 1);
            }
            Map<String, byte[]> files = new java.util.LinkedHashMap<>();
            String prefix = dir.isEmpty() ? "" : dir + "/";
            for (Map.Entry<String, byte[]> e : repoFiles.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    files.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
            String slug = dir.isEmpty()
                    ? ClaudeMarketplaceAdapter.slug(source.name())
                    : ClaudeMarketplaceAdapter.slug(dir.substring(dir.lastIndexOf('/') + 1));
            items.add(new NormalizedItem("repo:" + source.targetNamespace() + "/" + dir,
                    "SKILL", name, slug,
                    ClaudeMarketplaceAdapter.clamp(
                            ClaudeMarketplaceAdapter.frontmatterField(skillMd, "description"),
                            480),
                    ClaudeMarketplaceAdapter.frontmatterField(skillMd, "version"),
                    dir, url, files, null,
                    ClaudeMarketplaceAdapter.frontmatterField(skillMd, "license")));
        }
        return items;
    }
}
