package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Resolution API contract details (audit P2-3): the client-reported installed map
 * is keyed by whatever string the client sent, while the solver keys by the
 * normalized listing coordinate — mismatched casing silently forced
 * {@code alreadyInstalled=false} and reinstalled everything. The controller now
 * normalizes keys; invalid coordinates are ignored instead of failing the request.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ResolutionApiTest {

    @LocalServerPort
    int port;

    Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void installedKeysAreNormalizedToSolverCoordinates() {
        // Seeded official.markdown 2.4.0; the client reports mixed case + a build
        // suffix — exactly the shape the host sends after reading its manifest.
        Map<String, Object> body = Map.of(
                "coordinate", "infinia://plugin/official/markdown",
                "range", ">=2.0.0 <3.0.0",
                "client", Map.of(
                        "hostVersion", "4.1.0",
                        "os", "windows",
                        "arch", "x64",
                        "channel", "stable",
                        "installed", List.of(
                                Map.of("coordinate", "infinia://PLUGIN/Official/Markdown",
                                        "version", "2.4.0"))));

        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/resolutions", json(), body, Map.class);
        assertEquals(200, response.getStatusCode().value());
        assertTrue((Boolean) response.getBody().get("resolvable"),
                "body: " + response.getBody());

        List<Map<String, Object>> plan = (List<Map<String, Object>>) response.getBody()
                .get("plan");
        Map<String, Object> markdown = plan.stream()
                .filter(p -> String.valueOf(p.get("coordinate"))
                        .endsWith("official/markdown@2.4.0"))
                .findFirst().orElseThrow();
        assertEquals(true, markdown.get("alreadyInstalled"),
                "mixed-case installed coordinate must match the solver's normalized key");
    }

    @Test
    @SuppressWarnings("unchecked")
    void invalidInstalledCoordinatesAreIgnoredNotFatal() {
        Map<String, Object> body = Map.of(
                "coordinate", "infinia://plugin/official/markdown",
                "client", Map.of(
                        "hostVersion", "4.1.0",
                        "installed", List.of(
                                Map.of("coordinate", "not a coordinate at all",
                                        "version", "1.0.0"),
                                Map.of("coordinate", "infinia://plugin/official/markdown",
                                        "version", "2.4.0"))));

        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/resolutions", json(), body, Map.class);
        assertEquals(200, response.getStatusCode().value());
        List<Map<String, Object>> plan = (List<Map<String, Object>>) response.getBody()
                .get("plan");
        Map<String, Object> markdown = plan.stream()
                .filter(p -> String.valueOf(p.get("coordinate"))
                        .endsWith("official/markdown@2.4.0"))
                .findFirst().orElseThrow();
        assertEquals(true, markdown.get("alreadyInstalled"));
    }

    @Test
    void unknownChannelIsAValidationErrorNamingTheValidValues() {
        Map<String, Object> body = Map.of(
                "coordinate", "infinia://plugin/official/markdown",
                "client", Map.of("hostVersion", "4.1.0", "channel", "canary"));
        ResponseEntity<Map> response = http().exchangeJson(HttpMethod.POST,
                "/api/v1/resolutions", json(), body, Map.class);
        assertEquals(400, response.getStatusCode().value());
        assertTrue(String.valueOf(response.getBody().get("detail")).contains("rc"),
                "friendly message listing valid channels: " + response.getBody());
    }

    private HttpHeaders json() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
