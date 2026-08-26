package dev.infinia.store.contract.envelope;

/** Permission declaration (design §6.1). Scope examples: process, network, filesystem, mcp.tool. */
public record PermissionRef(
        String permissionId,
        String scope,
        boolean required,
        String reason) {
}
