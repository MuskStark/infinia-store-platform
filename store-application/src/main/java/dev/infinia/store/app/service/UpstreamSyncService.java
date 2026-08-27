package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.PublisherDtos;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Namespace;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Review;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.model.UpstreamSource;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.domain.port.ReleaseRepository;
import dev.infinia.store.domain.service.UuidV7;
import dev.infinia.store.scanner.Ed25519Signer;
import dev.infinia.store.scanner.TarGz;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Upstream marketplace aggregation (design §2.1): the store mirrors external
 * skill marketplaces — e.g. Anthropic's official Claude skills — into its own
 * catalog, so hosts configure ONLY the store instead of each upstream
 * themselves. Aggregated skills flow through the regular pipeline (scan →
 * review → platform signature → publish) exactly like first-party content;
 * trusted upstreams are auto-approved and the decision is audited.
 *
 * Supported upstream document: Claude marketplace format
 * {@code {"plugins":[{"name","description","source":{"source":"url","url",...}}]}}.
 * Skill content is fetched as a tarball: GitHub repository URLs are converted
 * to codeload archives, any other http(s) URL is fetched directly.
 */
@Service
public class UpstreamSyncService {

    private static final Logger log = LoggerFactory.getLogger(UpstreamSyncService.class);
    private static final Pattern SLUG = Pattern.compile("[^a-z0-9-]+");
    private static final long MAX_MARKETPLACE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_TARBALL_BYTES = 256L * 1024 * 1024;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(60);

    private final PublishingRepositories.UpstreamSourceRepository upstreams;
    private final PublisherService publisher;
    private final ReviewService reviews;
    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.NamespaceRepository namespaces;
    private final AuditService audit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    public UpstreamSyncService(PublishingRepositories.UpstreamSourceRepository upstreams,
            PublisherService publisher, ReviewService reviews, ListingRepository listings,
            ReleaseRepository releases, IdentityRepositories.UserRepository users,
            IdentityRepositories.NamespaceRepository namespaces, AuditService audit) {
        this.upstreams = upstreams;
        this.publisher = publisher;
        this.reviews = reviews;
        this.listings = listings;
        this.releases = releases;
        this.users = users;
        this.namespaces = namespaces;
        this.audit = audit;
    }

    public record SyncResult(String upstream, int imported, int skipped, int failed,
            List<String> errors) {}

    // Deliberately not @Transactional: the review wait polls for progress committed
    // by the async scan worker, which a long outer transaction would hide. Each
    // publisher/review call manages its own transaction.
    public SyncResult sync(UUID upstreamId) {
        UpstreamSource source = upstreams.findById(upstreamId)
                .orElseThrow(() -> new DomainException(dev.infinia.store.contract.error
                        .StoreErrorCode.NOT_FOUND, "Upstream source not found"));
        List<String> errors = new ArrayList<>();
        int imported = 0;
        int skipped = 0;
        try {
            StoreUser bot = requireCiAccount();
            StoreUser reviewer = requireReviewerAccount();
            ensureNamespace(source.targetNamespace(), bot);

            Map<String, Map<String, byte[]>> repoCache = new java.util.HashMap<>();
            MarketplaceDocument document = fetchDocument(source.marketplaceUrl(), repoCache);
            for (JsonNode plugin : document.json().path("plugins")) {
                if (!plugin.isObject() || plugin.path("name").asString(null) == null) {
                    continue;
                }
                try {
                    int[] counts = syncEntry(source, bot, reviewer, plugin,
                            document.repoUrl(), repoCache);
                    imported += counts[0];
                    skipped += counts[1];
                } catch (Exception e) {
                    errors.add(plugin.path("name").asString("?") + ": " + e.getMessage());
                    log.warn("Upstream entry failed: {}", e.toString());
                }
            }
            upstreams.save(new UpstreamSource(source.id(), source.name(),
                    source.marketplaceUrl(), source.targetNamespace(), source.enabled(),
                    Instant.now(), errors.isEmpty() ? Boolean.TRUE : Boolean.FALSE,
                    errors.isEmpty() ? null : String.join("; ", errors)));
            audit.record("SERVICE", "upstream-sync", "upstream.sync", "UPSTREAM",
                    source.id().toString(), null,
                    "imported=" + imported + ",skipped=" + skipped + ",failed=" + errors.size(),
                    null);
        } catch (Exception e) {
            upstreams.save(new UpstreamSource(source.id(), source.name(),
                    source.marketplaceUrl(), source.targetNamespace(), source.enabled(),
                    Instant.now(), Boolean.FALSE, e.getMessage()));
            throw e instanceof RuntimeException runtimeException ? runtimeException
                    : new IllegalStateException(e);
        }
        return new SyncResult(source.name(), imported, skipped, errors.size(), errors);
    }

    private enum EntryOutcome {
        IMPORTED, SKIPPED
    }

    /**
     * One marketplace entry → {imported, skipped}. Two upstream dialects exist:
     * the object form (source: {"source":"url","url":repo[,"path":dir]}) and the
     * official Anthropic form (source: "./" + a skills[] array of directories in
     * the marketplace repository itself — one store listing per skill).
     */
    private int[] syncEntry(UpstreamSource source, StoreUser bot, StoreUser reviewer,
            JsonNode plugin, String marketplaceRepoUrl,
            Map<String, Map<String, byte[]>> repoCache)
            throws IOException, InterruptedException {
        JsonNode sourceNode = plugin.path("source");
        if (sourceNode.isTextual()) {
            return syncOfficialCollection(source, bot, reviewer, plugin,
                    marketplaceRepoUrl, repoCache);
        }
        String name = plugin.path("name").asString();
        String slug = slugify(name);
        String description = plugin.path("description").asString("");
        String repoUrl = sourceNode.path("url").asString(null);
        String subPath = sourceNode.path("path").asString("");
        if (repoUrl == null) {
            throw new IllegalArgumentException("entry has no source.url");
        }

        Map<String, byte[]> repoFiles = repoFiles(repoUrl, repoCache);
        Map<String, byte[]> skillFiles = resolveSkillDirectory(repoFiles, subPath, slug);
        if (skillFiles == null) {
            throw new IllegalArgumentException("no SKILL.md found in upstream package");
        }

        int imported = publishSkill(source, bot, reviewer, name, slug, description,
                repoUrl, skillFiles) ? 1 : 0;
        return new int[] {imported, imported == 0 ? 1 : 0};
    }

    /** Official Anthropic marketplace: source "./" with a skills[] of directories. */
    private int[] syncOfficialCollection(UpstreamSource source, StoreUser bot,
            StoreUser reviewer, JsonNode plugin, String marketplaceRepoUrl,
            Map<String, Map<String, byte[]>> repoCache)
            throws IOException, InterruptedException {
        if (marketplaceRepoUrl == null) {
            throw new IllegalArgumentException(
                    "relative source requires a GitHub marketplace repository URL");
        }
        Map<String, byte[]> repoFiles = repoFiles(marketplaceRepoUrl, repoCache);
        int imported = 0;
        int skipped = 0;
        for (JsonNode skillPath : plugin.path("skills")) {
            String dir = stripSlashes(skillPath.asText().replaceFirst("^[.]/", ""));
            byte[] skillMd = repoFiles.get(dir + "/SKILL.md");
            if (skillMd == null) {
                throw new IllegalArgumentException("no SKILL.md at " + dir);
            }
            String name = frontmatterField(skillMd, "name");
            if (name == null || name.isBlank()) {
                name = dir.substring(dir.lastIndexOf('/') + 1);
            }
            String description = frontmatterField(skillMd, "description");
            if (description != null && description.length() > 480) {
                description = description.substring(0, 477) + "…";
            }
            Map<String, byte[]> skillFiles = new LinkedHashMap<>();
            for (Map.Entry<String, byte[]> e : repoFiles.entrySet()) {
                if (e.getKey().startsWith(dir + "/")) {
                    skillFiles.put(e.getKey().substring(dir.length() + 1), e.getValue());
                }
            }
            if (publishSkill(source, bot, reviewer, name, slugify(name),
                    description == null ? "" : description, marketplaceRepoUrl, skillFiles)) {
                imported++;
            } else {
                skipped++;
            }
        }
        return new int[] {imported, skipped};
    }

    /** Cached repo tarball extraction (one download serves every entry). */
    private Map<String, byte[]> repoFiles(String repoUrl,
            Map<String, Map<String, byte[]>> repoCache) throws IOException,
            InterruptedException {
        Map<String, byte[]> cached = repoCache.get(repoUrl);
        if (cached != null) {
            return cached;
        }
        Map<String, byte[]> files = TarGz.stripTopLevelDir(
                TarGz.extract(fetchStream(tarballUrl(repoUrl)), MAX_TARBALL_BYTES));
        repoCache.put(repoUrl, files);
        return files;
    }

    /**
     * Runs one skill through the full publish pipeline; true when a new release
     * was imported, false when the latest published release already carries the
     * exact content (idempotent re-sync).
     */
    private boolean publishSkill(UpstreamSource source, StoreUser bot, StoreUser reviewer,
            String name, String slug, String description, String repoUrl,
            Map<String, byte[]> skillFiles) throws IOException, InterruptedException {
        byte[] fys = buildSkillPackage(source.targetNamespace(), slug, name, description,
                skillFiles);
        String sha256 = Ed25519Signer.sha256Hex(fys);

        InfiniaCoordinate coordinate = InfiniaCoordinate.of(ListingType.SKILL,
                source.targetNamespace(), slug);
        Listing listing = listings.findByCoordinate(coordinate).orElse(null);
        if (listing == null) {
            listing = publisher.createListing(bot.id, new PublisherDtos.CreateListingRequest(
                    source.targetNamespace(), slug, "SKILL", "Aggregated",
                    List.of("upstream", "claude"), "stable", name,
                    description.isBlank() ? "Aggregated from " + source.name() : description,
                    null, "en"));
        }

        // Idempotency: unchanged content under the newest published release.
        Release latest = releases.findLatestVisible(listing.id, Channel.STABLE).orElse(null);
        if (latest != null && latest.artifacts.stream()
                .anyMatch(a -> sha256.equals(a.sha256()))) {
            return false;
        }

        String baseVersion = frontmatterVersion(skillFiles.get("SKILL.md"));
        Release release = null;
        DomainException lastConflict = null;
        for (int patch = 0; patch < 50; patch++) {
            String version = bump(baseVersion, patch);
            try {
                release = publisher.createDraftRelease(bot.id, listing,
                        new PublisherDtos.CreateReleaseRequest(version, "stable", null, null,
                                repoUrl, "Aggregated from upstream " + source.name(), null,
                                null, 100));
                break;
            } catch (DomainException e) {
                if ("duplicate_version".equals(e.code.code)) {
                    lastConflict = e;
                    continue;
                }
                throw e;
            }
        }
        if (release == null) {
            throw lastConflict != null ? lastConflict
                    : new DomainException(dev.infinia.store.contract.error.StoreErrorCode
                            .VALIDATION_FAILED, "could not allocate a version");
        }

        var session = publisher.createUploadSession(bot.id, release,
                slug + "-" + release.version + ".fys", ArtifactKind.PACKAGE, Platform.UNIVERSAL,
                Arch.UNIVERSAL, fys.length);
        publisher.completeUpload(session.id, new ByteArrayInputStream(fys));
        // completeUpload advanced the persisted release to UPLOADING; reload instead
        // of submitting the stale in-memory draft.
        Release submitted = releases.findById(release.id).orElseThrow();
        Review review = publisher.submit(bot.id, submitted);
        awaitReview(release.id);

        // Trusted-upstream auto-approve: the package still passed the full scan.
        reviews.decide(reviewer.id, review.id,
                new dev.infinia.store.contract.api.ReviewDtos.ReviewDecisionRequest("APPROVE",
                        "Auto-approved: aggregated from trusted upstream " + source.name()));
        return true;
    }

    /** Polls until the async scan moves the release into review. */
    private void awaitReview(UUID releaseId) throws InterruptedException {
        for (int i = 0; i < 150; i++) {
            Release current = releases.findById(releaseId).orElse(null);
            if (current != null && current.status == ReleaseStatus.IN_REVIEW) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("scan did not reach IN_REVIEW in time");
    }

    // ---- upstream package resolution ----

    /** GitHub repo URLs become codeload tarballs; anything else is fetched as-is. */
    static String tarballUrl(String repoUrl) {
        java.util.regex.Matcher github = Pattern
                .compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$")
                .matcher(repoUrl);
        if (github.matches()) {
            return "https://codeload.github.com/" + github.group(1) + "/" + github.group(2)
                    + "/tar.gz/HEAD";
        }
        return repoUrl;
    }

    /**
     * Locates the skill directory: explicit path first, then the repo root,
     * then a directory named like the entry. Returns files re-rooted so that
     * SKILL.md sits at the package root (host skill contract).
     */
    private static Map<String, byte[]> resolveSkillDirectory(Map<String, byte[]> repoFiles,
            String explicitPath, String slug) {
        List<String> candidates = new ArrayList<>();
        if (explicitPath != null && !explicitPath.isBlank()) {
            candidates.add(stripSlashes(explicitPath));
        }
        candidates.add("");
        candidates.add(slug);
        for (String candidate : candidates) {
            String prefix = candidate.isEmpty() ? "" : candidate + "/";
            if (repoFiles.containsKey(prefix + "SKILL.md")) {
                Map<String, byte[]> rooted = new LinkedHashMap<>();
                for (Map.Entry<String, byte[]> e : repoFiles.entrySet()) {
                    if (e.getKey().startsWith(prefix)) {
                        rooted.put(e.getKey().substring(prefix.length()), e.getValue());
                    }
                }
                return rooted;
            }
        }
        return null;
    }

    private byte[] buildSkillPackage(String namespace, String slug, String name,
            String description, Map<String, byte[]> skillFiles) throws IOException {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", namespace + "." + slug);
        manifest.put("name", name);
        manifest.put("description", description);
        manifest.put("version", frontmatterVersion(skillFiles.get("SKILL.md")));
        manifest.put("author", namespace);
        manifest.put("official", false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(manifest));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> e : skillFiles.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Single-line frontmatter field (name/description), quotes stripped. */
    private static String frontmatterField(byte[] skillMd, String field) {
        if (skillMd == null) {
            return null;
        }
        String md = new String(skillMd, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = Pattern.compile(
                "(?m)^" + Pattern.quote(field) + ":\s*(.*)$").matcher(md);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String frontmatterVersion(byte[] skillMd) {
        if (skillMd == null) {
            return "0.1.0";
        }
        String md = new String(skillMd, StandardCharsets.UTF_8);
        java.util.regex.Matcher version = Pattern.compile("(?m)^version:\\s*([0-9][^\\s}]*)")
                .matcher(md);
        if (version.find() && dev.infinia.store.contract.semver.SemVer
                .isValid(version.group(1))) {
            return version.group(1);
        }
        return "0.1.0";
    }

    /** patch+N with a SemVer-safe base ("1.2.3" → "1.2.4"; "0.1.0" stays parseable). */
    private static String bump(String base, int patch) {
        if (patch == 0) {
            return base;
        }
        String[] parts = base.split("[-+]");
        String[] numbers = parts[0].split("\\.");
        long minor = numbers.length > 1 ? Long.parseLong(numbers[1]) : 1;
        return numbers[0] + "." + (minor + patch) + ".0";
    }

    private static String slugify(String name) {
        String slug = name.toLowerCase().replaceAll(SLUG.pattern(), "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "skill";
        }
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }

    private static String stripSlashes(String path) {
        return path.replaceAll("^/+", "").replaceAll("/+$", "");
    }

    // ---- accounts / namespaces ----

    private StoreUser requireCiAccount() {
        return users.findByEmailNormalized("ci@infinia.local")
                .orElseThrow(() -> new IllegalStateException(
                        "CI publisher account missing (seed required)"));
    }

    private StoreUser requireReviewerAccount() {
        return users.findByEmailNormalized("reviewer@infinia.local")
                .orElseThrow(() -> new IllegalStateException(
                        "Reviewer account missing (seed required)"));
    }

    private void ensureNamespace(String name, StoreUser owner) {
        if (namespaces.findByName(name).isEmpty()) {
            namespaces.save(new Namespace(UuidV7.generate(), name, owner.id, null, false,
                    Instant.now()));
        }
    }

    // ---- bounded http ----

    /** Marketplace document plus, when loaded from a repo tarball, its repo URL. */
    private record MarketplaceDocument(JsonNode json, String repoUrl) {}

    /**
     * Loads the upstream marketplace. A GitHub repository URL is resolved through
     * the codeload tarball (raw.githubusercontent can be unreachable while
     * codeload works); any other URL is fetched as plain JSON.
     */
    private MarketplaceDocument fetchDocument(String url,
            Map<String, Map<String, byte[]>> repoCache) throws IOException,
            InterruptedException {
        java.util.regex.Matcher github = Pattern
                .compile("^https://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$")
                .matcher(url);
        if (github.matches()) {
            Map<String, byte[]> repoFiles = repoFiles(url, repoCache);
            byte[] marketplace = repoFiles.get(".claude-plugin/marketplace.json");
            if (marketplace == null) {
                throw new IOException("no .claude-plugin/marketplace.json in " + url);
            }
            return new MarketplaceDocument(mapper.readTree(marketplace), url);
        }
        return new MarketplaceDocument(fetchJson(url, MAX_MARKETPLACE_BYTES), null);
    }

    private JsonNode fetchJson(String url, long maxBytes) throws IOException,
            InterruptedException {
        byte[] body = fetch(url, maxBytes);
        return mapper.readTree(body);
    }

    private ByteArrayInputStream fetchStream(String url) throws IOException,
            InterruptedException {
        return new ByteArrayInputStream(fetch(url, MAX_TARBALL_BYTES));
    }

    private byte[] fetch(String url, long maxBytes) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(HTTP_TIMEOUT)
                .header("User-Agent", "Infinia-Store-Sync").GET().build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GET " + url + " → HTTP " + response.statusCode());
        }
        byte[] body = response.body();
        if (body.length > maxBytes) {
            throw new IOException("response exceeds budget: " + body.length + " bytes");
        }
        return body;
    }
}
