package dev.infinia.store.app.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import dev.infinia.store.app.config.KeyMaterial;
import dev.infinia.store.app.config.StoreProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Security wiring (design §7.2, §4.1):
 *
 * <ul>
 *   <li>Authorization server chain — OAuth 2.1 authorization code + PKCE for the SPA
 *       and desktop hosts, client credentials for the publisher CLI, form login for
 *       the authorize endpoint.</li>
 *   <li>API chain — JWT resource server; catalog and update feed stay anonymous.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    // Public API paths that never require authentication (design §3.1 anonymous browsing).
    private static final String[] PUBLIC_API = {
            "/api/v1/catalog", "/api/v1/listings/**", "/api/v1/resolutions",
            "/api/v1/updates/**", "/api/v1/auth/register", "/api/v1/auth/login",
            "/api/v1/blobs/**", "/api/v1/compat/**",
            "/api/v1/releases/*/download-ticket", "/api/v1/releases/*/install-manifest"
    };

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerChain(HttpSecurity http,
            RegisteredClientRepository clients, StoreProperties properties) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(authorizationServer.getEndpointsMatcher())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/token", "/oauth2/revoke"))
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(
                                properties.webSignInUri() + "?oauth=1"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)))
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .with(authorizationServer, configurer -> configurer
                        .oidc(Customizer.withDefaults()));
        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_API).permitAll()
                        .requestMatchers("/api/v1/reviews/**").hasAnyRole("REVIEWER", "PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/publisher/**")
                        .hasAnyRole("PUBLISHER", "ORG_ADMIN", "REVIEWER", "PLATFORM_ADMIN")
                        .requestMatchers("/api/v1/organizations/**").authenticated()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.disable())
                // Picks up the bean named "corsConfigurationSource" by name.
                .cors(Customizer.withDefaults())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.decoder(jwtDecoder)
                        .jwtAuthenticationConverter(rolesConverter())));
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain defaultChain(HttpSecurity http,
            StoreProperties properties) throws Exception {
        // Actuator health, legacy login redirect, and the Store Web session-login bridge;
        // everything else is handled above.
        http.securityMatcher("/actuator/**", "/login", "/logout", "/error", "/web/**", "/",
                        "/oauth2/session-login", "/oauth2/session-login/csrf")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/actuator/health/**", "/login", "/error", "/web/**", "/",
                                "/oauth2/session-login", "/oauth2/session-login/csrf")
                        .permitAll()
                        .anyRequest().authenticated())
                // Store Web renders the credential UI. This internal POST endpoint only
                // establishes the browser session needed to resume a saved OAuth request.
                .formLogin(form -> form
                        // Spring requires loginPage to be an application-relative matcher.
                        // GET /login is a deprecated redirect to Store Web, never a form.
                        .loginPage("/login")
                        .loginProcessingUrl("/oauth2/session-login")
                        .failureHandler((request, response, failure) -> response.sendRedirect(
                                properties.webSignInUri() + "?oauth=1&error=1")));
        return http.build();
    }

    @Bean
    public RegisteredClientRepository registeredClients(StoreProperties properties) {
        RegisteredClient web = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("store-web")
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.NONE)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(properties.webRedirectUri())
                .redirectUri(properties.baseUrl() + "/web/callback")
                .scope("openid")
                .scope("profile")
                // SAS 7.x issues refresh tokens for the authorization-code grant only
                // when offline_access is requested (OIDC offline-access semantics).
                .scope("offline_access")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false)
                        .requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30))
                        .refreshTokenTimeToLive(Duration.ofDays(30))
                        .build())
                .build();

        RegisteredClient cli = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(properties.cliClientId())
                .clientSecret("{bcrypt}" + new BCryptPasswordEncoder().encode(properties.cliClientSecret()))
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("publisher.write")
                .scope("review.write")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30)).build())
                .build();

        // FengYu desktop host: authorization code + PKCE via system browser with a
        // loopback redirect (RFC 8252). Confidential (client_secret_post) because
        // Spring Authorization Server 7 does not authenticate public clients on the
        // refresh-token grant — long-lived desktop sessions need refresh tokens
        // (design §7.2). PKCE stays required on top of the shared secret.
        RegisteredClient.Builder desktop = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("fengyu-desktop")
                .clientSecret("{bcrypt}" + new BCryptPasswordEncoder()
                        .encode(properties.desktopClientSecret()))
                .clientAuthenticationMethod(
                        org.springframework.security.oauth2.core.ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(
                        org.springframework.security.oauth2.core.AuthorizationGrantType.REFRESH_TOKEN)
                .scope("openid")
                .scope("profile")
                .scope("offline_access")
                .clientSettings(ClientSettings.builder().requireAuthorizationConsent(false)
                        .requireProofKey(true).build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(30))
                        .refreshTokenTimeToLive(Duration.ofDays(30)).build());
        for (String uri : properties.desktopRedirectUris()) {
            desktop.redirectUri(uri);
        }

        return new InMemoryRegisteredClientRepository(web, cli, desktop.build());
    }

    /**
     * Enriches access tokens with store roles and links them to a session ledger row
     * so users can see and revoke active grants (design §7.4).
     */
    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer(
            dev.infinia.store.domain.port.IdentityRepositories.SessionRepository sessions,
            StoreUserDetailsService userDetailsService,
            dev.infinia.store.domain.port.IdentityRepositories.UserRepository userRepository) {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            if (org.springframework.security.oauth2.core.AuthorizationGrantType.CLIENT_CREDENTIALS
                    .equals(context.getAuthorizationGrantType())) {
                // The CLI acts as the seeded CI service account (design §7.2 PAT semantics:
                // only the account id and roles live in the token, never secrets).
                var ci = userRepository.findByEmailNormalized("ci@infinia.local");
                if (ci.isPresent()) {
                    context.getClaims().claim("uid", ci.get().id.toString());
                    context.getClaims().claim("roles", ci.get().roles.stream()
                            .map(Enum::name).toList());
                } else {
                    context.getClaims().claim("roles", List.of("PUBLISHER", "REVIEWER", "USER"));
                }
                return;
            }
            String username = context.getPrincipal().getName();
            dev.infinia.store.domain.model.StoreUser user = userDetailsService
                    .findByEmailNormalized(StoreUserDetailsService.normalize(username));
            if (user == null) {
                return;
            }
            context.getClaims().claim("uid", user.id.toString());
            context.getClaims().claim("roles", user.roles.stream().map(Enum::name).toList());
            context.getClaims().claim("email", user.email);
            UUID sessionId = UUID.randomUUID();
            sessions.save(new dev.infinia.store.domain.port.IdentityRepositories.UserSessionRecord(
                    sessionId, user.id, context.getRegisteredClient().getClientId(),
                    "OAUTH_TOKEN", null, java.time.Instant.now(), null, false, null));
            context.getClaims().claim("sid", sessionId.toString());
        };
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(KeyMaterial keyMaterial) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.jwtPublicKey())
                .privateKey(keyMaterial.jwtKeyPair().getPrivate())
                .keyID("store-jwt-1")
                .build();
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }

    /**
     * Same-application validation: decode with the shared signing key instead of
     * fetching our own JWKS endpoint over HTTP (avoids self-call startup deadlocks).
     */
    @Bean
    public JwtDecoder jwtDecoder(KeyMaterial keyMaterial,
            dev.infinia.store.domain.port.IdentityRepositories.SessionRepository sessions) {
        NimbusJwtDecoder delegate = NimbusJwtDecoder
                .withPublicKey(keyMaterial.jwtPublicKey())
                .build();
        return token -> {
            var jwt = delegate.decode(token);
            String sid = jwt.getClaimAsString("sid");
            if (sid != null) {
                boolean revoked = sessions.findById(UUID.fromString(sid))
                        .map(s -> s.revoked())
                        .orElse(true);
                if (revoked) {
                    throw new org.springframework.security.oauth2.jwt.JwtValidationException(
                            "Session revoked", java.util.List.of(
                                    new org.springframework.security.oauth2.core.OAuth2Error(
                                            "session_revoked", "Session was revoked", null)));
                }
            }
            return jwt;
        };
    }

    /**
     * Maps the token's {@code roles} claim to ROLE_ authorities (the default
     * converter only understands scope/scp/authorities claims).
     */
    static org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter rolesConverter() {
        var converter = new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            java.util.List<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) {
                return java.util.List.<org.springframework.security.core.GrantedAuthority>of();
            }
            return roles.stream()
                    .<org.springframework.security.core.GrantedAuthority>map(
                            r -> new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                    "ROLE_" + r))
                    .toList();
        });
        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(StoreProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins().isEmpty()
                ? List.of("http://localhost:8089") : properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Location", "ETag", "Idempotency-Key"));
        config.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/oauth2/**", config);
        return source;
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(StoreProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.baseUrl()).build();
    }
}
