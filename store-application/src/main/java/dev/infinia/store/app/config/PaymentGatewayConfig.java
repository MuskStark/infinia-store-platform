package dev.infinia.store.app.config;

import dev.infinia.store.app.service.MockPaymentGateway;
import dev.infinia.store.domain.port.PaymentGateway;
import dev.infinia.store.infrastructure.payment.BmacGateway;
import dev.infinia.store.infrastructure.payment.BmacProperties;
import dev.infinia.store.infrastructure.payment.EpayGateway;
import dev.infinia.store.infrastructure.payment.EpayProperties;
import dev.infinia.store.infrastructure.payment.XunhupayGateway;
import dev.infinia.store.infrastructure.payment.XunhupayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Picks the ONE payment adapter backing the {@link PaymentGateway} port:
 *
 * <ol>
 *   <li>the local-only simulated cashier ({@code store.pay.mock-enabled=true}
 *       AND the local profile — unreachable in any deployment runtime);</li>
 *   <li>Buy Me a Coffee when its page URL + webhook secret are configured —
 *       free to start, no opening fee; webhook-matched by email + amount;</li>
 *   <li>the Epay-protocol adapter (易支付协议: self-hosted V免付 / 彩虹易支付 or
 *       any compatible provider) when its pid/key/api-base are configured —
 *       the zero-fee domestic option;</li>
 *   <li>the XunHuPay (虎皮椒) adapter as the fallback, active only when its
 *       credentials are set; with nothing configured order creation reports
 *       {@code payment_not_configured} and the rest of the store is
 *       unaffected.</li>
 * </ol>
 *
 * <p>Exactly one bean exists, so tests override it cleanly with a single
 * {@code @Primary} stub.</p>
 */
@Configuration
public class PaymentGatewayConfig {

    @Bean
    public PaymentGateway paymentGateway(BmacProperties bmac, EpayProperties epay,
            XunhupayProperties xunhu, StoreProperties store, ObjectMapper mapper,
            Environment environment) {
        if (store.pay().mockEnabled() && environment.acceptsProfiles(Profiles.of("local"))) {
            return new MockPaymentGateway(store);
        }
        if (bmac.configured()) {
            return new BmacGateway(bmac);
        }
        if (epay.configured()) {
            return new EpayGateway(epay);
        }
        return new XunhupayGateway(xunhu, mapper);
    }
}
