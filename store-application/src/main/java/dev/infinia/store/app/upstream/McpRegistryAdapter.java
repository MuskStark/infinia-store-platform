package dev.infinia.store.app.upstream;

import dev.infinia.store.app.upstream.ClaudeMarketplaceAdapter;
import dev.infinia.store.app.upstream.UpstreamAdapter.NormalizedItem;
import dev.infinia.store.domain.model.UpstreamSource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP_REGISTRY adapter (plan §3.1/§6): consumes the official registry
 * {@code server.json}. Remotes become HTTPS template deployments; stdio
 * packages (npm/PyPI/NuGet/Docker/MCPB) are recorded as pinned deployment
 * metadata — registry, version and digest — for the host to install in its
 * own sandbox. No secrets ever enter the template (plan §6.2).
 */
@Component
public class McpRegistryAdapter implements UpstreamAdapter {

    @Override
    public String type() {
        return MCP_REGISTRY;
    }

    @Override
    public List<NormalizedItem> discover(UpstreamSource source, RepoFetcher fetcher)
            throws IOException, InterruptedException {
        JsonNode server = fetcher.fetchJson(source.marketplaceUrl());
        String id = server.path("id").asString(
                server.path("name").asString("mcp-server"));
        String name = server.path("name").asString(id);
        String description = ClaudeMarketplaceAdapter.clamp(
                server.path("description").asString(""), 480);
        String version = server.path("version").asString(null);
        return List.of(new NormalizedItem("mcp-registry:" + id, "MCP", name,
                ClaudeMarketplaceAdapter.slug(id), description, version, "",
                source.marketplaceUrl(), null, null, null));
    }

    @Override
    public NormalizedItem materialize(UpstreamSource source, NormalizedItem discovered,
            RepoFetcher fetcher) throws IOException, InterruptedException {
        JsonNode server = fetcher.fetchJson(discovered.sourceUrl());

        ObjectNode template = tools.jackson.databind.json.JsonMapper.builder()
                .build().createObjectNode();
        template.put("schemaVersion", 1);
        template.put("id", source.targetNamespace() + "."
                + discovered.slug());
        template.put("name", discovered.name());
        template.put("description", discovered.description());
        template.put("transport", "STREAMABLE_HTTP");
        template.put("defaultEnabled", false);
        template.putObject("toolPolicy").put("enabledByDefault", false);

        JsonNode remote = server.path("remotes").path(0);
        if (remote.isObject() && remote.path("transport_type").asString("").contains("http")) {
            template.put("urlTemplate", remote.path("url").asString());
            template.putArray("networkHosts").add(hostOf(remote.path("url").asString()));
            ArrayNode secrets = template.putArray("requiredSecrets");
                        remote.path("headers").propertyNames().forEach(h -> secrets.addObject()
                    .put("name", h).put("target", "header").put("sensitive", true));
        }

        ObjectNode stdio = template.putObject("stdioDeployment");
        JsonNode pkg = server.path("packages").path(0);
        if (pkg.isObject()) {
            stdio.put("runtime", pkg.path("registry_type").asString("npm"));
            stdio.put("package", pkg.path("identifier").asString(
                    pkg.path("name").asString("")));
            stdio.put("version", pkg.path("version").asString(""));
            stdio.put("digest", pkg.path("checksum").asString(""));
            JsonNode runtimeArgs = pkg.path("runtime_args");
            if (runtimeArgs.isArray()) {
                stdio.set("args", runtimeArgs);
            } else {
                stdio.put("command", "npx");
                stdio.set("args", stdio.arrayNode().add("-y")
                        .add(pkg.path("identifier").asString("")));
            }
        }
        if (!template.has("urlTemplate") && !stdio.has("package")) {
            throw new IOException("server.json has neither remote nor package deployment");
        }

        return new NormalizedItem(discovered.externalId(), discovered.kind(),
                discovered.name(), discovered.slug(), discovered.description(),
                discovered.version(), discovered.sourcePath(), discovered.sourceUrl(), null,
                tools.jackson.databind.json.JsonMapper.builder().build()
                        .writerWithDefaultPrettyPrinter().writeValueAsBytes(template),
                discovered.license());
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }
}
