package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.domain.port.PaymentGateway;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Epay-protocol adapter (易支付协议) — the de-facto standard spoken by V免付
 * (vmqphp, self-hosted personal QR codes) and 彩虹易支付. Payment creation is
 * just a signed browser redirect: {@code {api-base}/submit.php?...&sign=...&sign_type=MD5}
 * shows the provider's cashier, so no server-to-server round-trip (and no
 * outage window) happens at order time. The async callback repeats the same
 * signing scheme with {@code trade_status=TRADE_SUCCESS} and must be answered
 * with the literal {@code success}.
 *
 * <p>Instantiated by PaymentGatewayConfig (not a component) so exactly one
 * adapter family backs the {@link PaymentGateway} port per deployment.</p>
 */
public class EpayGateway implements PaymentGateway {

    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_ALIPAY = "ALIPAY";

    static final String STATUS_PAID = "TRADE_SUCCESS";

    private final EpayProperties properties;

    public EpayGateway(EpayProperties properties) {
        this.properties = properties;
    }

    @Override
    public List<String> supportedChannels() {
        // One pid/key pair serves both channels; the cashier page picks the
        // wallet from the `type` parameter.
        return List.of(CHANNEL_WECHAT, CHANNEL_ALIPAY);
    }

    @Override
    public String notifyPath() {
        return "/api/v1/payments/epay/notify";
    }

    @Override
    public PaymentCreated createPayment(PaymentRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("pid", properties.pid());
        params.put("type", CHANNEL_WECHAT.equals(request.channel()) ? "wxpay" : "alipay");
        params.put("out_trade_no", request.orderNo());
        params.put("notify_url", request.notifyUrl());
        params.put("return_url", request.returnUrl());
        params.put("name", request.title());
        params.put("money", fenToYuan(request.amountFen()));
        String query = params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"))
                + "&sign=" + sign(params, properties.key())
                + "&sign_type=MD5";
        return new PaymentCreated(properties.apiBase() + "/submit.php?" + query);
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params) {
        String expectedPid = params.getOrDefault("pid", "");
        if (!properties.pid().equals(expectedPid)) {
            return new NotifyResult(false, false, null, 0, null);
        }
        String sign = params.getOrDefault("sign", "");
        if (sign.isBlank() || !sign(params, properties.key()).equals(sign)) {
            return new NotifyResult(false, false, null, 0, null);
        }
        boolean paid = STATUS_PAID.equals(params.getOrDefault("trade_status", ""));
        return new NotifyResult(true, paid,
                params.get("out_trade_no"),
                yuanToFenOrZero(params.getOrDefault("money", "")),
                params.get("trade_no"));
    }

    static String fenToYuan(long fen) {
        return BigDecimal.valueOf(fen).movePointLeft(2).toPlainString();
    }

    static long yuanToFenOrZero(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return 0;
        }
        try {
            return new BigDecimal(yuan.trim()).movePointRight(2).longValue();
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * The protocol's signing scheme: every non-empty param except {@code sign}
     * and {@code sign_type}, ASCII ascending by key, joined {@code k=v&k=v}
     * with RAW values, the merchant key appended directly, MD5 lowercase hex.
     */
    static String sign(Map<String, String> params, String key) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("sign".equals(entry.getKey()) || "sign_type".equals(entry.getKey())) {
                continue;
            }
            String value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            sorted.put(entry.getKey(), value);
        }
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            if (joined.length() > 0) {
                joined.append('&');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        joined.append(key);
        return md5Hex(joined.toString());
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return java.util.HexFormat.of().formatHex(
                    md5.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
