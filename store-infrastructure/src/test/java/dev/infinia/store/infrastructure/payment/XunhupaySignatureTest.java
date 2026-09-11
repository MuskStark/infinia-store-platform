package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.domain.port.PaymentGateway;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * XunHuPay (虎皮椒) signing semantics: the documented scheme (non-empty params
 * except {@code hash}, ASCII key order, raw values, appsecret appended
 * directly, MD5 lowercase hex) and the notify verification built on it. The
 * expected digests below were computed independently of the implementation.
 */
class XunhupaySignatureTest {

    private static final String SECRET = "secret123";

    private final XunhupayGateway gateway = new XunhupayGateway(
            new XunhupayProperties(null, "test-appid", SECRET, "", ""),
            new ObjectMapper());

    @Test
    void signSkipsEmptyValuesAndHashAndSortsByAscii() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("total_fee", "6.00");
        params.put("hash", "should-be-excluded");
        params.put("appid", "test-appid");
        params.put("return_url", "");
        params.put("nonce_str", "abc123");
        // appid < nonce_str < total_fee; empty return_url and hash excluded;
        // appsecret appended with no separator.
        assertEquals("89768477fa051a47388eed37a4cd060d",
                XunhupayGateway.sign(params, SECRET));
    }

    @Test
    void fenAndYuanRoundTrip() {
        assertEquals("6.00", XunhupayGateway.fenToYuan(600));
        assertEquals("16.05", XunhupayGateway.fenToYuan(1605));
        assertEquals(600, XunhupayGateway.yuanToFenOrZero("6.00"));
        assertEquals(1605, XunhupayGateway.yuanToFenOrZero("16.05"));
        assertEquals(600, XunhupayGateway.yuanToFenOrZero("6"));
        assertEquals(0, XunhupayGateway.yuanToFenOrZero("not-a-number"));
        assertEquals(0, XunhupayGateway.yuanToFenOrZero(""));
    }

    @Test
    void parseNotifyAcceptsSignedPaymentAndRejectsTampering() {
        Map<String, String> notify = new LinkedHashMap<>();
        notify.put("trade_order_id", "MEM20260911TEST01");
        notify.put("total_fee", "16.00");
        notify.put("transaction_id", "XH202609110001");
        notify.put("appid", "test-appid");
        notify.put("status", "OD");
        notify.put("time", "1700000000");
        notify.put("hash", XunhupayGateway.sign(notify, SECRET));

        PaymentGateway.NotifyResult ok = gateway.parseNotify(notify);
        assertTrue(ok.verified());
        assertTrue(ok.paid());
        assertEquals("MEM20260911TEST01", ok.orderNo());
        assertEquals(1600, ok.amountFen());
        assertEquals("XH202609110001", ok.gatewayTradeNo());

        // Wrong signature: reject outright, never trust the payload.
        Map<String, String> tampered = new LinkedHashMap<>(notify);
        tampered.put("total_fee", "0.01");
        PaymentGateway.NotifyResult bad = gateway.parseNotify(tampered);
        assertFalse(bad.verified());

        // Signed but a non-payment status (refund states): acknowledge, no apply.
        Map<String, String> refunded = new LinkedHashMap<>(notify);
        refunded.put("status", "CD");
        refunded.put("hash", XunhupayGateway.sign(refunded, SECRET));
        PaymentGateway.NotifyResult notPaid = gateway.parseNotify(refunded);
        assertTrue(notPaid.verified());
        assertFalse(notPaid.paid());

        // An appid we never configured cannot verify even with a valid-looking hash.
        Map<String, String> stranger = new LinkedHashMap<>(notify);
        stranger.put("appid", "someone-elses-app");
        stranger.put("hash", XunhupayGateway.sign(stranger, SECRET));
        assertFalse(gateway.parseNotify(stranger).verified());
    }

    @Test
    void channelsFollowConfiguredCredentials() {
        assertEquals(java.util.List.of("WECHAT"), gateway.supportedChannels());

        XunhupayGateway both = new XunhupayGateway(
                new XunhupayProperties(null, "wx-app", "wx-secret", "ali-app", "ali-secret"),
                new ObjectMapper());
        assertEquals(java.util.List.of("WECHAT", "ALIPAY"), both.supportedChannels());

        XunhupayGateway none = new XunhupayGateway(
                new XunhupayProperties(null, null, null, null, null), new ObjectMapper());
        assertTrue(none.supportedChannels().isEmpty());
    }
}
