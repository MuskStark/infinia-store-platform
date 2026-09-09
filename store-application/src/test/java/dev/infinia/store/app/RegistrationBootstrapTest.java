package dev.infinia.store.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Admin bootstrap on an empty deployment (design §7.4): production seeds
 * nothing — the demo credentials are public knowledge and refused outside the
 * dev profiles — so the FIRST account ever registered receives PLATFORM_ADMIN
 * and everyone after it the plain USER role. Runs against its own empty H2
 * database with seeding disabled; the suite's shared database is pre-seeded
 * with the demo admin and would exercise neither branch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:store-register-bootstrap;"
                        + "MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
                "store.seed.enabled=false"})
@ActiveProfiles("test")
class RegistrationBootstrapTest {

    @LocalServerPort
    int port;

    @Autowired
    dev.infinia.store.app.service.AccountService accounts;

    private Http http() {
        return new Http(port);
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstRegistrationBecomesPlatformAdminAndGrantsRolesFromTheConsole() {
        ResponseEntity<Map> first = http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register",
                json(null), Map.of("email", "owner@infinia.local", "password", "OwnerPass123!",
                        "displayName", "Owner"), Map.class);
        assertEquals(HttpStatus.CREATED, first.getStatusCode());
        List<String> ownerRoles = (List<String>) first.getBody().get("roles");
        assertTrue(ownerRoles.contains("PLATFORM_ADMIN"),
                "the first account on an empty deployment owns the instance: " + ownerRoles);
        assertTrue(ownerRoles.contains("USER"));

        // The bootstrap admin can act: the admin user console answers to their token.
        String token = AuthTestSupport.login(http(), null, "owner@infinia.local", "OwnerPass123!");
        ResponseEntity<List> console = http().getJson("/api/v1/admin/users", List.class,
                bearer(token));
        assertEquals(HttpStatus.OK, console.getStatusCode());

        // Everyone after the first stays a plain user.
        ResponseEntity<Map> second = http().exchangeJson(HttpMethod.POST, "/api/v1/auth/register",
                json(null), Map.of("email", "later@infinia.local", "password", "LaterPass123!",
                        "displayName", "Later"), Map.class);
        assertEquals(HttpStatus.CREATED, second.getStatusCode());
        assertEquals(List.of("USER"), second.getBody().get("roles"));
    }

    private static HttpHeaders json(HttpHeaders headers) {
        if (headers == null) {
            headers = new HttpHeaders();
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
