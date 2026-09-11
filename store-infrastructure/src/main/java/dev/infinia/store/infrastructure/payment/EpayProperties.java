package dev.infinia.store.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Epay-protocol gateway settings (易支付协议): works with any compatible
 * endpoint — a self-hosted V免付 (vmqphp: personal QR codes + the phone-side
 * monitor app, zero fees and no third-party identity verification), a
 * self-hosted 彩虹易支付, or any Epay-protocol provider. One pid/key pair
 * covers both channels ({@code type=wxpay|alipay}).
 *
 * <p>All fields empty (the default) leaves the gateway dormant; the flow then
 * falls back to the XunHuPay adapter or reports {@code payment_not_configured}.</p>
 */
@ConfigurationProperties(prefix = "store.pay.epay")
public record EpayProperties(String apiBase, String pid, String key) {

    public EpayProperties {
        apiBase = apiBase == null ? "" : apiBase.trim();
        pid = pid == null ? "" : pid.trim();
        key = key == null ? "" : key.trim();
        if (apiBase.endsWith("/")) {
            apiBase = apiBase.substring(0, apiBase.length() - 1);
        }
        // Half-configured means a typo: refuse loudly instead of silently
        // falling back to the unconfigured path.
        boolean anySet = !apiBase.isEmpty() || !pid.isEmpty() || !key.isEmpty();
        if (anySet && (apiBase.isEmpty() || pid.isEmpty() || key.isEmpty())) {
            throw new IllegalStateException("store.pay.epay requires api-base, pid and "
                    + "key together — supply all three or none");
        }
    }

    /** True when a complete endpoint configuration exists. */
    public boolean configured() {
        return !apiBase.isEmpty() && !pid.isEmpty() && !key.isEmpty();
    }
}
