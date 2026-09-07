package dev.infinia.store.app.upstream;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * DNS-rebinding pinning on the real transport (audit 3.6): every guarded fetch
 * connects to the address the guard validated and — when that address differs
 * from the URL's own authority — keeps the original authority in the Host
 * header. A loopback stand-in server records what the store actually sent.
 */
class RepoFetcherPinningTest {

    private HttpServer server;
    private final AtomicReference<String> seenHostHeader = new AtomicReference<>();
    private final AtomicReference<String> seenPath = new AtomicReference<>();

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            seenHostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            seenPath.set(exchange.getRequestURI().getPath());
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    private RepoFetcher fetcher() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("store.upstream.allow-internal", "true");
        return new RepoFetcher(env);
    }

    @Test
    void guardedFetchServesThroughTheValidatedAddress() throws Exception {
        startServer();
        int port = server.getAddress().getPort();
        var json = fetcher().fetchJson("http://127.0.0.1:" + port + "/marketplace.json");
        assertEquals(true, json.path("ok").asBoolean());
        assertEquals("/marketplace.json", seenPath.get());
        // Literal-IP URL: the validated address equals the authority, so the Host
        // header must match it exactly (no accidental rewrite to something else).
        assertEquals("127.0.0.1:" + port, seenHostHeader.get());
    }

    @Test
    void guardedFileDownloadStreamsThroughTheValidatedAddress() throws Exception {
        startServer();
        int port = server.getAddress().getPort();
        java.nio.file.Path target = java.nio.file.Files.createTempFile("pinning-test", ".json");
        try {
            fetcher().fetchFileFollowingRedirects(
                    "http://127.0.0.1:" + port + "/payload/download?sig=abc", target, 1024);
            assertEquals("{\"ok\":true}", java.nio.file.Files.readString(target));
            assertEquals("/payload/download", seenPath.get());
            assertNotNull(seenHostHeader.get());
        } finally {
            java.nio.file.Files.deleteIfExists(target);
        }
    }
}
