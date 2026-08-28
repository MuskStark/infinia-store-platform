package dev.infinia.store.app.upstream;

import dev.infinia.store.scanner.SourceFetchGuard;
import dev.infinia.store.scanner.TarGz;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared bounded fetcher for upstream adapters (plan §3.2): SSRF-guarded,
 * size-capped, with one-download caching per repo and GitHub ref→commit
 * pinning so provenance records the exact revision.
 */
@Component
public class RepoFetcher {

    private static final long MAX_JSON_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TARBALL_BYTES = 256L * 1024 * 1024;
    private static final Pattern GITHUB_REPO =
            Pattern.compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public RepoFetcher(org.springframework.core.env.Environment env) {
        // Tests host fake upstreams on loopback (store.upstream.allow-internal=true
        // in the test profile); production leaves this off — the SSRF guard stands.
        if (Boolean.parseBoolean(env.getProperty("store.upstream.allow-internal", "false"))) {
            System.setProperty("store.upstream.allow-internal", "true");
        }
    }
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    /** One cache per sync run — the caller passes a fresh map. */
    public static class SyncScope {
        final Map<String, Map<String, byte[]>> repoFiles = new HashMap<>();
        final Map<String, String> commitShas = new HashMap<>();
    }

    public JsonNode fetchJson(String url) throws IOException, InterruptedException {
        SourceFetchGuard.validate(url);
        byte[] body = fetch(url, MAX_JSON_BYTES);
        return mapper.readTree(body);
    }

    public Map<String, byte[]> repoFiles(String repoUrl, SyncScope scope)
            throws IOException, InterruptedException {
        Map<String, byte[]> cached = scope.repoFiles.get(repoUrl);
        if (cached != null) {
            return cached;
        }
        Map<String, byte[]> files = TarGz.stripTopLevelDir(TarGz.extract(
                new ByteArrayInputStream(fetch(tarballUrl(repoUrl), MAX_TARBALL_BYTES)),
                MAX_TARBALL_BYTES));
        scope.repoFiles.put(repoUrl, files);
        return files;
    }

    /**
     * Resolves the immutable commit sha for a GitHub repo (optionally at a ref).
     * A 40-hex ref is already a commit; otherwise the commits API is consulted
     * once per repo per sync.
     */
    public String commitSha(String repoUrl, String ref, SyncScope scope)
            throws IOException, InterruptedException {
        Matcher github = GITHUB_REPO.matcher(repoUrl);
        if (!github.matches()) {
            return null;
        }
        String owner = github.group(1);
        String repo = github.group(2);
        String effectiveRef = ref == null || ref.isBlank() ? "HEAD" : ref;
        if (effectiveRef.matches("[0-9a-f]{40}")) {
            return effectiveRef;
        }
        String key = owner + "/" + repo + "@" + effectiveRef;
        return scope.commitShas.computeIfAbsent(key, k -> {
            try {
                JsonNode commit = fetchJson("https://api.github.com/repos/" + owner + "/"
                        + repo + "/commits/" + effectiveRef);
                return commit.path("sha").asString(null);
            } catch (Exception e) {
                return null; // provenance degrades to ref-only, never blocks sync
            }
        });
    }

    public static String tarballUrl(String repoUrl) {
        Matcher github = GITHUB_REPO.matcher(repoUrl);
        if (github.matches()) {
            return "https://codeload.github.com/" + github.group(1) + "/" + github.group(2)
                    + "/tar.gz/HEAD";
        }
        return repoUrl;
    }

    private byte[] fetch(String url, long maxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Infinia-Store-Sync")
                .GET().build();
        HttpResponse<byte[]> response =
                http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " → HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body != null && body.length > maxBytes) {
            throw new IOException("Response exceeds budget: " + body.length + " bytes");
        }
        return body == null ? new byte[0] : body;
    }
}
