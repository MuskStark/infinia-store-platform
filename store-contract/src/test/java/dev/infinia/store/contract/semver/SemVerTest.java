package dev.infinia.store.contract.semver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {

    @Test
    void parsesFullVersion() {
        SemVer v = SemVer.parse("4.0.0-beta.5+build.123");
        assertEquals(4, v.major);
        assertEquals(0, v.minor);
        assertEquals(0, v.patch);
        assertEquals("beta.5", v.pre);
        assertEquals("build.123", v.build);
    }

    @ParameterizedTest
    @CsvSource({
            "1.0.0, 2.0.0, -1",
            "2.1.0, 2.1.0, 0",
            "1.0.0-alpha, 1.0.0, -1",
            "1.0.0-alpha.1, 1.0.0-alpha.2, -1",
            "1.0.0-alpha.2, 1.0.0-alpha.10, -1",
            "1.0.0-alpha, 1.0.0-beta, -1",
            "1.0.0-alpha.1, 1.0.0-alpha.beta, -1",
            "1.0.0-rc.1, 1.0.0, -1",
            "1.2.3, 1.2.4, -1",
            "1.10.0, 1.9.0, 1",
    })
    void comparesBySemverPrecedence(String a, String b, int expectedSign) {
        int cmp = SemVer.parse(a).compareTo(SemVer.parse(b));
        assertEquals(expectedSign, Integer.signum(cmp), a + " vs " + b);
    }

    @Test
    void buildMetadataIgnoredForEqualityAndOrder() {
        assertEquals(SemVer.parse("1.0.0+a"), SemVer.parse("1.0.0+b"));
        assertEquals(0, SemVer.parse("1.0.0+a").compareTo(SemVer.parse("1.0.0+b")));
    }

    @Test
    void hugeNumericPrereleaseIdentifiersDoNotCrashComparison() {
        // 20+ digit numeric identifiers parse fine per semver 2.0.0 but overflow long;
        // comparison must not throw NumberFormatException.
        SemVer big = SemVer.parse("1.0.0-99999999999999999999.1");
        SemVer bigger = SemVer.parse("1.0.0-100000000000000000000.1");
        assertEquals(-1, Integer.signum(big.compareTo(bigger)));
        assertEquals(1, Integer.signum(bigger.compareTo(big)));
        assertEquals(0, big.compareTo(SemVer.parse("1.0.0-99999999999999999999.1")));
    }

    @Test
    void hugeNumericIdentifierStillLosesAgainstAlphanumeric() {
        // Numeric identifiers always have lower precedence than alphanumeric ones,
        // regardless of magnitude.
        SemVer numeric = SemVer.parse("1.0.0-99999999999999999999");
        SemVer alpha = SemVer.parse("1.0.0-alpha");
        assertEquals(-1, Integer.signum(numeric.compareTo(alpha)));
        assertEquals(1, Integer.signum(alpha.compareTo(numeric)));
    }

    @Test
    void hugeNumericIdentifiersSortNumericallyNotLexicographically() {
        SemVer shorter = SemVer.parse("1.0.0-99999999999999999999");
        SemVer longer = SemVer.parse("1.0.0-100000000000000000000");
        assertEquals(-1, Integer.signum(shorter.compareTo(longer)));
        // ...and mixed numeric/alphanumeric identifiers keep semver ordering.
        SemVer num = SemVer.parse("1.0.0-99999999999999999999.rc");
        SemVer alpha = SemVer.parse("1.0.0-rc.99999999999999999999");
        assertEquals(-1, Integer.signum(num.compareTo(alpha)));
    }

    @ParameterizedTest
    @CsvSource({
            "1.0",
            "1",
            "1.0.0.0",
            "01.0.0",
            "1.0.0-",
            "1.0.0+",
            "not-a-version"
    })
    void rejectsInvalidVersions(String input) {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse(input));
        assertFalse(SemVer.isValid(input));
    }
}
