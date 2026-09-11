package dev.infinia.store.domain.port;

/**
 * Payment gateway port (收款网关端口): creates a cashier session for an order and
 * verifies the gateway's asynchronous payment callback. Implementations live in
 * store-infrastructure (Xunhupay for production, a mock for local development
 * and a stub for tests); the application service never knows which one is wired.
 */
public interface PaymentGateway {

    /** Charge request handed to the gateway. Money is integer fen end to end. */
    record PaymentRequest(String orderNo, long amountFen, String title, String channel,
            String notifyUrl, String returnUrl) {
    }

    /** The cashier entry to send the buyer's browser to. */
    record PaymentCreated(String payUrl) {
    }

    /**
     * A verified callback: signature already checked by the adapter.
     * {@code verified=false} means the payload failed verification and must be
     * rejected outright; {@code paid=false} covers non-payment statuses the
     * gateway may report (e.g. refund states) — acknowledge and ignore.
     */
    record NotifyResult(boolean verified, boolean paid, String orderNo, long amountFen,
            String gatewayTradeNo) {
    }

    /** Channel identifiers the configured credentials support, e.g. ["WECHAT"]. */
    java.util.List<String> supportedChannels();

    PaymentCreated createPayment(PaymentRequest request);

    NotifyResult parseNotify(java.util.Map<String, String> params);
}
