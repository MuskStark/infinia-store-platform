package dev.infinia.store.scanner;

import java.net.InetAddress;
import java.net.URI;

/**
 * Outbound fetch policy for upstream aggregation (plan §3.2): HTTPS/HTTP only,
 * no credentials in URLs, and hard blocks on loopback / private / link-local /
 * cloud-metadata targets so upstream content cannot pivot the store into the
 * internal network (SSRF).
 *
 * <p>DNS-rebinding pinning (audit 3.6): {@link #resolveValidated(String)} resolves
 * the host exactly ONCE, validates every returned address and hands the caller the
 * validated address together with the request shapes needed to connect to it —
 * otherwise the HTTP client would resolve the hostname a second time and a rebinding
 * upstream (public IP at validation, internal IP at connect) could slip past the
 * guard in between (TOCTOU). Plain-HTTP URLs connect to the validated address with
 * the original authority pinned in the {@code Host} header; HTTPS URLs keep the
 * hostname connection because TLS endpoint verification binds it to the validated
 * name (an IP-authority request would break SNI and certificate checks), with every
 * resolved address still validated before the request.
 */
public final class SourceFetchGuard {

    static {
        // Needed to keep the virtual-host Host header while connecting to the
        // validated address: the JDK HttpClient rejects a user-supplied Host
        // header unless this property is set before its restricted-header table
        // initializes. Guarded fetches go through this class first, so setting it
        // here activates pinning for them; if another component loaded the client
        // earlier the header stays restricted and callers degrade to a
        // hostname-based request (still range-validated, just unpinned).
        if (System.getProperty("jdk.httpclient.allowRestrictedHeaders") == null) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
        }
    }

    private SourceFetchGuard() {}

    /**
     * Integration tests stand in fake upstreams on loopback; production keeps
     * this false so upstream content can never pivot the store inward.
     */
    private static final boolean ALLOW_INTERNAL = Boolean.getBoolean(
            "store.upstream.allow-internal");

    /**
     * One URL whose host has been resolved and range-validated exactly once.
     * {@code uri} is the logical URL; {@code address} is the validated address
     * the connection must use.
     */
    public record GuardedFetch(URI uri, InetAddress address) {

        /**
         * The URI to connect to: for plain HTTP the authority is replaced by the
         * validated address (bracketed for IPv6) so the client cannot re-resolve
         * the hostname to a different, unvalidated address; for HTTPS this is the
         * logical URI unchanged — the TLS handshake verifies the endpoint against
         * the hostname the guard validated, and every address the host resolved to
         * was range-checked before the request.
         */
        public URI requestUri() {
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                return uri;
            }
            String literal = address.getHostAddress();
            if (literal.contains(":")) {
                literal = "[" + literal + "]"; // IPv6 authority literal
            }
            if (uri.getPort() >= 0) {
                literal += ":" + uri.getPort();
            }
            return URI.create(uri.getScheme() + "://" + literal
                    + (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery())
                    + (uri.getRawFragment() == null ? "" : "#" + uri.getRawFragment()));
        }

        /**
         * The {@code Host} header value preserving the original authority, so
         * name-based virtual hosting keeps working while the connection goes to
         * the validated address.
         */
        public String hostHeader() {
            String host = uri.getHost();
            if (host != null && host.contains(":")) {
                host = "[" + host + "]";
            }
            return uri.getPort() >= 0 ? host + ":" + uri.getPort() : host;
        }
    }

    /** Validates a URL without keeping the resolved address (see {@link #resolveValidated}). */
    public static void validate(String url) {
        resolveValidated(url);
    }

    /**
     * Validates the URL policy and resolves its host exactly once, checking every
     * address the host resolves to. Returns the guarded fetch target whose address
     * the caller must connect to — closing the validate-then-connect rebinding
     * window of a plain {@link #validate} followed by a hostname-based request.
     */
    public static GuardedFetch resolveValidated(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid upstream URL: " + url);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalArgumentException("Upstream URLs must use http(s): " + url);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Credentials in upstream URLs are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Upstream URL has no host: " + url);
        }
        try {
            // Literals return immediately (no DNS); names resolve once here — the
            // caller connects to the returned address instead of re-resolving.
            InetAddress[] addresses = InetAddress.getAllByName(host);
            InetAddress chosen = null;
            for (InetAddress address : addresses) {
                if (!ALLOW_INTERNAL && (address.isLoopbackAddress()
                        || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || isUniqueLocalIpv6(address))) {
                    throw new IllegalArgumentException(
                            "Upstream host resolves to a blocked address range: " + host);
                }
                if (chosen == null) {
                    chosen = address;
                }
            }
            if (chosen == null) {
                throw new IllegalArgumentException("Upstream host resolves to no address: " + host);
            }
            return new GuardedFetch(uri, chosen);
        } catch (IllegalArgumentException ok) {
            throw ok;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot resolve upstream host " + host);
        }
    }

    /**
     * IPv6 unique-local addresses (fc00::/7, in practice fd00::/8) are the IPv6
     * equivalent of RFC1918 space; {@code isSiteLocalAddress()} only knows the
     * deprecated fec0::/10, so they must be rejected explicitly.
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }
}
