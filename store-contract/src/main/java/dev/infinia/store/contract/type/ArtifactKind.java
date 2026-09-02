package dev.infinia.store.contract.type;

/** Kind of an artifact attached to a release (design §6.1). */
public enum ArtifactKind {
    /** The primary installable package (.fyp, .fys, .fyflow or MCP manifest). */
    PACKAGE,
    /** Installed APP distribution (NSIS exe, dmg or deb). */
    INSTALLER,
    /** Portable APP distribution (zip, AppImage, portable web archive or fat JAR). */
    PORTABLE,
    /** checksums.txt alongside an APP release. */
    CHECKSUMS,
    /** Detached signature file. */
    SIGNATURE,
    /** CycloneDX SBOM document. */
    SBOM
}
