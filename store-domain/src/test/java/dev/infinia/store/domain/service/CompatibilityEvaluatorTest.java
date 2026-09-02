package dev.infinia.store.domain.service;

import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.domain.model.Release;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CompatibilityEvaluatorTest {

    private Release.ArtifactInfo artifact(Platform p, Arch a) {
        return new Release.ArtifactInfo(UUID.randomUUID(), ArtifactKind.PACKAGE, p, a,
                "default", "pkg.zip", 100, "ab", null, null, "blobs/ab", "application/zip");
    }

    @Test
    void hostRangeIsEvaluated() {
        Release r = new Release();
        r.requiresHost = ">=4.0.0-beta.5 <5.0.0";
        assertTrue(CompatibilityEvaluator.hostCompatible(r, "4.0.0-beta.5"));
        assertTrue(CompatibilityEvaluator.hostCompatible(r, "4.2.1"));
        assertFalse(CompatibilityEvaluator.hostCompatible(r, "5.0.0"));
        assertFalse(CompatibilityEvaluator.hostCompatible(r, "4.0.0-beta.4"));
    }

    @Test
    void missingRequiresHostMeansCompatible() {
        Release r = new Release();
        r.requiresHost = null;
        assertTrue(CompatibilityEvaluator.hostCompatible(r, "0.0.1"));
    }

    @Test
    void picksExactPlatformArtifactFirst() {
        Release r = new Release();
        r.artifacts = List.of(
                artifact(Platform.UNIVERSAL, Arch.UNIVERSAL),
                artifact(Platform.MACOS, Arch.UNIVERSAL),
                artifact(Platform.MACOS, Arch.ARM64));

        var best = CompatibilityEvaluator.bestArtifact(r, Platform.MACOS, Arch.ARM64);
        assertTrue(best.isPresent());
        assertEquals(Arch.ARM64, best.get().arch());
        assertEquals(Platform.MACOS, best.get().platform());
    }

    @Test
    void fallsBackToUniversalArtifact() {
        Release r = new Release();
        r.artifacts = List.of(artifact(Platform.UNIVERSAL, Arch.UNIVERSAL));

        var best = CompatibilityEvaluator.bestArtifact(r, Platform.WINDOWS, Arch.X64);
        assertTrue(best.isPresent());
        assertEquals(Platform.UNIVERSAL, best.get().platform());
    }

    @Test
    void noArtifactWhenNothingMatches() {
        Release r = new Release();
        r.artifacts = List.of(artifact(Platform.MACOS, Arch.X64));
        assertTrue(CompatibilityEvaluator.bestArtifact(r, Platform.LINUX, Arch.ARM64).isEmpty());
    }
}
