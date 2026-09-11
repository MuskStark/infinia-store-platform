package dev.infinia.store.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * XunHuPay (虎皮椒) gateway credentials — the personal-developer payment channel.
 * WeChat and Alipay are separate apps on the platform, each with its own
 * appid/appsecret pair; configure whichever channels were approved. With no
 * credentials configured the purchase flow degrades gracefully: order creation
 * reports {@code payment_not_configured} and nothing else changes.
 */
@ConfigurationProperties(prefix = "store.pay.xunhu")
public record XunhupayProperties(
        String apiBase,
        String wechatAppid,
        String wechatAppsecret,
        String alipayAppid,
        String alipayAppsecret) {

    public XunhupayProperties {
        apiBase = apiBase == null || apiBase.isBlank()
                ? "https://api.xunhupay.com" : apiBase.trim();
        wechatAppid = normalize(wechatAppid);
        wechatAppsecret = normalize(wechatAppsecret);
        alipayAppid = normalize(alipayAppid);
        alipayAppsecret = normalize(alipayAppsecret);
        if (wechatAppid.isEmpty() != wechatAppsecret.isEmpty()) {
            throw new IllegalStateException("store.pay.xunhu.wechat-appid and "
                    + "wechat-appsecret must be supplied together");
        }
        if (alipayAppid.isEmpty() != alipayAppsecret.isEmpty()) {
            throw new IllegalStateException("store.pay.xunhu.alipay-appid and "
                    + "alipay-appsecret must be supplied together");
        }
    }

    /** True when at least one channel has full credentials. */
    public boolean configured() {
        return !wechatAppid.isEmpty() || !alipayAppid.isEmpty();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
