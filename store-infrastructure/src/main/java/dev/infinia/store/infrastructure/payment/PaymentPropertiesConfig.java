package dev.infinia.store.infrastructure.payment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the payment-gateway credential records (infrastructure properties
 * follow the BlobStorageConfig precedent: the application's
 * {@code @ConfigurationPropertiesScan} only covers its own package, so this
 * module enables its own records). The active adapter is selected by
 * PaymentGatewayConfig in store-application.
 */
@Configuration
@EnableConfigurationProperties({BmacProperties.class, EpayProperties.class,
        XunhupayProperties.class})
public class PaymentPropertiesConfig {
}
