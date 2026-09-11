package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.domain.port.PaymentGateway;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Buy Me a Coffee adapter basics: the buyer is simply sent to the plan's
 * hosted checkout link (a priced Extra) or the creator page — there is no
 * cashier session to build — and the JSON webhook is consumed by the dedicated
 * BMC endpoint, so parseNotify never verifies anything.
 */
class BmacGatewayTest {

    private final BmacGateway gateway = new BmacGateway(
            new BmacProperties("https://buymeacoffee.com/infinia", "whsec-123", null));

    @Test
    void configuredOnlyWithPageAndSecret() {
        assertTrue(new BmacProperties("https://x.example", "s", null).configured());
        assertFalse(new BmacProperties(null, null, null).configured());
        // Half-configured is a boot failure, not a silently dormant gateway.
        assertThrows(IllegalStateException.class,
                () -> new BmacProperties("https://x.example", null, null));
        assertThrows(IllegalStateException.class,
                () -> new BmacProperties("", "s", null));
        assertThrows(IllegalStateException.class,
                () -> new BmacProperties("buymeacoffee.com/infinia", "s", null));
    }

    @Test
    void planExtraLinkWinsOverThePlainPage() {
        PaymentGateway.PaymentCreated viaExtra = gateway.createPayment(
                new PaymentGateway.PaymentRequest("MEM1", 300, "Infinia Level 1", "BMAC",
                        "https://store.example.com/api/v1/payments/bmac/notify",
                        "https://store.example.com/membership/result", "https://buymeacoffee.com/infinia/extras/worker-30d"));
        assertEquals("https://buymeacoffee.com/infinia/extras/worker-30d", viaExtra.payUrl());

        PaymentGateway.PaymentCreated viaPage = gateway.createPayment(
                new PaymentGateway.PaymentRequest("MEM1", 300, "Infinia Level 1", "BMAC",
                        "https://store.example.com/api/v1/payments/bmac/notify",
                        "https://store.example.com/membership/result", null));
        assertEquals("https://buymeacoffee.com/infinia", viaPage.payUrl());
    }

    @Test
    void protocolFields() {
        assertEquals(java.util.List.of("BMAC"), gateway.supportedChannels());
        assertEquals("/api/v1/payments/bmac/notify", gateway.notifyPath());
        assertFalse(gateway.parseNotify(java.util.Map.of("x", "y")).verified());
    }
}
