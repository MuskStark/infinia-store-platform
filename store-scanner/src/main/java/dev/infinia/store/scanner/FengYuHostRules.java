package dev.infinia.store.scanner;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Install-time rules of the FengYu host (PluginPackageService / SkillPackageService),
 * mirrored here so the store only publishes packages the host will actually accept.
 * A store approval must never be rejected at install time (design §2.1: the host is
 * the execution plane and keeps the authoritative manifest validation).
 *
 * Source of truth: FengYu PluginManifest / SkillManifest validators — keep in sync.
 */
public final class FengYuHostRules {

    private FengYuHostRules() {}

    /** Host plugin permission allowlist (verbatim). */
    public static final Set<String> PLUGIN_PERMISSIONS = Set.of(
            "files.read", "files.write", "network", "network.email",
            "clipboard.read", "clipboard.write", "notifications", "database");

    /** Plugin and skill manifest id: lowercase reverse-domain segments. */
    public static final Pattern ID_PATTERN =
            Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)+");

    /** Skill manifest version: MAJOR.MINOR.PATCH with optional pre-release/build. */
    public static final Pattern SKILL_VERSION_PATTERN =
            Pattern.compile("\\d+\\.\\d+\\.\\d+(?:[-+].+)?");

    public static final int PLUGIN_SCHEMA_VERSION = 2;
    public static final int SKILL_SCHEMA_VERSION = 1;

    /** Only the FengYu team may ship ids under this namespace. */
    public static final String OFFICIAL_NAMESPACE = "fan.summer.";

    /**
     * The host's SemanticVersionRange understands only {@code >= <= > < =} over
     * plain versions with {@code ||} alternatives — no npm-style {@code ^ ~ x *}
     * shorthands and no hyphen ranges. The store's own solver is more permissive,
     * so anything intended for the host must be checked against this subset.
     */
    public static boolean hostCompatibleRange(String range) {
        if (range == null || range.isBlank()) {
            return false;
        }
        for (String alternative : range.split("\\|\\|")) {
            String trimmed = alternative.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            for (String token : trimmed.split("\\s+")) {
                if (!token.matches("(>=|<=|>|<|=)?\\d+\\.\\d+\\.\\d+"
                        + "(-[0-9A-Za-z.-]+)?(\\+[0-9A-Za-z.-]+)?")) {
                    return false;
                }
            }
        }
        return true;
    }

    public static boolean isPluginPermissionAllowed(String permission) {
        return PLUGIN_PERMISSIONS.contains(permission);
    }

    public static List<String> pluginBackendRuntimes() {
        return List.of("java", "python", "go");
    }
}
