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
