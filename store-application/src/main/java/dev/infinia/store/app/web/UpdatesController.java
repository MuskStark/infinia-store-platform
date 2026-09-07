package dev.infinia.store.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Update-feed surface (design §8.4). Anonymous by design.
 *
 * <p>{@code GET /api/v1/updates/app} is RESERVED (audit 3.5): it was originally
 * documented as "field-compatible with the FengYu host {@code UpdateInfo}
 * model", which never held — the desktop deb updater reads the electron-updater
 * generic feed ({@code /fengyu-updates/deb/latest-linux.yml}, see
 * {@link FengYuUpdateFeedController}), the Windows portable updater reads the
 * GitHub-releases mirror ({@code /api/v1/compat/fengyu/fengyu-releases/...}), and
 * the portable-web backend checks GitHub directly. No shipped client consumes
 * this endpoint, and its historic response fields (latestVersion / mandatory /
 * rollout) match none of the live contracts. Rather than serve a shape nobody
 * maintains against a real client, the endpoint deliberately refuses with
 * 501 so an accidental integration fails loudly; reviving it requires wiring it
 * to an actual client contract first.
 */
@RestController
@RequestMapping("/api/v1/updates")
public class UpdatesController {

    @GetMapping("/app")
    public ResponseEntity<ProblemDetail> app(HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_IMPLEMENTED,
                "This update-feed endpoint is reserved and has no consumer: the desktop"
                        + " deb feed lives at /fengyu-updates/deb/latest-linux.yml and the"
                        + " Windows portable feed at /api/v1/compat/fengyu/fengyu-releases"
                        + "/api/releases/latest");
        problem.setType(URI.create("https://store.infinia.dev/problems/reserved"));
        problem.setTitle("Endpoint reserved");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", "reserved");
        String traceId = org.slf4j.MDC.get("traceId");
        problem.setProperty("traceId", traceId == null ? "unset" : traceId);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(problem);
    }
}
