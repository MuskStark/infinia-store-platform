package dev.infinia.store.app;

import dev.infinia.store.app.web.AdminAppReleaseController;
import dev.infinia.store.contract.type.Channel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel vocabulary (audit P1-6): the FengYu release flow cuts {@code vX.Y.Z-rc.N}
 * tags, so {@code rc} is a first-class channel — clients deriving their channel
 * from the version suffix must not 400 on an unknown value.
 */
class ChannelInferenceTest {

    @Test
    void inferChannelMapsPreReleaseSuffixesToTheirOwnChannels() {
        assertEquals("stable", AdminAppReleaseController.inferChannel("4.1.0"));
        assertEquals("beta", AdminAppReleaseController.inferChannel("4.1.0-beta.1"));
        assertEquals("rc", AdminAppReleaseController.inferChannel("4.1.0-rc.1"),
                "rc is its own channel, not folded into beta");
        assertEquals("rc", AdminAppReleaseController.inferChannel("4.1.0-rc.12"));
        assertEquals("alpha", AdminAppReleaseController.inferChannel("4.1.0-alpha.3"));
        assertEquals("nightly", AdminAppReleaseController.inferChannel("4.1.0-nightly.20260907"));
    }

    @Test
    void channelEnumParsesRcCaseInsensitively() {
        assertEquals(Channel.RC, Channel.parse("rc"));
        assertEquals(Channel.RC, Channel.parse("RC"));
        assertEquals(Channel.STABLE, Channel.parse(" stable "));
        assertEquals(Channel.PRIVATE, Channel.parse("private"));
    }

    @Test
    void channelParseFailureListsValidValues() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Channel.parse("canary"));
        assertTrue(e.getMessage().contains("stable")
                && e.getMessage().contains("rc")
                && e.getMessage().contains("beta")
                && e.getMessage().contains("alpha")
                && e.getMessage().contains("nightly")
                && e.getMessage().contains("private"),
                "error must list the accepted values: " + e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Channel.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Channel.parse(" "));
    }
}
