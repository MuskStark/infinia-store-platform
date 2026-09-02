package dev.infinia.store.contract.envelope;

/** A downloadable artifact belonging to a release (design §6.3). */
public record ArtifactRef(
        String url,
        String sha256,
        String signature,
        String keyId,
        long size,
        String platform,
        String arch,
        String kind,
        String variant,
        String mimeType) {
}
