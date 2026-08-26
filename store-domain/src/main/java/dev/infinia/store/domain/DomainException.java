package dev.infinia.store.domain;

import dev.infinia.store.contract.error.StoreErrorCode;

import java.util.Map;

/** Domain-level failure carrying a stable, client-facing error code. */
public class DomainException extends RuntimeException {

    public final StoreErrorCode code;
    public final Map<String, Object> params;

    public DomainException(StoreErrorCode code, String message, Map<String, Object> params) {
        super(message);
        this.code = code;
        this.params = params == null ? Map.of() : params;
    }

    public DomainException(StoreErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public static DomainException notFound(String message) {
        return new DomainException(StoreErrorCode.NOT_FOUND, message);
    }

    public static DomainException forbidden(String message) {
        return new DomainException(StoreErrorCode.FORBIDDEN, message);
    }
}
