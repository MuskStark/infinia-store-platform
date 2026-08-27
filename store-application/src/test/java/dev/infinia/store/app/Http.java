package dev.infinia.store.app;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Minimal HTTP client for integration tests — Boot 4 removed TestRestTemplate, and
 * the OAuth dance needs redirects followed manually anyway.
 */
public final class Http {

    private final RestTemplate raw;
    public final String base;

    public Http(int port) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod)
                    throws java.io.IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };
        this.raw = new RestTemplate(factory);
        this.raw.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false; // let tests read 4xx/5xx bodies directly
            }

            public void handleError(org.springframework.http.client.ClientHttpResponse response) {
            }
        });
        this.base = "http://localhost:" + port;
    }

    public String url(String path) {
        return path.startsWith("http") ? path : base + path;
    }

    public ResponseEntity<String> get(String path, HttpHeaders headers) {
        return raw.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers == null ? new HttpHeaders() : headers), String.class);
    }

    public <T> ResponseEntity<T> getJson(String path, Class<T> type, HttpHeaders headers) {
        return raw.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(headers == null ? new HttpHeaders() : headers), type);
    }

    /** Binary GET — for package downloads that must not be decoded as text. */
    public ResponseEntity<byte[]> getBytes(String path) {
        return raw.exchange(url(path), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), byte[].class);
    }

    public ResponseEntity<String> postForm(String path, Map<String, String> form,
            HttpHeaders headers) {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) {
                body.append('&');
            }
            body.append(encode(k)).append('=').append(encode(v == null ? "" : v));
        });
        HttpHeaders merged = headers == null ? new HttpHeaders() : new HttpHeaders(headers);
        merged.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return raw.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body.toString(), merged), String.class);
    }

    public ResponseEntity<String> exchange(HttpMethod method, String path, HttpHeaders headers,
            Object body) {
        return raw.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
    }

    public <T> ResponseEntity<T> exchangeJson(HttpMethod method, String path, HttpHeaders headers,
            Object body, Class<T> type) {
        return raw.exchange(url(path), method, new HttpEntity<>(body, headers), type);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    public static HttpHeaders bearerJson(String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public static HttpHeaders acceptHtml() {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.TEXT_HTML, MediaType.ALL));
        return headers;
    }

    public static String cookie(ResponseEntity<?> response) {
        var setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookie == null || setCookie.isEmpty()) {
            return null;
        }
        return String.join("; ", setCookie.stream().map(c -> c.split(";")[0]).toList());
    }
}
