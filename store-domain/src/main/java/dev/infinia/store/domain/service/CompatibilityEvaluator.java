package dev.infinia.store.domain.service;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.semver.SemVerRange;
import dev.infinia.store.contract.type.Arch;
import dev.infinia.store.contract.type.ArtifactKind;
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
            if (a.kind() != ArtifactKind.PACKAGE && a.kind() != ArtifactKind.INSTALLER
                    && a.kind() != ArtifactKind.PORTABLE) {
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

    /** Select all APP binaries compatible with a requested mode and build variant. */
    public static java.util.List<Release.ArtifactInfo> appArtifacts(Release release, Platform os,
            Arch arch, ArtifactKind mode, String variant) {
        Platform requestedOs = os == null ? Platform.UNIVERSAL : os;
        Arch requestedArch = arch == null ? Arch.UNIVERSAL : arch;
        String requestedVariant = variant == null || variant.isBlank() ? null
                : variant.trim().toLowerCase(Locale.ROOT);
        return release.artifacts.stream()
                .filter(a -> a.kind() == ArtifactKind.INSTALLER
                        || a.kind() == ArtifactKind.PORTABLE)
                .filter(a -> mode == null || a.kind() == mode)
                .filter(a -> requestedOs == Platform.UNIVERSAL
                        || a.platform() == Platform.UNIVERSAL || a.platform() == requestedOs)
                .filter(a -> requestedArch == Arch.UNIVERSAL
                        || a.arch() == Arch.UNIVERSAL || a.arch() == requestedArch)
                .filter(a -> requestedVariant == null || requestedVariant.equals(a.variant()))
                .sorted(java.util.Comparator
                        .comparingInt((Release.ArtifactInfo a) ->
                                a.platform() == requestedOs ? 0 : 1)
                        .thenComparingInt(a -> a.arch() == requestedArch ? 0 : 1)
                        .thenComparing(Release.ArtifactInfo::variant)
                        .thenComparing(Release.ArtifactInfo::filename))
                .toList();
    }

    public static Platform parsePlatform(String value) {
        if (value == null) {
            return Platform.UNIVERSAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "win", "win32", "windows" -> Platform.WINDOWS;
            case "darwin", "mac", "macos", "osx" -> Platform.MACOS;
            case "linux" -> Platform.LINUX;
            case "universal", "any", "all" -> Platform.UNIVERSAL;
            default -> Platform.valueOf(value.trim().toUpperCase(Locale.ROOT));
        };
    }

    public static Arch parseArch(String value) {
        if (value == null) {
            return Arch.UNIVERSAL;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "x64", "x86_64", "amd64" -> Arch.X64;
            case "arm64", "aarch64" -> Arch.ARM64;
            case "universal", "any", "all" -> Arch.UNIVERSAL;
            default -> Arch.valueOf(value.trim().toUpperCase(Locale.ROOT));
        };
    }
}
