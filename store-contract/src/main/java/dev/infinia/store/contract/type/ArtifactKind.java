package dev.infinia.store.contract.type;

/** Kind of an artifact attached to a release (design §6.1). */
public enum ArtifactKind {
    /** The primary installable package (.fyp, .fys, .fyflow, MCP manifest, platform installer). */
    PACKAGE,
    /** Platform installer image for APP releases (dmg, exe, AppImage, portable JAR). */
    INSTALLER,
    /** checksums.txt alongside an APP release. */
    CHECKSUMS,
    /** Detached signature file. */
    SIGNATURE,
    /** CycloneDX SBOM document. */
    SBOM
}
