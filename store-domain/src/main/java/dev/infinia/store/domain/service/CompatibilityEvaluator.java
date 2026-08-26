package dev.infinia.store.domain.service;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.semver.SemVerRange;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.Platform;
import dev.infinia.store.domain.model.Release;

import java.util.Locale;
import java.util.Optional;

/**
 * Compatibility policy (design §5.3, §9.4): a release is compatible when the host
 * version satisfies {@code requiresHost} and the release offers an artifact for the
 * client platform/architecture (UNIVERSAL matches everything).
 */
public final class CompatibilityEvaluator {

    private CompatibilityEvaluator() {}

    public static boolean hostCompatible(Release release, String hostVersion) {
        if (release.requiresHost == null || release.requiresHost.isBlank()) {
            return true;
        }
        try {
            return SemVerRange.parse(release.requiresHost).matches(SemVer.parse(hostVersion));
        } catch (IllegalArgumentException e) {
            // An unparseable requirement fails closed.
            return false;
        }
    }

    public static Optional<Release.ArtifactInfo> bestArtifact(Release release, Platform os, Arch arch) {
        Platform os_ = os == null ? Platform.UNIVERSAL : os;
        Arch arch_ = arch == null ? Arch.UNIVERSAL : arch;
        Release.ArtifactInfo universal = null;
        Release.ArtifactInfo platformMatch = null;
        Release.ArtifactInfo exact = null;
        for (Release.ArtifactInfo a : release.artifacts) {
            if (a.kind() != dev.infinia.store.contract.type.ArtifactKind.PACKAGE
                    && a.kind() != dev.infinia.store.contract.type.ArtifactKind.INSTALLER) {
                continue;
            }
            boolean archOk = a.arch() == Arch.UNIVERSAL || a.arch() == arch_;
            if (a.platform() == Platform.UNIVERSAL) {
                if (archOk && universal == null) {
                    universal = a;
                }
            } else if (a.platform() == os_) {
                if (!archOk) {
                    continue;
                }
                if (a.arch() == arch_ && exact == null) {
                    exact = a;
                } else if (platformMatch == null) {
                    platformMatch = a;
                }
            }
        }
        if (exact != null) {
            return Optional.of(exact);
        }
        if (platformMatch != null) {
            return Optional.of(platformMatch);
        }
        return Optional.ofNullable(universal);
    }

    public static Platform parsePlatform(String value) {
        if (value == null) {
            return Platform.UNIVERSAL;
        }
        return Platform.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static Arch parseArch(String value) {
        if (value == null) {
            return Arch.UNIVERSAL;
        }
        return Arch.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
