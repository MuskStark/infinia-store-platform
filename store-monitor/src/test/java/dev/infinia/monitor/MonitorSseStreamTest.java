package dev.infinia.monitor;

import com.sun.net.httpserver.HttpServer;
import dev.infinia.monitor.service.PollCycle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live event stream end to end against a real port: a fresh connection
 * gets a {@code snapshot}; confirmed transitions arrive as
 * {@code component.updated} without the page polling; a reconnect presenting
 * {@code Last-Event-ID} gets exactly the missed events replayed instead of a
 * fresh snapshot. The fake store flips healthy to drive a confirmed outage.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "monitor.probe-interval-ms=3600000",
                "monitor.probe-initial-delay-ms=3600000",
                "monitor.mirror-interval-ms=3600000",
                "monitor.mirror-initial-delay-ms=3600000",
                "monitor.rollup-interval-ms=3600000",
                "monitor.rollup-initial-delay-ms=3600000",
                "spring.datasource.url=jdbc:h2:mem:monitor-sse;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        })
class MonitorSseStreamTest {

    private static final String STORE_PAGE = """
            {
              "indicator": "operational",
              "checkedAt": "2026-09-14T10:00:00Z",
              "components": [
                {"key": "api", "indicator": "operational", "uptime90d": 99.9, "history": []}
              ]
            }
            """;

    static final AtomicBoolean storeHealthy = new AtomicBoolean(true);
    private static HttpServer fakeStore;

    @DynamicPropertySource
    static void fakeStoreUrl(DynamicPropertyRegistry registry) throws Exception {
        fakeStore = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeStore.createContext("/", MonitorSseStreamTest::serve);
        fakeStore.start();
        registry.add("monitor.target-base-url",
                () -> "http://127.0.0.1:" + fakeStore.getAddress().getPort());
    }

    static void serve(com.sun.net.httpserver.HttpExchange exchange) {
        try {
            int status = storeHealthy.get() ? 200 : 503;
            String path = exchange.getRequestURI().getPath();
            String body = status == 200 && "/api/v1/status".equals(path)
                    ? STORE_PAGE : (status == 200 ? "[]" : "{\"status\":\"DOWN\"}");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception ignored) {
            // client hung up; nothing to assert
        }
    }

    @AfterAll
    static void stopFakeStore() {
        if (fakeStore != null) {
            fakeStore.stop(0);
        }
        storeHealthy.set(true);
    }

    @LocalServerPort
    int port;

    @Autowired
    PollCycle pollCycle;

    @Test
    void snapshotThenLiveEventsThenReplayAfterReconnect() throws Exception {
        pollCycle.cycle(); // prime: external confirmed + mirrored api published

        try (SseStream stream = SseStream.open(port, null)) {
            assertEquals("no", stream.header("X-Accel-Buffering"),
                    "nginx-family proxies must be told not to buffer the stream");

            Frame snapshot = stream.nextFrame();
            assertEquals("snapshot", snapshot.event());
            assertTrue(snapshot.data().contains("\"indicator\""),
                    "snapshot carries the full status page");

            storeHealthy.set(false);
            pollCycle.cycle(); // first failing probe: 确认中
            Frame pending = stream.nextFrame();
            assertEquals("component.updated", pending.event());
            assertTrue(pending.data().contains("external"));
            assertTrue(pending.data().contains("\"pending\":true"),
                    "the first failure pushes 确认中, not a flip");

            pollCycle.cycle(); // second failing probe: confirmed
            Frame confirmed = stream.untilFrame(frame -> frame.event().equals("component.updated")
                    && frame.data().contains("\"major_outage\""));
            assertTrue(confirmed.data().contains("external"));
            long confirmedId = confirmed.id();

            Frame incident = stream.nextFrame();
            assertEquals("incident.updated", incident.event());

            // Reconnect from one event behind: replay, not a fresh snapshot.
            try (SseStream replay = SseStream.open(port, Long.toString(confirmedId - 1))) {
                Frame first = replay.nextFrame();
                assertEquals(confirmedId, first.id(),
                        "replay resumes exactly where the client left off");
                assertEquals("component.updated", first.event());
                assertTrue(first.data().contains("major_outage"));
            }
        } finally {
            storeHealthy.set(true);
        }
    }

    /** One parsed SSE frame; comment-only heartbeats are skipped by the reader. */
    record Frame(long id, String event, String data) {}

    /** Minimal streaming SSE client over HttpURLConnection. */
    static final class SseStream implements AutoCloseable {

        private final HttpURLConnection connection;
        private final ExecutorService reader = Executors.newSingleThreadExecutor();
        private final ConcurrentLinkedQueue<Frame> frames = new ConcurrentLinkedQueue<>();
        private final Future<?> pump;

        private SseStream(HttpURLConnection connection) {
            this.connection = connection;
            this.pump = reader.submit(() -> {
                try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                        connection.getInputStream(), StandardCharsets.UTF_8))) {
                    List<String> frame = new ArrayList<>();
                    String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            Frame parsed = parse(frame);
                            if (parsed != null) {
                                frames.add(parsed);
                            }
                            frame = new ArrayList<>();
                        } else {
                            frame.add(line);
                        }
                    }
                } catch (Exception ignored) {
                    // stream closed; queued frames remain readable
                }
            });
        }

        private static Frame parse(List<String> lines) {
            if (lines.isEmpty() || lines.get(0).startsWith(":")) {
                return null; // heartbeat comment, or empty
            }
            long id = 0;
            String event = "message";
            StringBuilder data = new StringBuilder();
            for (String line : lines) {
                if (line.startsWith("id:")) {
                    id = Long.parseLong(line.substring(3).trim());
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
            return new Frame(id, event, data.toString());
        }

        static SseStream open(int port, String lastEventId) throws Exception {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/api/v1/status/events").openConnection();
            connection.setRequestProperty("Accept", "text/event-stream");
            if (lastEventId != null) {
                connection.setRequestProperty("Last-Event-ID", lastEventId);
            }
            connection.setReadTimeout(10_000);
            return new SseStream(connection);
        }

        String header(String name) {
            return connection.getHeaderField(name);
        }

        Frame nextFrame() throws Exception {
            Frame frame = awaitFrame();
            assertNotNull(frame, "a frame must arrive");
            return frame;
        }

        Frame untilFrame(java.util.function.Predicate<Frame> match) throws Exception {
            Frame frame = awaitFrame();
            while (frame != null && !match.test(frame)) {
                frame = awaitFrame();
            }
            assertNotNull(frame, "the expected frame must arrive");
            return frame;
        }

        private Frame awaitFrame() throws Exception {
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                Frame frame = frames.poll();
                if (frame != null) {
                    return frame;
                }
                Thread.sleep(20);
            }
            return null;
        }

        @Override
        public void close() {
            pump.cancel(true);
            reader.shutdownNow();
            connection.disconnect();
        }
    }
}
