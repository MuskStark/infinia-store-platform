package dev.infinia.store.scanner;

import java.net.InetAddress;
import java.net.URI;

/**
 * Outbound fetch policy for upstream aggregation (plan §3.2): HTTPS/HTTP only,
 * no credentials in URLs, and hard blocks on loopback / private / link-local /
 * cloud-metadata targets so upstream content cannot pivot the store into the
 * internal network (SSRF).
 */
public final class SourceFetchGuard {

    private SourceFetchGuard() {}

    /**
     * Integration tests stand in fake upstreams on loopback; production keeps
     * this false so upstream content can never pivot the store inward.
     */
    private static final boolean ALLOW_INTERNAL = Boolean.getBoolean(
            "store.upstream.allow-internal");

    public static void validate(String url) {
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
            for (InetAddress address : InetAddress.getAllByName(host)) {
                if (!ALLOW_INTERNAL && (address.isLoopbackAddress()
                        || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress())) {
                    throw new IllegalArgumentException(
                            "Upstream host resolves to a blocked address range: " + host);
                }
            }
        } catch (IllegalArgumentException ok) {
            throw ok;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot resolve upstream host " + host);
        }
    }
}
