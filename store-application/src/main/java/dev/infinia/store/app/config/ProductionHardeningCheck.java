package dev.infinia.store.app.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Refuses to start production-like deployments with known-weak defaults (audit P2-5):
 *
 * <ul>
 *   <li>The dev-only {@code ticket-secret}/{@code rollout-secret}/{@code cli-client-secret}
 *       defaults are public knowledge — anyone could forge download tickets, steer
 *       rollout bucketing, or mint CLI tokens.</li>
 *   <li>The {@code prod} profile's fallback embedded-H2 file database silently
 *       carries all publishing transactions; a real deployment must point
 *       {@code spring.datasource.*} at PostgreSQL (or explicitly accept H2 via
 *       {@code STORE_ALLOW_EMBEDDED_H2=true}).</li>
 * </ul>
 *
 * <p>Development profiles (local/dev/test) are exempt. The check runs in
 * {@code @PostConstruct}, before the web server binds: a violation aborts startup.
 */
@Component
public class ProductionHardeningCheck {

    private static final Logger log = LoggerFactory.getLogger(ProductionHardeningCheck.class);

    /** Chosen approach: refuse startup (fail fast) with an actionable message. */
    static final String ALLOW_EMBEDDED_H2_ENV = "STORE_ALLOW_EMBEDDED_H2";

    private final StoreProperties properties;
    private final Environment environment;

    public ProductionHardeningCheck(StoreProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    void verify() {
        if (environment.acceptsProfiles(Profiles.of("local", "dev", "test"))) {
            return; // development convenience profiles keep the defaults
        }
        if ("dev-only-ticket-secret-change-me".equals(properties.ticketSecret())) {
            throw new IllegalStateException("store.ticket-secret is left at its dev-only"
                    + " default — set STORE_TICKET_SECRET to a strong random value before"
                    + " running outside local/dev/test profiles");
        }
        if ("dev-only-rollout-secret-change-me".equals(properties.rolloutSecret())) {
            throw new IllegalStateException("store.rollout-secret is left at its dev-only"
                    + " default — set STORE_ROLLOUT_SECRET to a strong random value before"
                    + " running outside local/dev/test profiles");
        }
        if ("dev-only-cli-secret".equals(properties.cliClientSecret())) {
            throw new IllegalStateException("store.cli-client-secret is left at its dev-only"
                    + " default — set STORE_CLI_CLIENT_SECRET to a strong random value before"
                    + " running outside local/dev/test profiles");
        }
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            String url = environment.getProperty("spring.datasource.url", "");
            if (url.startsWith("jdbc:h2:")) {
                String allow = environment.getProperty(
                        "store.allow-embedded-h2",
                        System.getenv(ALLOW_EMBEDDED_H2_ENV) == null ? "false"
                                : System.getenv(ALLOW_EMBEDDED_H2_ENV));
                if (!"true".equalsIgnoreCase(allow)) {
                    log.error("prod profile is running on the embedded H2 fallback database");
                    throw new IllegalStateException("The prod profile must not run on the"
                            + " default embedded H2 database — configure spring.datasource.*"
                            + " (PostgreSQL), or set STORE_ALLOW_EMBEDDED_H2=true to explicitly"
                            + " accept an embedded single-node database for this deployment");
                }
                log.warn("prod profile runs on embedded H2 with explicit acknowledgment"
                        + " ({}=true)", ALLOW_EMBEDDED_H2_ENV);
            }
        }
    }
}
