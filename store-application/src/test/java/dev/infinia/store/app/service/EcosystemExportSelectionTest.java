package dev.infinia.store.app.service;

import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.domain.model.Release.ArtifactInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Deterministic artifact selection for the ecosystem export (audit 3.4): the
 * historic {@code artifacts.get(0)} fallback could pick a CHECKSUMS/SBOM row or
 * an arbitrary entry of a multi-artifact release — the export must degrade to
 * "skip" (null) when there is no PACKAGE, and pick deterministically when there
 * are several.
 */
class EcosystemExportSelectionTest {

    private static ArtifactInfo artifact(ArtifactKind kind, Platform platform, String filename) {
        return new ArtifactInfo(UUID.randomUUID(), kind, platform, Arch.UNIVERSAL, "default",
                filename, 1L, "cafe", null, null, "blobs/" + filename, null);
    }

    @Test
    void universalPackageWinsOverPlatformPackageAndNonPackageKinds() {
        Release release = new Release();
        release.artifacts = new java.util.ArrayList<>(List.of(
                artifact(ArtifactKind.CHECKSUMS, Platform.UNIVERSAL, "checksums.txt"),
                artifact(ArtifactKind.PACKAGE, Platform.WINDOWS, "b-win.fyp"),
                artifact(ArtifactKind.PACKAGE, Platform.UNIVERSAL, "z-universal.fys"),
                artifact(ArtifactKind.PACKAGE, Platform.MACOS, "a-mac.fyp")));
        assertEquals("z-universal.fys",
                EcosystemExportService.packageArtifact(release).filename());
    }

    @Test
    void multiArchReleasesResolveToTheSameArtifactRegardlessOfListOrder() {
        Release first = new Release();
        first.artifacts = new java.util.ArrayList<>(List.of(
                artifact(ArtifactKind.PACKAGE, Platform.LINUX, "linux-x64.fys"),
                artifact(ArtifactKind.PACKAGE, Platform.WINDOWS, "win-x64.fys")));
        Release rotated = new Release();
        rotated.artifacts = new java.util.ArrayList<>(List.of(
                artifact(ArtifactKind.PACKAGE, Platform.WINDOWS, "win-x64.fys"),
                artifact(ArtifactKind.PACKAGE, Platform.LINUX, "linux-x64.fys")));
        assertEquals(
                EcosystemExportService.packageArtifact(first).filename(),
                EcosystemExportService.packageArtifact(rotated).filename(),
                "without a UNIVERSAL candidate, stable filename order decides");
        assertEquals("linux-x64.fys",
                EcosystemExportService.packageArtifact(first).filename());
    }

    @Test
    void noPackageKindDegradesToSkipInsteadOfBogusFallback() {
        Release checksumsOnly = new Release();
        checksumsOnly.artifacts = new java.util.ArrayList<>(List.of(
                artifact(ArtifactKind.CHECKSUMS, Platform.UNIVERSAL, "checksums.txt"),
                artifact(ArtifactKind.SBOM, Platform.UNIVERSAL, "sbom.json")));
        assertNull(EcosystemExportService.packageArtifact(checksumsOnly),
                "a CHECKSUMS/SBOM-only release must not surface an install URL");

        Release empty = new Release();
        empty.artifacts = new java.util.ArrayList<>();
        assertNull(EcosystemExportService.packageArtifact(empty),
                "an artifact-less release must not throw");
    }
}
