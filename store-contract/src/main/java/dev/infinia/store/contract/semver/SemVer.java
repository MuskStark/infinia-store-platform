package dev.infinia.store.contract.semver;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Immutable semantic version per semver.org 2.0.0 with full precedence rules
 * (prerelease sorts before release, build metadata is ignored for precedence).
 */
public final class SemVer implements Comparable<SemVer> {

    private static final Pattern PATTERN = Pattern.compile(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
                    + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
                    + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    public static final SemVer ZERO = new SemVer(0, 0, 0, null, null);

    public final int major;
    public final int minor;
    public final int patch;
    /** Dot-separated prerelease identifiers, or null when absent. */
    public final String pre;
    /** Build metadata, or null when absent. Ignored for precedence. */
    public final String build;

    @Override
    public int compareTo(SemVer other) {
        int c = Integer.compare(major, other.major);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(minor, other.minor);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(patch, other.patch);
        if (c != 0) {
            return c;
        }
        if (pre == null && other.pre == null) {
            return 0;
        }
        if (pre == null) {
            return 1; // a release outranks any of its prereleases
        }
        if (other.pre == null) {
            return -1;
        }
        return comparePre(pre, other.pre);
    }

    public SemVer(int major, int minor, int patch, String pre, String build) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.pre = pre == null || pre.isEmpty() ? null : pre;
        this.build = build == null || build.isEmpty() ? null : build;
    }

    public static SemVer parse(String input) {
        Objects.requireNonNull(input, "version must not be null");
        Matcher m = PATTERN.matcher(input.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid semantic version: " + input);
        }
        return new SemVer(
                Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)),
                m.group(4),
                m.group(5));
    }

    public static boolean isValid(String input) {
        return input != null && PATTERN.matcher(input.trim()).matches();
    }

    public boolean isPrerelease() {
        return pre != null;
    }

    /** The [major.minor.patch] tuple without prerelease/build, e.g. {@code 4.0.0}. */
    public String core() {
        return major + "." + minor + "." + patch;
    }

    private static int comparePre(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        int len = Math.min(as.length, bs.length);
        for (int i = 0; i < len; i++) {
            int c = compareIdentifier(as[i], bs[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(as.length, bs.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNum = isNumeric(a);
        boolean bNum = isNumeric(b);
        if (aNum && bNum) {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        }
        if (aNum) {
            return -1; // numeric identifiers always have lower precedence
        }
        if (bNum) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SemVer other
                && major == other.major && minor == other.minor && patch == other.patch
                && Objects.equals(pre, other.pre);
    }

    @Override
    public int hashCode() {
        return Objects.hash(major, minor, patch, pre);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder().append(major).append('.').append(minor).append('.').append(patch);
        if (pre != null) {
            sb.append('-').append(pre);
        }
        if (build != null) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
