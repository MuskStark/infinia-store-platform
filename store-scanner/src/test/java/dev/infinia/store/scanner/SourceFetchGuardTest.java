package dev.infinia.store.scanner;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SSRF policy and DNS-rebinding pinning (audit 3.6): the guard must resolve the
 * host exactly once, reject every blocked address range, and hand the caller a
 * request shape that connects to the validated address instead of re-resolving
 * the hostname.
 */
class SourceFetchGuardTest {

    // This module runs without store.upstream.allow-internal, so the range
    // checks below observe the production posture.

    @Test
    void rejectsNonHttpSchemesAndCredentials() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("ftp://example.com/pkg"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://user:pass@example.com/pkg"));
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http:///no-host"));
    }

    @Test
    void rejectsBlockedAddressRanges() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://127.0.0.1:9/x"),
                "loopback literal");
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://localhost:9/x"),
                "loopback name resolves into the blocked range");
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://10.0.0.5:9/x"),
                "RFC1918 literal");
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://169.254.169.254:9/x"),
                "cloud metadata range");
        assertThrows(IllegalArgumentException.class,
                () -> SourceFetchGuard.validate("http://[fd00::1]:9/x"),
                "IPv6 unique-local");
    }

    @Test
    void resolveValidatedReturnsThePinnedAddress() throws Exception {
        SourceFetchGuard.GuardedFetch guarded =
                SourceFetchGuard.resolveValidated("http://203.0.113.7:8080/pkg/x?q=1");
        assertEquals(InetAddress.getByName("203.0.113.7"), guarded.address());
        assertEquals("203.0.113.7:8080", guarded.hostHeader());
        // Literal-IP authority already equals the validated address.
        assertEquals(guarded.uri(), guarded.requestUri());
    }

    @Test
    void plainHttpRequestUriConnectsToTheValidatedAddressWithOriginalHost() throws Exception {
        SourceFetchGuard.GuardedFetch guarded = new SourceFetchGuard.GuardedFetch(
                java.net.URI.create("http://upstream.example:8443/a/b/c.tar.gz?ref=heads"),
                InetAddress.getByName("203.0.113.7"));
        assertEquals("http://203.0.113.7:8443/a/b/c.tar.gz?ref=heads",
                guarded.requestUri().toString(),
                "the connection must go to the validated address");
        assertEquals("upstream.example:8443", guarded.hostHeader(),
                "the Host header must preserve the original authority");
    }

    @Test
    void httpsKeepsTheHostnameConnection() throws Exception {
        // TLS endpoint verification binds the connection to the validated name;
        // an IP-authority request would break SNI and certificate checks.
        SourceFetchGuard.GuardedFetch guarded = new SourceFetchGuard.GuardedFetch(
                java.net.URI.create("https://api.github.com/repos/x/y"),
                InetAddress.getByName("203.0.113.7"));
        assertEquals("https://api.github.com/repos/x/y", guarded.requestUri().toString());
        assertEquals("api.github.com", guarded.hostHeader());
    }

    @Test
    void ipv6ValidatedAddressIsBracketedInRequestUri() throws Exception {
        InetAddress ipv6 = InetAddress.getByName("2001:db8::1");
        SourceFetchGuard.GuardedFetch guarded = new SourceFetchGuard.GuardedFetch(
                java.net.URI.create("http://upstream.example:9/x"), ipv6);
        assertEquals("http://[" + ipv6.getHostAddress() + "]:9/x",
                guarded.requestUri().toString(),
                "IPv6 authority must be bracketed");
    }

    @Test
    void defaultPortsOmitThePortSuffix() throws Exception {
        SourceFetchGuard.GuardedFetch guarded = new SourceFetchGuard.GuardedFetch(
                java.net.URI.create("http://upstream.example/x"),
                InetAddress.getByName("203.0.113.7"));
        assertEquals("http://203.0.113.7/x", guarded.requestUri().toString());
        assertEquals("upstream.example", guarded.hostHeader());
    }
}
