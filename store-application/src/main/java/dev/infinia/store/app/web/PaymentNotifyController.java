package dev.infinia.store.app.web;

import dev.infinia.store.app.service.MembershipService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * XunHuPay's asynchronous payment callback. The gateway POSTs form fields here
 * after a payment and expects the literal {@code success} body to stop retrying
 * (up to 6 retries otherwise) — problem+json would be retried forever, so this
 * controller speaks plain text and answers anything unverifiable with
 * {@code fail}. Public by design (the gateway cannot authenticate).
 */
@RestController
@RequestMapping("/api/v1/payments/xunhu")
class PaymentNotifyController {

    private final MembershipService membership;

    PaymentNotifyController(MembershipService membership) {
        this.membership = membership;
    }

    @GetMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notifyGet(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> notifyPost(@RequestParam Map<String, String> params) {
        return respond(params);
    }

    private ResponseEntity<String> respond(Map<String, String> params) {
        boolean accepted = membership.handleNotify(params);
        return accepted
                ? ResponseEntity.ok().body("success")
                : ResponseEntity.badRequest().body("fail");
    }
}
