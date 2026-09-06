package dev.infinia.store.infrastructure.persistence;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Slice-test anchor for store-infrastructure (the module has no
 * {@code @SpringBootApplication}); {@code @DataJpaTest} looks it up from the
 * test's package. Entities and repositories both live in sub-packages.
 */
@SpringBootConfiguration
@EntityScan("dev.infinia.store.infrastructure.persistence.entity")
@EnableJpaRepositories("dev.infinia.store.infrastructure.persistence.repository")
class InfrastructurePersistenceTestConfig {
}
