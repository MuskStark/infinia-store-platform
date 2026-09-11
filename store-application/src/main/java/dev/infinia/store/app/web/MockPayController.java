package dev.infinia.store.app.web;

import dev.infinia.store.app.service.MembershipService;
import dev.infinia.store.app.service.MockPaymentGateway;
import dev.infinia.store.domain.port.BillingRepositories;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The built-in simulated cashier (模拟收银台) for local development: shows the
 * order and a confirm button whose POST drives the same notify path a real
 * gateway callback takes. Registered only with the local profile plus
 * {@code store.pay.mock-enabled=true}; the public security rule for
 * {@code /api/v1/payments/mock/**} is inert everywhere else because this
 * controller bean does not exist and unknown API paths 404.
 */
@Controller
@Profile("local")
@ConditionalOnProperty(prefix = "store.pay", name = "mock-enabled", havingValue = "true")
@RequestMapping("/api/v1/payments/mock")
public class MockPayController {

    private final BillingRepositories.MembershipOrderRepository orders;
    private final MockPaymentGateway gateway;
    private final MembershipService membership;

    public MockPayController(BillingRepositories.MembershipOrderRepository orders,
            MockPaymentGateway gateway, MembershipService membership) {
        this.orders = orders;
        this.gateway = gateway;
        this.membership = membership;
    }

    @GetMapping(value = "/{orderNo}", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public ResponseEntity<String> cashier(@PathVariable String orderNo,
            @RequestParam String token) {
        var order = orders.findByOrderNo(orderNo).orElse(null);
        if (order == null || !gateway.token(orderNo, order.priceFen).equals(token)) {
            return ResponseEntity.status(404).contentType(MediaType.TEXT_PLAIN)
                    .body("Unknown or forged mock order");
        }
        String yuan = BigDecimal.valueOf(order.priceFen).movePointLeft(2).toPlainString();
        String html = """
                <!doctype html><html lang="zh"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>模拟收银台 Mock Cashier</title>
                <style>
                  body{font-family:system-ui,sans-serif;background:#f5f5f4;display:flex;
                       justify-content:center;padding-top:8vh;margin:0}
                  .card{background:#fff;border-radius:16px;padding:32px 40px;text-align:center;
                        box-shadow:0 8px 30px rgba(0,0,0,.08);max-width:420px}
                  .amount{font-size:40px;font-weight:700;margin:16px 0}
                  button{background:#10b981;color:#fff;border:0;border-radius:999px;
                         padding:12px 40px;font-size:16px;cursor:pointer;margin-top:8px}
                  .muted{color:#78716c;font-size:13px;margin-top:16px}
                </style></head><body><div class="card">
                <h2>🧪 模拟收银台 · Mock Cashier</h2>
                <p>Infinia Level %d · %d 天</p>
                <div class="amount">¥%s</div>
                <form method="post" action="/api/v1/payments/mock/%s/confirm">
                  <input type="hidden" name="token" value="%s">
                  <button type="submit">模拟支付成功 · Simulate payment</button>
                </form>
                <p class="muted">仅本地开发 · local development only</p>
                </div></body></html>
                """.formatted(order.targetLevel, order.durationDays, yuan, orderNo, token);
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

    /** The "payment happened" button — feeds the real notify pipeline. */
    @PostMapping("/{orderNo}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String orderNo,
            @RequestParam String token) {
        var order = orders.findByOrderNo(orderNo).orElse(null);
        if (order == null || !gateway.token(orderNo, order.priceFen).equals(token)) {
            return ResponseEntity.status(404).build();
        }
        membership.handleNotify(Map.of(
                "orderNo", orderNo,
                "amountFen", String.valueOf(order.priceFen),
                "token", token));
        return ResponseEntity.status(303)
                .header(HttpHeaders.LOCATION, "/membership/result?orderNo=" + orderNo)
                .build();
    }
}
