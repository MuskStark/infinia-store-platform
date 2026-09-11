package dev.infinia.store.infrastructure.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the XunHuPay credentials record (infrastructure properties follow
 * the BlobStorageConfig precedent: the application's {@code @ConfigurationPropertiesScan}
 * only covers its own package, so this module enables its own records).
 */
@Configuration
@EnableConfigurationProperties(XunhupayProperties.class)
public class XunhupayConfig {
}
