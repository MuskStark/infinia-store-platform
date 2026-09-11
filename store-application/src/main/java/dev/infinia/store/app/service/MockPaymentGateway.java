package dev.infinia.store.app.service;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.domain.port.PaymentGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Local-development gateway behind the {@link PaymentGateway} port: instead of
 * calling XunHuPay it hands back the built-in simulated cashier URL
 * ({@code /api/v1/payments/mock/...}) whose confirm button drives the very same
 * {@code handleNotify} path a real callback takes. Tokens are HMAC-SHA256 over
 * {@code orderNo|amountFen} with the deployment's ticket secret, so a forged
 * cashier link cannot confirm an order.
 *
 * <p>Both guards matter: the {@code local} profile keeps it out of every
 * deployment runtime, and {@code store.pay.mock-enabled=true} (default false)
 * makes even local runs opt-in. {@code @Primary} overrides the unconfigured
 * XunHuPay adapter so the whole purchase flow works without credentials.</p>
 */
@Component
@Profile("local")
@ConditionalOnProperty(prefix = "store.pay", name = "mock-enabled", havingValue = "true")
@Primary
public class MockPaymentGateway implements PaymentGateway {

    public static final String CHANNEL_MOCK = "MOCK";

    private final StoreProperties properties;

    public MockPaymentGateway(StoreProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> supportedChannels() {
        return List.of(CHANNEL_MOCK);
    }

    @Override
    public PaymentCreated createPayment(PaymentRequest request) {
        String token = token(request.orderNo(), request.amountFen());
        return new PaymentCreated(properties.baseUrl() + "/api/v1/payments/mock/"
                + request.orderNo() + "?token=" + token);
    }

    /** Mock callback shape: {@code orderNo}, {@code amountFen}, {@code token}. */
    @Override
    public NotifyResult parseNotify(Map<String, String> params) {
        String orderNo = params.getOrDefault("orderNo", "");
        long amountFen;
        try {
            amountFen = Long.parseLong(params.getOrDefault("amountFen", "-1"));
        } catch (NumberFormatException e) {
            amountFen = -1;
        }
        String token = params.getOrDefault("token", "");
        boolean verified = !orderNo.isBlank() && amountFen >= 0
                && token.equals(token(orderNo, amountFen));
        return new NotifyResult(verified, verified, orderNo, amountFen, "MOCK-" + orderNo);
    }

    public String token(String orderNo, long amountFen) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    properties.ticketSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(
                    (orderNo + "|" + amountFen).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
