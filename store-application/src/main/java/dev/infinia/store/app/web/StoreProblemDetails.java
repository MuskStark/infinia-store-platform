package dev.infinia.store.app.web;

import dev.infinia.store.contract.error.StoreErrorCode;
import dev.infinia.store.domain.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
            Map.entry(StoreErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS),
            Map.entry(StoreErrorCode.IDEMPOTENCY_CONFLICT, HttpStatus.CONFLICT),
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
        problem.setDetail(messages.getMessage("error." + e.code.code + ".detail", null,
                e.getMessage(), LocaleContextHolder.getLocale()));
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return problem(StoreErrorCode.INTERNAL_ERROR, null, request);
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
