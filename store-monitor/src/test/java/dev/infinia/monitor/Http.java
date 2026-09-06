package dev.infinia.monitor;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** Minimal HTTP client for integration tests (same pattern as store-application). */
public final class Http {

    private final RestTemplate raw = new RestTemplate();
    private final String base;

    public Http(int port) {
        this.base = "http://localhost:" + port;
    }

    public <T> ResponseEntity<T> getJson(String path, Class<T> type) {
        return raw.exchange(base + path, HttpMethod.GET, new HttpEntity<>(new HttpHeaders()),
                type);
    }
}
