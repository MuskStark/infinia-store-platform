package dev.infinia.store.contract.type;

/** Release channels with rollout semantics (design §6.1). */
public enum Channel {
    STABLE,
    BETA,
    ALPHA,
    NIGHTLY,
    PRIVATE
}
