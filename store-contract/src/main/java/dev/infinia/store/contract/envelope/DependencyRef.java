package dev.infinia.store.contract.envelope;

/** Declared dependency of a release (design §6.1 / §6.3). */
public record DependencyRef(
        String coordinate,
        String range,
        boolean optional) {
}
