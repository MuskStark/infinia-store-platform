package dev.infinia.store.app;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.port.PaymentGateway;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stub payment gateway for membership-flow integration tests: records created
 * cashier sessions and "signs" notifies with SHA-256 over
 * {@code orderNo|amountFen|gatewayTradeNo|stub-secret} so the public notify
 * endpoint's verification path runs for real. {@code failOrderNo} simulates an
 * upstream outage for one order.
 */
public final class MembershipTestSupport {

    public static final String STUB_SECRET = "stub-secret";

    private MembershipTestSupport() {}

    @TestConfiguration
    public static class StubGatewayConfig {

        @Bean
        @Primary
        public StubPaymentGateway stubPaymentGateway() {
            return new StubPaymentGateway();
        }
    }

    public static final class StubPaymentGateway implements PaymentGateway {

        public final List<PaymentRequest> requests = new CopyOnWriteArrayList<>();
        public final Map<String, PaymentCreated> created = new ConcurrentHashMap<>();
        public volatile String failOrderNo;

        @Override
        public List<String> supportedChannels() {
            return List.of("WECHAT", "ALIPAY");
        }

        @Override
        public String notifyPath() {
            return "/api/v1/payments/stub/notify";
        }

        @Override
        public PaymentCreated createPayment(PaymentRequest request) {
            requests.add(request);
            if (request.orderNo().equals(failOrderNo)) {
                throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                        "Simulated gateway outage for " + request.orderNo());
            }
            PaymentCreated result = new PaymentCreated(
                    "https://pay.stub.example/cashier/" + request.orderNo());
            created.put(request.orderNo(), result);
            return result;
        }

        @Override
        public NotifyResult parseNotify(Map<String, String> params) {
            String orderNo = params.getOrDefault("orderNo", "");
            long amountFen;
            try {
                amountFen = Long.parseLong(params.getOrDefault("amountFen", "-1"));
            } catch (NumberFormatException e) {
                amountFen = -1;
            }
            String tradeNo = params.getOrDefault("gatewayTradeNo", "");
            String sign = params.getOrDefault("sign", "");
            boolean verified = !orderNo.isBlank() && amountFen >= 0 && !tradeNo.isBlank()
                    && sign.equals(sign(orderNo, amountFen, tradeNo));
            return new NotifyResult(verified, verified, orderNo, amountFen, tradeNo);
        }

        /** The signed param set a "gateway" would POST to the notify endpoint. */
        public Map<String, String> notifyFor(String orderNo, long amountFen, String tradeNo) {
            return Map.of(
                    "orderNo", orderNo,
                    "amountFen", String.valueOf(amountFen),
                    "gatewayTradeNo", tradeNo,
                    "sign", sign(orderNo, amountFen, tradeNo));
        }

        public Map<String, String> tamperedNotify(String orderNo, long amountFen, String tradeNo) {
            return Map.of(
                    "orderNo", orderNo,
                    "amountFen", String.valueOf(amountFen),
                    "gatewayTradeNo", tradeNo,
                    "sign", "dead" + "beef".repeat(14));
        }

        private static String sign(String orderNo, long amountFen, String tradeNo) {
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(sha256.digest(
                        (orderNo + "|" + amountFen + "|" + tradeNo + "|" + STUB_SECRET)
                                .getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
