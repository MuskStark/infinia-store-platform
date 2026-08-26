package dev.infinia.store.contract.envelope;

import java.util.List;

/**
 * MCP listings publish reviewed configuration templates, never live secrets
 * (design §6.4). Templates are installed disabled; the user supplies secrets
 * locally before the server can be enabled.
 */
public record McpTemplate(
        int schemaVersion,
        String id,
        String name,
        McpTransport transport,
        String urlTemplate,
        String commandTemplate,
        List<String> argsTemplate,
        List<RequiredSecret> requiredSecrets,
        boolean defaultEnabled,
        ToolPolicy toolPolicy,
        List<String> networkHosts) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public enum McpTransport { STDIO, SSE, STREAMABLE_HTTP }

    public record RequiredSecret(String name, String target, boolean sensitive) {}

    public record ToolPolicy(boolean enabledByDefault, List<String> toolsRequiringApproval) {
        public ToolPolicy {
            toolsRequiringApproval = toolsRequiringApproval == null ? List.of() : toolsRequiringApproval;
        }
    }
}
