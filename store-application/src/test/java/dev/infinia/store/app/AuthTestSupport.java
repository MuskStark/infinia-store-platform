package dev.infinia.store.app;

import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs the full OAuth 2.1 Authorization Code + PKCE dance against the embedded
 * authorization server using a browser-like client (design §7.2): authorize → form
 * login → code → token exchange. This is the same flow the SPA uses.
 */
public final class AuthTestSupport {

    private static final SecureRandom RANDOM = new SecureRandom();
    private AuthTestSupport() {}

    public record OAuthGrant(String accessToken, String refreshToken) {}

    public static String login(Http rest, String baseUrl, String email, String password) {
        return loginGrant(rest, email, password, "store-web", null,
                "http://localhost:8089/callback", "openid").accessToken();
    }

    public static OAuthGrant desktopLogin(Http rest, String email, String password) {
        return loginGrant(rest, email, password, "fengyu-desktop",
                "dev-only-desktop-secret", "http://127.0.0.1:24057/callback",
                "openid profile offline_access");
    }

    public static OAuthGrant refreshDesktop(Http rest, String refreshToken) {
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", "fengyu-desktop");
        form.put("client_secret", "dev-only-desktop-secret");
        ResponseEntity<String> token = rest.postForm("/oauth2/token", form, null);
        if (!token.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Refresh failed: " + token.getStatusCode()
                    + " " + token.getBody());
        }
        return tokenGrant(token.getBody());
    }

    private static OAuthGrant loginGrant(Http rest, String email, String password,
            String clientId, String clientSecret, String redirectUri, String scope) {
        String verifier = randomToken(48);
        String challenge = s256(verifier);
        String state = randomToken(12);

        ResponseEntity<String> authorize = rest.exchange(HttpMethod.GET,
                "/oauth2/authorize?response_type=code&client_id=" + clientId
                        + "&redirect_uri=" + redirectUri
                        + "&scope=" + scope.replace(" ", "+")
                        + "&state=" + state
                        + "&code_challenge=" + challenge
                        + "&code_challenge_method=S256",
                Http.acceptHtml(), null);

        String signInLocation = authorize.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (signInLocation == null
                || !signInLocation.startsWith("http://localhost:8089/signin?oauth=1")) {
            throw new IllegalStateException("OAuth did not redirect to Store Web sign-in: "
                    + signInLocation);
        }

        String sessionCookie = cookie(authorize);
        HttpHeaders csrfHeaders = new HttpHeaders();
        csrfHeaders.setAccept(List.of(MediaType.APPLICATION_JSON));
        if (sessionCookie != null) {
            csrfHeaders.add(HttpHeaders.COOKIE, sessionCookie);
        }
        ResponseEntity<String> csrfResponse = rest.exchange(HttpMethod.GET,
                "/oauth2/session-login/csrf", csrfHeaders, null);
        String csrfCookie = Http.cookie(csrfResponse);
        if (csrfCookie != null) {
            sessionCookie = csrfCookie;
        }
        String csrfParameter = jsonString(csrfResponse.getBody(), "parameterName");
        String csrf = jsonString(csrfResponse.getBody(), "token");
        if (!csrfResponse.getStatusCode().is2xxSuccessful()
                || csrfParameter == null || csrf == null) {
            throw new IllegalStateException("Session-login CSRF initialization failed: "
                    + csrfResponse.getStatusCode() + " " + csrfResponse.getBody());
        }

        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("username", email);
        form.put("password", password);
        form.put(csrfParameter, csrf);
        HttpHeaders loginHeaders = new HttpHeaders();
        if (sessionCookie != null) {
            loginHeaders.add(HttpHeaders.COOKIE, sessionCookie);
        }
        ResponseEntity<String> login = rest.postForm("/oauth2/session-login", form, loginHeaders);

        // After successful login we are redirected to the authorize endpoint and then
        // back to the SPA redirect URI carrying ?code=...
        String loginCookie = Http.cookie(login);
        if (loginCookie != null) {
            sessionCookie = loginCookie; // session id may rotate on authentication
        }
        String codeLocation = followToCode(rest, login, sessionCookie);
        String code = param(codeLocation, "code");
        if (code == null) {
            throw new IllegalStateException("No authorization code; last location: " + codeLocation
                    + " (login status " + login.getStatusCode() + ")");
        }

        // Token exchange with PKCE verifier; desktop additionally authenticates
        // as the confidential host client so the refresh grant can be used.
        java.util.Map<String, String> tokenForm = new java.util.LinkedHashMap<>();
        tokenForm.put("grant_type", "authorization_code");
        tokenForm.put("code", code);
        tokenForm.put("redirect_uri", redirectUri);
        tokenForm.put("client_id", clientId);
        if (clientSecret != null) {
            tokenForm.put("client_secret", clientSecret);
        }
        tokenForm.put("code_verifier", verifier);
        ResponseEntity<String> token = rest.postForm("/oauth2/token", tokenForm, null);
        if (!token.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("Token exchange failed: " + token.getStatusCode()
                    + " " + token.getBody());
        }
        return tokenGrant(token.getBody());
    }

    public static String clientCredentialsToken(Http rest, String clientId, String clientSecret) {
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        ResponseEntity<String> token = rest.postForm("/oauth2/token", form, null);
        Matcher access = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
                .matcher(token.getBody());
        if (!token.getStatusCode().is2xxSuccessful() || !access.find()) {
            throw new IllegalStateException("client_credentials failed: " + token.getStatusCode()
                    + " " + token.getBody());
        }
        return access.group(1);
    }

    private static String followToCode(Http rest, ResponseEntity<String> response,
            String sessionCookie) {
        String location = response.getHeaders().getFirst(HttpHeaders.LOCATION);
        if (location != null && location.contains("error")) {
            throw new IllegalStateException("Login failed; redirected to " + location);
        }
        int hops = 0;
        while (location != null && !location.contains("code=") && hops++ < 6) {
            HttpHeaders headers = Http.acceptHtml();
            if (sessionCookie != null) {
                headers.add(HttpHeaders.COOKIE, sessionCookie);
            }
            ResponseEntity<String> next = rest.exchange(HttpMethod.GET, location, headers, null);
            String nextCookie = cookie(next);
            if (nextCookie != null) {
                sessionCookie = nextCookie;
            }
            location = next.getHeaders().getFirst(HttpHeaders.LOCATION);
        }
        return location == null ? "" : location;
    }

    private static String cookie(ResponseEntity<?> response) {
        List<String> setCookie = response.getHeaders().get(HttpHeaders.SET_COOKIE);
        if (setCookie == null || setCookie.isEmpty()) {
            return null;
        }
        return setCookie.stream().map(c -> c.split(";")[0]).reduce((a, b) -> a + "; " + b)
                .orElse(null);
    }

    private static String param(String uri, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(uri);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static OAuthGrant tokenGrant(String body) {
        String accessToken = jsonString(body, "access_token");
        if (accessToken == null) {
            throw new IllegalStateException("No access_token in response: " + body);
        }
        return new OAuthGrant(accessToken, jsonString(body, "refresh_token"));
    }

    private static String jsonString(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name)
                + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body == null ? "" : body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String s256(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(digest.digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
