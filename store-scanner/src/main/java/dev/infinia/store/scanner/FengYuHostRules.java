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
 * <p>Source of truth: FengYu {@code PluginPackageService.validate} /
 * {@code SemanticVersion} / {@code SemanticVersionRange} — keep in sync. The range
 * grammar grew npm-style shorthand (caret / tilde / wildcards) in the host's P3
 * batch; this mirror must accept everything {@code SemanticVersionRange.isValid}
 * accepts, or the store would reject packages the host installs fine.</p>
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

    /** Strict SemVer 2.0 (host {@code SemanticVersion.VERSION}), incl. leading-zero bans. */
    public static final Pattern SEMVER_PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");

    /**
     * Host {@code SemanticVersionRange.Partial}: M, M.m, or a full semver third
     * component. When the patch group is present the host re-parses the whole value
     * through the strict parser, so the pattern below embeds pre/build only there.
     */
    private static final Pattern PARTIAL = Pattern.compile(
            "^(0|[1-9]\\d*)(?:\\.(0|[1-9]\\d*))?"
                    + "(?:\\.(0|[1-9]\\d*)(?:-[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*"
                    + ")?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?)?$");

    public static final int PLUGIN_SCHEMA_VERSION = 2;
    public static final int SKILL_SCHEMA_VERSION = 1;

    /** Only the FengYu team may ship ids under this namespace. */
    public static final String OFFICIAL_NAMESPACE = "fan.summer.";

    /** Host backend worker artifact per runtime ({@code PluginPackageService.workerArtifact}). */
    public static String workerArtifact(String runtime, boolean windowsHost) {
        return switch (runtime) {
            case "python" -> "backend/worker.py";
            case "go" -> windowsHost ? "backend/worker.exe" : "backend/worker";
            default -> "backend/worker.jar";
        };
    }

    /**
     * The host's {@code SemanticVersionRange} understands comparator sets
     * ({@code >=4.0.0 <5.0.0}, {@code =4.0.0}) combined by {@code ||} alternatives,
     * <em>plus</em> the npm-style shorthand authors instinctively write: {@code ^1.2.3}
     * caret, {@code ~1.2.3} tilde, wildcards {@code *} / {@code 1.x} / {@code 1.2.x},
     * and bare numeric partials ({@code 1}, {@code 1.2}). This validity check mirrors
     * the accepted token shapes of {@code SemanticVersionRange.parseToken}; the store
     * never evaluates matches, so shape-checking is enough to stay in sync.
     */
    public static boolean hostCompatibleRange(String range) {
        if (range == null || range.isBlank()) {
            return false;
        }
        for (String alternative : range.split("\\|\\|", -1)) {
            String trimmed = alternative.trim();
            if (trimmed.isEmpty()) {
                return false;
            }
            for (String token : trimmed.split("\\s+")) {
                if (!validRangeToken(token)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Mirror of {@code SemanticVersionRange.parseToken}'s accepted token shapes. */
    private static boolean validRangeToken(String token) {
        // Bare wildcard matches every version.
        if (token.equals("*") || token.equals("x") || token.equals("X")) {
            return true;
        }
        // Caret / tilde over a partial anchor (M, M.m, or full semver).
        if (token.startsWith("^") || token.startsWith("~")) {
            return isValidPartial(token.substring(1));
        }
        // Wildcard partials: 1.x, 1.2.x, 1.* — and bare numeric partials 1, 1.2.
        if (token.endsWith(".x") || token.endsWith(".X") || token.endsWith(".*")) {
            return isValidPartial(token.substring(0, token.length() - 2));
        }
        if (token.matches("\\d+(\\.\\d+)?")) {
            return true;
        }
        // Full-semver comparators; the operator is optional (=).
        String value = token;
        for (String prefix : List.of(">=", "<=", ">", "<", "=")) {
            if (token.startsWith(prefix)) {
                value = token.substring(prefix.length());
                break;
            }
        }
        return !value.isEmpty() && SEMVER_PATTERN.matcher(value).matches();
    }

    /** Host {@code Partial.parse}: a full third component must pass the strict parser. */
    private static boolean isValidPartial(String value) {
        var matcher = PARTIAL.matcher(value);
        return matcher.matches()
                && (matcher.group(3) == null || SEMVER_PATTERN.matcher(value).matches());
    }

    public static boolean isPluginPermissionAllowed(String permission) {
        return PLUGIN_PERMISSIONS.contains(permission);
    }

    public static List<String> pluginBackendRuntimes() {
        return List.of("java", "python", "go");
    }
}
