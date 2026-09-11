package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.domain.port.PaymentGateway;

import java.util.List;
import java.util.Map;

/**
 * Buy Me a Coffee adapter: BMC has no order-creating API — the buyer is simply
 * sent to the creator's page, ideally the plan's priced Extra
 * ({@code productUrl}); the plain page URL is the fallback. There is also no
 * order metadata passthrough, so the async webhook (HMAC-SHA256 signed, header
 * {@code x-signature-sha256}) is consumed by the dedicated
 * {@code /api/v1/payments/bmac/notify} endpoint which matches the payment to a
 * pending order by supporter email + amount
 * (MembershipService#handleBmacWebhook) — {@link #parseNotify} is therefore
 * never used and reports everything as unverifiable.
 *
 * <p>Instantiated by PaymentGatewayConfig (not a component) so exactly one
 * adapter family backs the {@link PaymentGateway} port per deployment.</p>
 */
public class BmacGateway implements PaymentGateway {

    public static final String CHANNEL_BMAC = "BMAC";

    private final BmacProperties properties;

    public BmacGateway(BmacProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> supportedChannels() {
        return List.of(CHANNEL_BMAC);
    }

    @Override
    public String notifyPath() {
        return "/api/v1/payments/bmac/notify";
    }

    @Override
    public PaymentCreated createPayment(PaymentRequest request) {
        // A per-plan Extra link prices the product on BMC's side; without one
        // the supporter picks the amount themselves on the page.
        String url = request.productUrl() == null || request.productUrl().isBlank()
                ? properties.pageUrl() : request.productUrl();
        return new PaymentCreated(url);
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params) {
        // BMC callbacks are JSON bodies handled by handleBmacWebhook; nothing
        // arriving here can be trusted.
        return new NotifyResult(false, false, null, 0, null);
    }
}
