package dev.infinia.store.contract.type;

/** Global roles (design §7.3). Organization-scoped roles extend these on the server side. */
public enum UserRole {
    USER,
    PUBLISHER,
    ORG_ADMIN,
    REVIEWER,
    PLATFORM_ADMIN
}
