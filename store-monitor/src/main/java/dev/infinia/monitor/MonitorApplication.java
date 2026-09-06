package dev.infinia.monitor;

import dev.infinia.monitor.config.MonitorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The standalone store monitor (ADR-011). Deployed on its own host, it keeps
 * the status page alive when the store is not: it probes the store over the
 * same public URL users take, mirrors the store's own status snapshot, keeps
 * the external-reachability history and incidents in a local database, and
 * serves the status UI from this process.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(MonitorProperties.class)
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
