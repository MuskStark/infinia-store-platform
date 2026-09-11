package dev.infinia.store.infrastructure.payment;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import dev.infinia.store.domain.port.PaymentGateway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * XunHuPay (虎皮椒) adapter — the personal-developer payment channel behind the
 * {@link PaymentGateway} port. Speaks the documented protocol: form POST to
 * {@code /payment/do.html} with an MD5 {@code hash} over the non-empty params
 * sorted by key (raw values, no URL encoding) with the appsecret appended
 * directly; the cashier page comes back in {@code url}; the async callback
 * repeats the same signing scheme and must be answered with the literal
 * {@code success}.
 *
 * <p>Instantiated by PaymentGatewayConfig (not a component) so exactly one
 * adapter family backs the {@link PaymentGateway} port per deployment. The
 * platform charges an account-opening fee, so deployments that did not pay it
 * simply leave its credentials unset — selection then falls through to the
 * Epay adapter.</p>
 */
public class XunhupayGateway implements PaymentGateway {

    public static final String CHANNEL_WECHAT = "WECHAT";
    public static final String CHANNEL_ALIPAY = "ALIPAY";

    /** Callback status for a completed payment (OD = order done). */
    static final String STATUS_PAID = "OD";

    private static final String PLUGINS = "infinia-store";

    private final XunhupayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public XunhupayGateway(XunhupayProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public List<String> supportedChannels() {
        List<String> channels = new ArrayList<>();
        if (!properties.wechatAppid().isEmpty()) {
            channels.add(CHANNEL_WECHAT);
        }
        if (!properties.alipayAppid().isEmpty()) {
            channels.add(CHANNEL_ALIPAY);
        }
        return channels;
    }

    @Override
    public String notifyPath() {
        return "/api/v1/payments/xunhu/notify";
    }

    @Override
    public PaymentCreated createPayment(PaymentRequest request) {
        String appid = appidOf(request.channel());
        String appsecret = appsecretOf(request.channel());
        Map<String, String> params = new LinkedHashMap<>();
        params.put("version", "1.1");
        params.put("appid", appid);
        params.put("plugins", PLUGINS);
        params.put("trade_order_id", request.orderNo());
        params.put("total_fee", fenToYuan(request.amountFen()));
        params.put("title", request.title());
        params.put("time", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("notify_url", request.notifyUrl());
        params.put("return_url", request.returnUrl());
        params.put("nonce_str", UUID.randomUUID().toString().replace("-", ""));
        params.put("hash", sign(params, appsecret));

        JsonNode response = postForm(properties.apiBase() + "/payment/do.html", params);
        int errcode = response.path("errcode").asInt(-1);
        if (errcode != 0) {
            throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                    "XunHuPay rejected order " + request.orderNo() + ": errcode=" + errcode
                            + " errmsg=" + response.path("errmsg").asText(""));
        }
        Map<String, String> responseParams = new LinkedHashMap<>();
        response.properties().forEach(field -> {
            String value = field.getValue().asText("");
            if (!value.isEmpty()) {
                responseParams.put(field.getKey(), value);
            }
        });
        String expectedHash = sign(responseParams, appsecret);
        if (!expectedHash.equals(response.path("hash").asText(""))) {
            throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                    "XunHuPay response hash mismatch for order " + request.orderNo());
        }
        // `url` is the cashier entry (PC + mobile); url_qrcode is the PC-native
        // fallback. Redirecting to `url` keeps one flow for every device.
        String payUrl = response.path("url").asText("");
        if (payUrl.isBlank()) {
            payUrl = response.path("url_qrcode").asText("");
        }
        if (payUrl.isBlank()) {
            throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                    "XunHuPay response carried no payment URL for order " + request.orderNo());
        }
        return new PaymentCreated(payUrl);
    }

    @Override
    public NotifyResult parseNotify(Map<String, String> params) {
        String appid = params.getOrDefault("appid", "");
        String appsecret;
        if (properties.wechatAppid().equals(appid)) {
            appsecret = properties.wechatAppsecret();
        } else if (properties.alipayAppid().equals(appid)) {
            appsecret = properties.alipayAppsecret();
        } else {
            appsecret = "";
        }
        if (appsecret.isEmpty()) {
            return new NotifyResult(false, false, null, 0, null);
        }
        String hash = params.getOrDefault("hash", "");
        if (hash.isBlank() || !sign(params, appsecret).equals(hash)) {
            return new NotifyResult(false, false, null, 0, null);
        }
        boolean paid = STATUS_PAID.equals(params.getOrDefault("status", ""));
        return new NotifyResult(true, paid,
                params.get("trade_order_id"),
                yuanToFenOrZero(params.getOrDefault("total_fee", "")),
                params.get("transaction_id"));
    }

    private JsonNode postForm(String url, Map<String, String> params) {
        String form = params.entrySet().stream()
                .map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                        "XunHuPay endpoint " + url + " responded " + response.statusCode());
            }
            return mapper.readTree(response.body());
        } catch (IOException e) {
            throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                    "XunHuPay call failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DomainException(StoreErrorCode.PAYMENT_GATEWAY_ERROR,
                    "XunHuPay call interrupted");
        }
    }

    private String appidOf(String channel) {
        if (CHANNEL_WECHAT.equals(channel) && !properties.wechatAppid().isEmpty()) {
            return properties.wechatAppid();
        }
        if (CHANNEL_ALIPAY.equals(channel) && !properties.alipayAppid().isEmpty()) {
            return properties.alipayAppid();
        }
        throw new DomainException(StoreErrorCode.VALIDATION_FAILED,
                "Unsupported payment channel: " + channel + " (configured: "
                        + supportedChannels() + ")");
    }

    private String appsecretOf(String channel) {
        return CHANNEL_WECHAT.equals(channel)
                ? properties.wechatAppsecret() : properties.alipayAppsecret();
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
     * The documented signing scheme: non-empty params except {@code hash}, ASCII
     * ascending by key, joined {@code k=v&k=v} with RAW values (no URL
     * encoding), appsecret appended directly, MD5 lowercase hex.
     */
    static String sign(Map<String, String> params, String appsecret) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("hash".equals(entry.getKey())) {
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
        joined.append(appsecret);
        return md5Hex(joined.toString());
    }

    static String md5Hex(String input) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(
                    md5.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
