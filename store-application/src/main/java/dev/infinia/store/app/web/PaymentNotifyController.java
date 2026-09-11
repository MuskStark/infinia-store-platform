package dev.infinia.store.app.web;

import dev.infinia.store.app.service.MembershipService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Asynchronous payment callbacks. Providers POST (or GET) their signed fields
 * here and expect the literal {@code success} body to stop retrying —
 * problem+json would be retried forever, so these endpoints speak plain text
 * and answer anything unverifiable with {@code fail}. Public by design (the
 * gateway cannot authenticate); one path per protocol:
 *
 * <ul>
 *   <li>{@code /api/v1/payments/epay/notify} — Epay protocol (易支付协议:
 *       V免付 / 彩虹易支付 / compatible providers), signature per that spec;</li>
 *   <li>{@code /api/v1/payments/xunhu/notify} — XunHuPay (虎皮椒);</li>
 *   <li>{@code /api/v1/payments/bmac/notify} — Buy Me a Coffee: a JSON body
 *       signed with HMAC-SHA256 in {@code x-signature-sha256}; acknowledged
 *       events answer success, only a bad signature answers fail.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/payments")
class PaymentNotifyController {

    private final MembershipService membership;

    PaymentNotifyController(MembershipService membership) {
        this.membership = membership;
    }

    @PostMapping(value = "/bmac/notify", produces = MediaType.TEXT_PLAIN_VALUE,
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> bmacPost(@RequestBody String rawBody,
            @RequestHeader(value = "x-signature-sha256", required = false) String signature) {
        boolean accepted = membership.handleBmacWebhook(rawBody, signature);
        return accepted
                ? ResponseEntity.ok().body("success")
                : ResponseEntity.badRequest().body("fail");
    }

    @GetMapping(value = "/epay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> epayGet(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    @PostMapping(value = "/epay/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> epayPost(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    @GetMapping(value = "/xunhu/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> xunhuGet(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    @PostMapping(value = "/xunhu/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> xunhuPost(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    private ResponseEntity<String> respond(Map<String, String> params) {
        boolean accepted = membership.handleNotify(params);
        return accepted
                ? ResponseEntity.ok().body("success")
                : ResponseEntity.badRequest().body("fail");
    }
}
