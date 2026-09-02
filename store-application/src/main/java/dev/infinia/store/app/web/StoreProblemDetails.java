package dev.infinia.store.app.web;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.Map;

/**
 * Maps failures to RFC 9457 problem+json with a stable {@code code}, {@code traceId}
 * and localizable parameters (design §10.1). Messages resolve from
 * {@code errors_en.properties} / {@code errors_zh_CN.properties} using the request
 * locale, so the API is English-first with Chinese switchable via Accept-Language.
 */
@RestControllerAdvice
public class StoreProblemDetails {

    private static final Logger log = LoggerFactory.getLogger(StoreProblemDetails.class);

    private static final Map<StoreErrorCode, HttpStatus> STATUS = Map.ofEntries(
            Map.entry(StoreErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST),
            Map.entry(StoreErrorCode.INVALID_COORDINATE, HttpStatus.BAD_REQUEST),
            Map.entry(StoreErrorCode.INVALID_SEMVER, HttpStatus.BAD_REQUEST),
            Map.entry(StoreErrorCode.NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(StoreErrorCode.LISTING_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(StoreErrorCode.RELEASE_NOT_FOUND, HttpStatus.NOT_FOUND),
            Map.entry(StoreErrorCode.NAMESPACE_TAKEN, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.SLUG_TAKEN, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.EMAIL_TAKEN, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.DUPLICATE_VERSION, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.NAMESPACE_NOT_OWNED, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED),
            Map.entry(StoreErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED),
            Map.entry(StoreErrorCode.FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.ROLE_REQUIRED, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.SELF_REVIEW_FORBIDDEN, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.INVALID_STATE_TRANSITION, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.UPLOAD_NOT_COMPLETE, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.UPLOAD_EXPIRED, HttpStatus.GONE),
            Map.entry(StoreErrorCode.TICKET_INVALID, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.BEE_LEVEL_REQUIRED, HttpStatus.FORBIDDEN),
            Map.entry(StoreErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(StoreErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.UPSTREAM_DRIFTED, HttpStatus.CONFLICT),
            Map.entry(StoreErrorCode.SCAN_FAILED, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(StoreErrorCode.SIGNATURE_INVALID, HttpStatus.UNPROCESSABLE_ENTITY),
            Map.entry(StoreErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR));

    private final MessageSource messages;

    public StoreProblemDetails(MessageSource messages) {
        this.messages = messages;
    }

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ProblemDetail> domain(DomainException e, HttpServletRequest request) {
        HttpStatus status = STATUS.getOrDefault(e.code, HttpStatus.BAD_REQUEST);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("https://store.infinia.dev/problems/" + e.code.code));
        problem.setTitle(messages.getMessage("error." + e.code.code + ".title", null,
                e.code.code, LocaleContextHolder.getLocale()));
        String detail = e.getMessage();
        if (e.code == StoreErrorCode.INTERNAL_ERROR && detail != null
                && !detail.isBlank()) {
            // Internal errors keep the concrete cause — the generic text hides
            // exactly the information needed to debug delivery failures.
            problem.setDetail(detail);
        } else {
            problem.setDetail(messages.getMessage("error." + e.code.code + ".detail", null,
                    detail, LocaleContextHolder.getLocale()));
        }
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", e.code.code);
        problem.setProperty("parameters", e.params);
        problem.setProperty("traceId", traceId());
        return ResponseEntity.status(status).body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException e,
            HttpServletRequest request) {
        return problem(StoreErrorCode.VALIDATION_FAILED, e.getMessage(), request);
    }

    /**
     * Binary PUTs mislabelled as form data (curl --data-binary defaults to
     * application/x-www-form-urlencoded) used to explode in the form filter as a
     * 500. Surface an actionable 400 instead — package uploads must use
     * application/octet-stream.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e,
            HttpServletRequest request) {
        String hint = "Request body could not be read. For package uploads send "
                + "Content-Type: application/octet-stream.";
        return problem(StoreErrorCode.VALIDATION_FAILED, hint, request);
    }

    /**
     * Nothing matched the URL — no handler and no static resource. API-style
     * requests get a proper 404 problem document instead of a misleading 500;
     * browser navigations fall back to the embedded SPA so history-mode deep
     * links such as /listing/acme/tool reach its router (which renders its own
     * NotFound view for truly unknown paths).
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Object noStaticResource(NoResourceFoundException e, HttpServletRequest request) {
        String uri = request.getRequestURI();
        boolean htmlNavigation = HttpMethod.GET.matches(request.getMethod())
                && !uri.startsWith("/api/") && !uri.startsWith("/oauth2/")
                && !uri.startsWith("/actuator/")
                && acceptsHtml(request);
        if (htmlNavigation) {
            return new ModelAndView("forward:/index.html", HttpStatus.OK);
        }
        return problem(StoreErrorCode.NOT_FOUND, "No such path: " + uri, request);
    }

    private static boolean acceptsHtml(HttpServletRequest request) {
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.contains("text/html");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        String detail = e.getMessage() == null ? null
                : e.getClass().getSimpleName() + ": " + e.getMessage();
        return problem(StoreErrorCode.INTERNAL_ERROR, detail, request);
    }

    private ResponseEntity<ProblemDetail> problem(StoreErrorCode code, String detail,
            HttpServletRequest request) {
        HttpStatus status = STATUS.getOrDefault(code, HttpStatus.BAD_REQUEST);
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create("https://store.infinia.dev/problems/" + code.code));
        problem.setTitle(messages.getMessage("error." + code.code + ".title", null,
                code.code, LocaleContextHolder.getLocale()));
        if (detail != null) {
            problem.setDetail(messages.getMessage("error." + code.code + ".detail", null,
                    detail, LocaleContextHolder.getLocale()));
        }
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.code);
        problem.setProperty("traceId", traceId());
        return ResponseEntity.status(status).body(problem);
    }

    private static String traceId() {
        String traceId = org.slf4j.MDC.get("traceId");
        return traceId == null ? "unset" : traceId;
    }
}
