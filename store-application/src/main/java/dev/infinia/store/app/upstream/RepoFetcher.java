package dev.infinia.store.app.upstream;

import dev.infinia.store.scanner.SourceFetchGuard;
import dev.infinia.store.scanner.TarGz;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
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

    /**
     * Lists GitHub repository paths through the Git Trees metadata API. This is
     * safe for catalog discovery because it does not download repository blobs.
     * Non-GitHub archive URLs have no metadata listing protocol and return empty.
     */
    public List<String> repoFilePaths(String repoUrl)
            throws IOException, InterruptedException {
        Matcher github = GITHUB_REPO.matcher(repoUrl);
        if (!github.matches()) {
            return List.of();
        }
        JsonNode tree = fetchJson("https://api.github.com/repos/" + github.group(1) + "/"
                + github.group(2) + "/git/trees/HEAD?recursive=1");
        List<String> paths = new java.util.ArrayList<>();
        for (JsonNode entry : tree.path("tree")) {
            if ("blob".equals(entry.path("type").asString())) {
                String path = entry.path("path").asString(null);
                if (path != null) {
                    paths.add(path);
                }
            }
        }
        return List.copyOf(paths);
    }

    /** Reads a small repository file without downloading the repository archive. */
    public JsonNode githubJsonFile(String repoUrl, String path)
            throws IOException, InterruptedException {
        Matcher github = GITHUB_REPO.matcher(repoUrl);
        if (!github.matches()) {
            throw new IOException("Not a GitHub repository URL: " + repoUrl);
        }
        JsonNode envelope = fetchJson("https://api.github.com/repos/" + github.group(1)
                + "/" + github.group(2) + "/contents/" + path);
        if (!"base64".equalsIgnoreCase(envelope.path("encoding").asString())
                || envelope.path("content").asString(null) == null) {
            throw new IOException("GitHub contents response has no Base64 file payload: "
                    + path);
        }
        final byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(envelope.path("content").asString());
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid Base64 in GitHub contents response: " + path, e);
        }
        if (decoded.length > MAX_JSON_BYTES) {
            throw new IOException("Decoded GitHub file exceeds budget: " + decoded.length
                    + " bytes");
        }
        return mapper.readTree(decoded);
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
     * Downloads and extracts a repository through bounded file streams. The
     * caller owns {@code targetDirectory} and deletes its request workspace.
     */
    public Path repoDirectory(String repoUrl, Path targetDirectory)
            throws IOException, InterruptedException {
        Path root = targetDirectory.toAbsolutePath().normalize();
        Files.createDirectories(root.getParent());
        Path archive = root.getParent().resolve("upstream-source.tar.gz");
        try {
            fetchToFile(tarballUrl(repoUrl), archive, MAX_TARBALL_BYTES);
            try (InputStream in = Files.newInputStream(archive)) {
                TarGz.extractToDirectory(in, root, MAX_TARBALL_BYTES,
                        256L * 1024 * 1024);
            }
            return root;
        } finally {
            Files.deleteIfExists(archive);
        }
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
        HttpResponse<byte[]> response = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 == 2) {
                break;
            }
            if (attempt == 2 || !isTransient(response.statusCode())) {
                throw new IOException("GET " + url + " → HTTP " + response.statusCode());
            }
            Thread.sleep(250L << attempt);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " → HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body != null && body.length > maxBytes) {
            throw new IOException("Response exceeds budget: " + body.length + " bytes");
        }
        return body == null ? new byte[0] : body;
    }

    private void fetchToFile(String url, Path target, long maxBytes)
            throws IOException, InterruptedException {
        SourceFetchGuard.validate(url);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Infinia-Store-Download")
                .GET().build();
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpResponse<InputStream> response = http.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                try (InputStream ignored = response.body()) {
                    // Closing releases the connection before a retry.
                }
                if (attempt == 2 || !isTransient(response.statusCode())) {
                    throw new IOException("GET " + url + " → HTTP "
                            + response.statusCode());
                }
                Thread.sleep(250L << attempt);
                continue;
            }
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            try (InputStream in = response.body();
                    OutputStream out = Files.newOutputStream(target,
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Response exceeds budget: " + total + " bytes");
                    }
                    out.write(buffer, 0, read);
                }
                return;
            } catch (IOException e) {
                Files.deleteIfExists(target);
                throw e;
            }
        }
        throw new IOException("GET " + url + " failed");
    }

    private static boolean isTransient(int status) {
        return status == 403 || status == 429 || status == 500 || status == 502
                || status == 503 || status == 504;
    }
}
