package dev.infinia.store.app.web;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.BeeLevelService;
import dev.infinia.store.contract.coordinate.InfiniaCoordinate;
import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.Channel;
import dev.infinia.store.contract.type.ListingType;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Release.ArtifactInfo;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.ListingRepository;
import dev.infinia.store.domain.port.ReleaseRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Debian update feed for the Infinia desktop client (audit 3.3).
 *
 * <p>The desktop shell configures electron-updater's <em>generic</em> provider at
 * {@code {FENGYU_UPDATE_API_BASE}/fengyu-updates/deb} with {@code channel: latest}
 * (see {@code desktop/electron/src/updater/update-feed.ts} in the FengYu repo), so
 * it fetches {@code GET /fengyu-updates/deb/latest-linux.yml} and resolves the
 * {@code files[].url} entries against that same directory — exactly the layout
 * electron-builder publishes. This controller serves the yml plus the deb
 * artifacts themselves, so an intranet store deployment fully replaces the GitHub
 * feed for the lite Debian package.
 *
 * <p>Only the newest PUBLISHED STABLE APP release that is fully rolled out is
 * announced: the generic feed is anonymous (no installId), so partial rollout
 * bucketing cannot apply — holding the feed back until rollout completes is the
 * only faithful mapping. Digests are derived from the stored blobs, never from
 * metadata: sha512 (the value electron-updater verifies) is the Base64 of the
 * SHA-512 over the blob bytes, cached per content-addressed blob key.
 */
@RestController
@RequestMapping("/fengyu-updates")
public class FengYuUpdateFeedController {

    /** electron-builder's deb media type (artifactName …-linux-<arch>.deb). */
    private static final String DEB_MEDIA_TYPE = "application/vnd.debian.binary-package";

    private final ListingRepository listings;
    private final ReleaseRepository releases;
    private final BlobStorage blobs;
    private final StoreProperties properties;
    private final BeeLevelService beeLevels;
    /** blobKey → Base64(SHA-512). Blobs are content-addressed and immutable. */
    private final Map<String, String> sha512Cache = new ConcurrentHashMap<>();

    public FengYuUpdateFeedController(ListingRepository listings, ReleaseRepository releases,
            BlobStorage blobs, StoreProperties properties, BeeLevelService beeLevels) {
        this.listings = listings;
        this.releases = releases;
        this.blobs = blobs;
        this.properties = properties;
        this.beeLevels = beeLevels;
    }

    /**
     * electron-updater {@code latest-linux.yml}: version, files[] with url +
     * base64 sha512 + size, plus the legacy top-level path/sha512 mirror — the
     * same document shape electron-builder emits next to its artifacts.
     */
    @GetMapping(value = "/deb/latest-linux.yml", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> latestLinuxYml() {
        return linuxYml(Arch.X64);
    }

    @GetMapping(value = "/deb/latest-linux-arm64.yml", produces = "text/plain; charset=utf-8")
    public ResponseEntity<String> latestLinuxArm64Yml() {
        return linuxYml(Arch.ARM64);
    }

    private ResponseEntity<String> linuxYml(Arch arch) {
        Release best = latestDebRelease(arch);
        if (best == null) {
            throw new DomainException(StoreErrorCode.NOT_FOUND,
                    "No published stable deb release is available on this channel");
        }
        List<ArtifactInfo> debs = debArtifacts(best).stream()
                .filter(a -> a.arch() == arch).toList();
        ArtifactInfo primary = primaryDeb(debs);
        String sha512 = sha512(primary.blobKey());
        StringBuilder yml = new StringBuilder();
        yml.append("version: ").append(best.version).append('\n');
        yml.append("files:\n");
        for (ArtifactInfo deb : debs) {
            yml.append("  - url: ").append(deb.filename()).append('\n');
            yml.append("    sha512: ").append(sha512(deb.blobKey())).append('\n');
            yml.append("    size: ").append(blobs.size(deb.blobKey())).append('\n');
        }
        yml.append("path: ").append(primary.filename()).append('\n');
        yml.append("sha512: ").append(sha512).append('\n');
        if (best.publishedAt != null) {
            yml.append("releaseDate: '").append(best.publishedAt).append("'\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(yml.toString());
    }

    /** Direct deb download — the URLs latest-linux.yml points at (anonymous). */
    @GetMapping("/deb/{filename}")
    public ResponseEntity<StreamingResponseBody> deb(@PathVariable String filename) {
        // Served from any published STABLE app release: a client holding a
        // recently fetched yml may still reference the previous deb during a
        // staged transition. Only filenames the store actually published match.
        for (Release release : stableAppReleases()) {
            for (ArtifactInfo deb : debArtifacts(release)) {
                if (!deb.filename().equals(filename)) {
                    continue;
                }
                // Opened inside writeTo: a never-executed async body leaks no
                // stream (S3-backed streams hold a pooled connection until closed).
                StreamingResponseBody body = out -> {
                    try (InputStream in = blobs.open(deb.blobKey())) {
                        in.transferTo(out);
                    }
                };
                return ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, DEB_MEDIA_TYPE)
                        .header(HttpHeaders.CONTENT_LENGTH,
                                String.valueOf(blobs.size(deb.blobKey())))
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                        .body(body);
            }
        }
        throw new DomainException(StoreErrorCode.NOT_FOUND,
                "No published deb artifact named " + filename);
    }

    // ---- release / artifact selection ----

    /** Newest published, fully rolled out STABLE release that ships a deb. */
    private Release latestDebRelease(Arch arch) {
        List<Release> candidates = stableAppReleases().stream()
                .filter(r -> r.rolloutPercent >= 100)
                .toList();
        Release best = null;
        for (Release release : candidates) {
            if (debArtifacts(release).stream().noneMatch(a -> a.arch() == arch)) {
                continue;
            }
            if (best == null
                    || CompatFengYuController.latestOfEqualVersions(release, best) == release) {
                best = release;
            }
        }
        return best;
    }

    private List<Release> stableAppReleases() {
        InfiniaCoordinate configured = InfiniaCoordinate.parse(properties.appCoordinate());
        Listing app = listings.findByCoordinate(configured).orElse(null);
        if (app == null || app.type != ListingType.APP || !app.isPubliclyVisible()
                // The deb feed is anonymous — a bee-level gated APP listing must
                // serve an empty feed, not a 403 mid-update-check (蜜蜂等级).
                || (app.minBeeLevel > 0 && app.minBeeLevel > beeLevels.viewerLevel())) {
            return List.of();
        }
        return releases.findVisibleByListingId(app.id).stream()
                .filter(r -> r.status == ReleaseStatus.PUBLISHED && r.channel == Channel.STABLE)
                .toList();
    }

    /**
     * The linux deb INSTALLER artifacts of a release, filename-sorted for a
     * stable feed document across multi-arch releases.
     */
    private static List<ArtifactInfo> debArtifacts(Release release) {
        return release.artifacts.stream()
                .filter(a -> a.kind() == ArtifactKind.INSTALLER
                        && a.platform() == Platform.LINUX
                        && "lite".equals(a.variant())
                        && a.filename() != null && a.filename().endsWith(".deb"))
                .sorted(Comparator.comparing(ArtifactInfo::filename))
                .toList();
    }

    /**
     * The primary deb ({@code path} / top-level sha512): the store contract
     * serves the lite Debian package — JRE-bundling builds never reach this
     * feed — so the lite variant wins, then the deterministic first entry.
     */
    private static ArtifactInfo primaryDeb(List<ArtifactInfo> debs) {
        return debs.stream()
                .filter(a -> "lite".equals(a.variant()))
                .findFirst()
                .orElse(debs.get(0));
    }

    /** Base64 of the SHA-512 over the stored blob, cached per immutable blob. */
    private String sha512(String blobKey) {
        return sha512Cache.computeIfAbsent(blobKey, key -> {
            try (InputStream in = blobs.open(key)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
                return Base64.getEncoder().encodeToString(digest.digest());
            } catch (Exception e) {
                throw new DomainException(StoreErrorCode.INTERNAL_ERROR,
                        "Cannot digest deb blob " + key + ": " + e.getMessage());
            }
        });
    }
}
