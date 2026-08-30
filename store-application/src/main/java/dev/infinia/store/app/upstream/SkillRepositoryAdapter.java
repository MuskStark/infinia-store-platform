package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.ClaudeMarketplaceAdapter;
import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        String url = source.marketplaceUrl();
        List<NormalizedItem> items = new ArrayList<>();
        // GitHub's tree endpoint provides paths without repository blobs. For a
        // generic archive URL there is no index protocol, so expose one root item
        // and locate its SKILL.md only if the user downloads it.
        java.util.TreeSet<String> dirs = new java.util.TreeSet<>();
        for (String path : fetcher.repoFilePaths(url)) {
            if (!path.endsWith("/SKILL.md") && !path.equals("SKILL.md")) {
                continue;
            }
            String dir = path.equals("SKILL.md") ? ""
                    : path.substring(0, path.length() - "/SKILL.md".length());
            if (dir.chars().filter(c -> c == '/').count() <= 2) {
                dirs.add(dir);
            }
        }
        if (dirs.isEmpty()) {
            dirs.add("");
        }
        for (String dir : dirs) {
            String name = dir.isEmpty() ? source.name()
                    : dir.substring(dir.lastIndexOf('/') + 1);
            String slug = dir.isEmpty()
                    ? ClaudeMarketplaceAdapter.slug(source.name())
                    : ClaudeMarketplaceAdapter.slug(dir.substring(dir.lastIndexOf('/') + 1));
            items.add(new NormalizedItem("repo:" + source.targetNamespace() + "/" + dir,
                    "SKILL", name, slug, "Upstream skill from " + source.name(),
                    null, dir, url, null, null, null));
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
        repoFiles.keySet().stream()
                .filter(p -> p.equals("SKILL.md") || p.endsWith("/SKILL.md"))
                .map(p -> p.equals("SKILL.md") ? ""
                        : p.substring(0, p.length() - "/SKILL.md".length()))
                .sorted().forEach(candidates::add);
        for (String dir : candidates) {
            if (dir == null) {
                continue;
            }
            String prefix = dir.isEmpty() ? "" : dir + "/";
            byte[] skillMd = repoFiles.get(prefix + "SKILL.md");
            if (skillMd == null) {
                continue;
            }
            Map<String, byte[]> files = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : repoFiles.entrySet()) {
                if (e.getKey().startsWith(prefix)) {
                    files.put(e.getKey().substring(prefix.length()), e.getValue());
                }
            }
            return new NormalizedItem(discovered.externalId(), discovered.kind(),
                    discovered.name(), discovered.slug(), discovered.description(),
                    discovered.version(), discovered.sourcePath(), discovered.sourceUrl(),
                    files, null, discovered.license());
        }
        throw new IOException("No SKILL.md for " + discovered.externalId());
    }

    @Override
    public MaterializedPayload materializeToDirectory(UpstreamSource source,
            NormalizedItem discovered, RepoFetcher fetcher, Path workspace)
            throws IOException, InterruptedException {
        Path repository = fetcher.repoDirectory(discovered.sourceUrl(),
                workspace.resolve("repository"));
        java.util.LinkedHashSet<Path> candidates = new java.util.LinkedHashSet<>();
        candidates.add(repository.resolve(discovered.sourcePath() == null
                ? "" : discovered.sourcePath()).normalize());
        try (var paths = Files.walk(repository)) {
            paths.filter(Files::isRegularFile)
                    .filter(p -> "SKILL.md".equals(String.valueOf(p.getFileName())))
                    .map(Path::getParent)
                    .sorted()
                    .forEach(candidates::add);
        }
        for (Path directory : candidates) {
            if (directory.startsWith(repository)
                    && Files.isRegularFile(directory.resolve("SKILL.md"))) {
                return new MaterializedPayload(discovered, directory, null);
            }
        }
        throw new IOException("No SKILL.md for " + discovered.externalId());
    }
}
