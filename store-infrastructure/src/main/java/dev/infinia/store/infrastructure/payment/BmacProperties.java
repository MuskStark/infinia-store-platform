package dev.infinia.store.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Buy Me a Coffee (BMAC) settings — the free-to-start creator monetization
 * platform (no opening fee; BMAC keeps 5% of support). The store redirects
 * buyers to the creator's page (ideally a priced Extra linked per plan) and
 * matches the webhook's payment back by supporter email + amount.
 *
 * <p>Configure both the public page URL and the webhook Signing Secret from
 * the BMAC dashboard (Integrations → Webhooks; point the endpoint at
 * {@code https://<store>/api/v1/payments/bmac/notify} and subscribe to the
 * donation and extra_purchase events). Unset leaves the gateway dormant.</p>
 */
@ConfigurationProperties(prefix = "store.pay.bmac")
public record BmacProperties(
        String pageUrl,
        String webhookSecret,
        Integer matchWindowMinutes) {

    public BmacProperties {
        pageUrl = pageUrl == null ? "" : pageUrl.trim();
        webhookSecret = webhookSecret == null ? "" : webhookSecret.trim();
        if (pageUrl.endsWith("/")) {
            pageUrl = pageUrl.substring(0, pageUrl.length() - 1);
        }
        matchWindowMinutes = matchWindowMinutes == null || matchWindowMinutes <= 0
                ? 1440 : matchWindowMinutes;
        if (!pageUrl.isEmpty() && !pageUrl.startsWith("https://")
                && !pageUrl.startsWith("http://")) {
            throw new IllegalStateException("store.pay.bmac.page-url must be an absolute "
                    + "http(s) URL, got: " + pageUrl);
        }
        // Half-configured means a typo: refuse loudly instead of silently
        // falling back to the unconfigured path.
        if (!pageUrl.isEmpty() != !webhookSecret.isEmpty()) {
            throw new IllegalStateException("store.pay.bmac requires page-url and "
                    + "webhook-secret together — supply both or neither");
        }
    }

    /** True when the page and webhook secret are both configured. */
    public boolean configured() {
        return !pageUrl.isEmpty() && !webhookSecret.isEmpty();
    }
}
