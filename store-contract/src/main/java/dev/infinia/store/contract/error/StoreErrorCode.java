package dev.infinia.store.contract.error;

/**
 * Stable problem+json error codes (design §10.1). The {@code code} value is part of
 * the public API contract; messages are localized server-side via the message key.
 */
public enum StoreErrorCode {
    VALIDATION_FAILED("validation_failed"),
    INVALID_COORDINATE("invalid_coordinate"),
    INVALID_SEMVER("invalid_semver"),
    NOT_FOUND("not_found"),
    LISTING_NOT_FOUND("listing_not_found"),
    RELEASE_NOT_FOUND("release_not_found"),
    NAMESPACE_TAKEN("namespace_taken"),
    SLUG_TAKEN("slug_taken"),
    NAMESPACE_NOT_OWNED("namespace_not_owned"),
    EMAIL_TAKEN("email_taken"),
    INVALID_CREDENTIALS("invalid_credentials"),
    UNAUTHENTICATED("unauthenticated"),
    FORBIDDEN("forbidden"),
    ROLE_REQUIRED("role_required"),
    INVALID_STATE_TRANSITION("invalid_state_transition"),
    DUPLICATE_VERSION("duplicate_version"),
    UPLOAD_NOT_COMPLETE("upload_not_complete"),
    UPLOAD_EXPIRED("upload_expired"),
    SCAN_FAILED("scan_failed"),
    SIGNATURE_INVALID("signature_invalid"),
    SELF_REVIEW_FORBIDDEN("self_review_forbidden"),
    TICKET_INVALID("ticket_invalid"),
    RATE_LIMITED("rate_limited"),
    IDEMPOTENCY_CONFLICT("idempotency_conflict"),
    WRONG_PASSWORD("wrong_password"),
    UPSTREAM_DRIFTED("upstream_drifted"),
    REPORT_NOT_FOUND("report_not_found"),
    INTERNAL_ERROR("internal_error");

    public final String code;

    StoreErrorCode(String code) {
        this.code = code;
    }
}
