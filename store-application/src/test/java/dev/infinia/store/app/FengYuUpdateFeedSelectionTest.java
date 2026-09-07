package dev.infinia.store.app;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.BeeLevelService;
import dev.infinia.store.app.web.FengYuUpdateFeedController;
import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.*;
import dev.infinia.store.domain.model.Listing;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.port.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FengYuUpdateFeedSelectionTest {
    @Test
    void feedsSelectOnlyMatchingArchitectureAndLiteVariant() {
        var listings = mock(ListingRepository.class);
        var releases = mock(ReleaseRepository.class);
        var blobs = mock(BlobStorage.class);
        var properties = mock(StoreProperties.class);
        when(properties.appCoordinate()).thenReturn("infinia://app/official/fengyu-host");
        var listing = new Listing();
        listing.id = UUID.randomUUID();
        listing.type = ListingType.APP;
        listing.status = "ACTIVE";
        listing.visibility = ListingVisibility.PUBLIC;
        when(listings.findByCoordinate(any())).thenReturn(Optional.of(listing));
        var release = new Release();
        release.version = SemVer.parse("4.1.0");
        release.status = ReleaseStatus.PUBLISHED;
        for (var arch : List.of(Arch.X64, Arch.ARM64)) {
            for (var variant : List.of("jre", "uos", "lite")) {
                String filename = variant + "-" + arch + ".deb";
                release.artifacts.add(new Release.ArtifactInfo(UUID.randomUUID(), ArtifactKind.INSTALLER,
                        Platform.LINUX, arch, variant, filename, 1, "hash", null, null, filename, null));
                when(blobs.open(filename)).thenAnswer(i -> new ByteArrayInputStream(new byte[]{42}));
                when(blobs.size(filename)).thenReturn(1L);
            }
        }
        when(releases.findVisibleByListingId(listing.id)).thenReturn(List.of(release));
        var controller = new FengYuUpdateFeedController(listings, releases, blobs, properties,
                mock(BeeLevelService.class));
        String x64 = controller.latestLinuxYml().getBody();
        String arm64 = controller.latestLinuxArm64Yml().getBody();
        assertTrue(x64.contains("lite-X64.deb"));
        assertFalse(x64.contains("ARM64"));
        assertTrue(arm64.contains("lite-ARM64.deb"));
        assertFalse(arm64.contains("X64"));
        for (String body : List.of(x64, arm64)) {
            assertFalse(body.contains("jre-"));
            assertFalse(body.contains("uos-"));
        }
    }
}
