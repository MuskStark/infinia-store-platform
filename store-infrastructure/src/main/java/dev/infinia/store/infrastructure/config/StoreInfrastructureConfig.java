package dev.infinia.store.infrastructure.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Infrastructure wiring: JPA entities/repositories, blob storage, outbox relay,
 * credential hashing (design §5.1).
 */
@Configuration
@EnableScheduling
@EnableTransactionManagement
@EntityScan("dev.infinia.store.infrastructure.persistence.entity")
@EnableJpaRepositories("dev.infinia.store.infrastructure.persistence.repository")
@ComponentScan("dev.infinia.store.infrastructure")
public class StoreInfrastructureConfig {
}
