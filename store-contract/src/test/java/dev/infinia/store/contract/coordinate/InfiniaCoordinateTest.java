package dev.infinia.store.contract.coordinate;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.ListingType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InfiniaCoordinateTest {

    @Test
    void parsesFullCoordinate() {
        InfiniaCoordinate c = InfiniaCoordinate.parse("infinia://plugin/official/markdown@4.0.0-beta.5");
        assertEquals(ListingType.PLUGIN, c.type);
        assertEquals("official", c.namespace);
        assertEquals("markdown", c.slug);
        assertEquals(SemVer.parse("4.0.0-beta.5"), c.version);
        assertEquals("infinia://plugin/official/markdown@4.0.0-beta.5", c.toString());
    }

    @Test
    void parsesListingOnlyCoordinate() {
        InfiniaCoordinate c = InfiniaCoordinate.parse("infinia://flow/summer/mail-digest");
        assertNull(c.version);
        assertEquals("infinia://flow/summer/mail-digest", c.toString());
    }

    @Test
    void normalizesCase() {
        InfiniaCoordinate c = InfiniaCoordinate.parse("infinia://PLUGIN/Official/Markdown");
        assertEquals("official", c.namespace);
        assertEquals("markdown", c.slug);
        assertEquals("infinia://plugin/official/markdown", c.toString());
    }

    @Test
    void listingPartAndWithVersion() {
        InfiniaCoordinate full = InfiniaCoordinate.of(ListingType.MCP, "official", "calendar",
                SemVer.parse("1.0.0"));
        assertEquals("infinia://mcp/official/calendar", full.listingPart().toString());
        assertEquals("infinia://mcp/official/calendar@2.0.0",
                full.withVersion(SemVer.parse("2.0.0")).toString());
    }

    @Test
    void rejectsInvalidSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.parse("infinia://plugin/-bad-/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.parse("infinia://plugin/official/bad_slug"));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.parse("https://plugin/official/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.parse("infinia://plugin/official"));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.parse("infinia://unknown/official/markdown"));
        assertThrows(IllegalArgumentException.class,
                () -> InfiniaCoordinate.of(ListingType.PLUGIN, "UPPER", "ok"));
    }
}
