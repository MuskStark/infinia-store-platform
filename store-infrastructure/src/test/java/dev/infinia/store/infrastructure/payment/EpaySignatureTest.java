package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.domain.port.PaymentGateway;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Epay-protocol signing semantics (易支付协议): non-empty params except
 * {@code sign}/{@code sign_type}, ASCII key order, raw values, merchant key
 * appended directly, MD5 lowercase hex — and the notify verification built on
 * it. The expected digest below was computed independently of the
 * implementation.
 */
class EpaySignatureTest {

    private static final String KEY = "epay-key-123";

    private final EpayGateway gateway = new EpayGateway(
            new EpayProperties("https://pay.example.com/", "1001", KEY));

    @Test
    void signSkipsEmptyValuesAndSignFieldsAndSortsByAscii() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("return_url", "http://localhost:8080/membership/result?orderNo=MEM260911TEST01");
        params.put("money", "6.00");
        params.put("sign_type", "MD4"); // excluded like sign itself
        params.put("name", "Test order");
        params.put("notify_url", "http://localhost:8080/api/v1/payments/epay/notify");
        params.put("out_trade_no", "MEM260911TEST01");
        params.put("pid", "1001");
        params.put("type", "wxpay");
        params.put("sign", "excluded");
        // money < name < notify_url < out_trade_no < pid < return_url < type
        assertEquals("03de7dcc6538397155b70322c84ef969", EpayGateway.sign(params, KEY));
    }

    @Test
    void createPaymentBuildsSignedSubmitRedirect() {
        PaymentGateway.PaymentCreated created = gateway.createPayment(
                new PaymentGateway.PaymentRequest("MEM1", 1600, "Infinia Level", "WECHAT",
                        "https://store.example.com/api/v1/payments/epay/notify",
                        "https://store.example.com/membership/result?orderNo=MEM1", null));
        String url = created.payUrl();
        assertTrue(url.startsWith("https://pay.example.com/submit.php?"), url);
        assertTrue(url.contains("out_trade_no=MEM1"));
        assertTrue(url.contains("type=wxpay"));
        assertTrue(url.contains("money=16.00"));
        assertTrue(url.contains("sign_type=MD5"));
        // The signature covers the RAW (unencoded) values, so it must be
        // computable from the decoded params again.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", "1001");
        params.put("type", "wxpay");
        params.put("out_trade_no", "MEM1");
        params.put("notify_url", "https://store.example.com/api/v1/payments/epay/notify");
        params.put("return_url", "https://store.example.com/membership/result?orderNo=MEM1");
        params.put("name", "Infinia Level");
        params.put("money", "16.00");
        String expected = EpayGateway.sign(params, KEY);
        assertTrue(url.contains("sign=" + expected));
    }

    @Test
    void parseNotifyAcceptsSignedPaymentAndRejectsTampering() {
        Map<String, String> notify = new LinkedHashMap<>();
        notify.put("pid", "1001");
        notify.put("trade_no", "202609110001");
        notify.put("out_trade_no", "MEM260911TEST01");
        notify.put("type", "wxpay");
        notify.put("name", "Test order");
        notify.put("money", "16.00");
        notify.put("trade_status", "TRADE_SUCCESS");
        notify.put("sign_type", "MD5");
        notify.put("sign", EpayGateway.sign(notify, KEY));

        PaymentGateway.NotifyResult ok = gateway.parseNotify(notify);
        assertTrue(ok.verified());
        assertTrue(ok.paid());
        assertEquals("MEM260911TEST01", ok.orderNo());
        assertEquals(1600, ok.amountFen());
        assertEquals("202609110001", ok.gatewayTradeNo());

        Map<String, String> tampered = new LinkedHashMap<>(notify);
        tampered.put("money", "0.01");
        assertFalse(gateway.parseNotify(tampered).verified());

        Map<String, String> strangerPid = new LinkedHashMap<>(notify);
        strangerPid.put("pid", "9999");
        strangerPid.put("sign", EpayGateway.sign(strangerPid, KEY));
        assertFalse(gateway.parseNotify(strangerPid).verified(),
                "a foreign pid cannot verify against our key");
    }

    @Test
    void channelsAndProtocolFields() {
        assertEquals(java.util.List.of("WECHAT", "ALIPAY"), gateway.supportedChannels());
        assertEquals("/api/v1/payments/epay/notify", gateway.notifyPath());
        assertEquals(0, EpayGateway.yuanToFenOrZero(""));
        assertEquals(600, EpayGateway.yuanToFenOrZero("6"));
        assertEquals(1605, EpayGateway.yuanToFenOrZero("16.05"));
    }
}
