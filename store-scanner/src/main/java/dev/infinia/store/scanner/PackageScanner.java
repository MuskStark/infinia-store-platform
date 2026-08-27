package dev.infinia.store.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

/**
 * Package scanning facade (design §8.2 steps 3-6). Validates native manifests of all
 * five artifact classes, runs secret / malicious-content heuristics and produces a
 * CycloneDX SBOM. Blocking findings cause automatic policy rejection.
 */
public class PackageScanner {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SafeZip.Limits limits;

    public PackageScanner() {
        this(SafeZip.Limits.defaults());
    }

    public PackageScanner(SafeZip.Limits limits) {
        this.limits = limits;
    }

    public ScanResult scan(String listingType, String expectedVersion, byte[] content) {
        ScanResult result = new ScanResult();
        result.mimeType = sniffMime(content);
        try {
            switch (listingType.toUpperCase(Locale.ROOT)) {
                case "PLUGIN" -> scanZipPackage(content, expectedVersion, result, "manifest.json",
                        "PLUGIN");
                case "SKILL" -> scanSkill(content, expectedVersion, result);
                case "FLOW" -> scanFlow(content, expectedVersion, result);
                case "MCP" -> scanMcpTemplate(content, expectedVersion, result);
                case "APP" -> scanApp(content, expectedVersion, result);
                default -> result.error("scanner.unknown-type",
                        "Unsupported listing type: " + listingType);
            }
        } catch (ScanViolation violation) {
            result.findings.add(new ScanResult.Finding("CRITICAL", violation.rule,
                    violation.getMessage(), null));
        } catch (IOException e) {
            result.error("scanner.io", "Package could not be read: " + e.getMessage());
        }
        return result;
    }

    // ---- PLUGIN (.fyp zip with plugin.json) ----

    private void scanZipPackage(byte[] content, String expectedVersion, ScanResult result,
            String manifestName, String typeLabel) throws IOException {
        Map<String, SafeZip.ExtractedFile> files = SafeZip.extract(new ByteArrayInputStream(content),
                limits);
        result.files.addAll(files.keySet());
        SafeZip.ExtractedFile manifest = files.get(manifestName);
        if (manifest == null) {
            result.error("plugin.manifest-missing", "Missing " + manifestName + " manifest");
            return;
        }
        JsonNode json;
        try {
            json = mapper.readTree(manifest.content());
        } catch (IOException e) {
            result.error("plugin.manifest-invalid", manifestName + " is not valid JSON");
            return;
        }
        result.manifestName = text(json, "name");
        result.manifestVersion = text(json, "version");

        // ---- rules mirrored from the FengYu host's PluginManifest validator ----
        if (json.path("schemaVersion").asInt(-1) != FengYuHostRules.PLUGIN_SCHEMA_VERSION) {
            result.error("plugin.schema-version", "plugin.json schemaVersion must be "
                    + FengYuHostRules.PLUGIN_SCHEMA_VERSION);
        }
        String id = text(json, "id");
        if (id == null || !FengYuHostRules.ID_PATTERN.matcher(id).matches()) {
            result.error("plugin.manifest-field",
                    "plugin.json 'id' must be a lowercase reverse-domain identifier");
        } else if (id.startsWith(FengYuHostRules.OFFICIAL_NAMESPACE)) {
            result.error("plugin.namespace-reserved",
                    "id must not use the reserved " + FengYuHostRules.OFFICIAL_NAMESPACE
                            + "* namespace");
        }
        if (Boolean.TRUE.equals(json.path("official").asBoolean(false))) {
            result.error("plugin.official-reserved",
                    "Only FengYu-trusted publishers may declare official:true");
        }
        for (String required : new String[] {"name", "description", "author", "icon",
                "category", "version"}) {
            if (isBlank(text(json, required))) {
                result.error("plugin.manifest-field",
                        "Manifest field '" + required + "' is required by the host");
            }
        }
        if (result.manifestVersion == null) {
            result.error("plugin.manifest-field", "Manifest field 'version' is required");
        } else if (!result.manifestVersion.equals(expectedVersion)) {
            result.error("plugin.version-mismatch",
                    "Manifest version " + result.manifestVersion + " does not match release version "
                            + expectedVersion);
        }
        // The host resolves the UI through ui.entry (not the legacy entry field).
        JsonNode uiEntry = json.path("ui").path("entry");
        if (!uiEntry.isTextual() || isBlank(uiEntry.asText())
                || files.get(uiEntry.asText()) == null) {
            result.error("plugin.ui-entry-missing",
                    "ui.entry must be declared and present inside the package");
        }
        JsonNode permissions = json.get("permissions");
        if (permissions != null && permissions.isArray()) {
            // Host format: array of permission tokens from a fixed allowlist.
            for (JsonNode p : permissions) {
                if (!p.isTextual()) {
                    result.error("plugin.permissions-format",
                            "permissions must be an array of strings (host contract)");
                    break;
                }
                String token = p.asText();
                if (!FengYuHostRules.isPluginPermissionAllowed(token)) {
                    result.error("plugin.permission-not-allowed",
                            "Permission '" + token + "' is not in the host allowlist "
                                    + FengYuHostRules.PLUGIN_PERMISSIONS);
                } else {
                    result.extractedPermissions.add(Map.of("permissionId", token));
                }
            }
        }
        String engines = text(json.path("engines"), "fengyu");
        if (engines != null && !FengYuHostRules.hostCompatibleRange(engines)) {
            result.error("plugin.engines-syntax",
                    "engines.fengyu must use host-compatible range syntax "
                            + "(>= <= > < = only; no ^ ~ x *)");
        }
        String backend = text(json.path("backend"), "runtime");
        if (backend != null && !FengYuHostRules.pluginBackendRuntimes().contains(backend)) {
            result.error("plugin.backend-runtime",
                    "backend.runtime must be one of " + FengYuHostRules.pluginBackendRuntimes());
        }
        scanContents(files, result);
        result.sbom = SbomGenerator.generate(typeLabel, expectedVersion,
                Ed25519Signer.sha256Hex(content), files);
    }

    // ---- SKILL (.fys zip with manifest.json + SKILL.md) ----

    private void scanSkill(byte[] content, String expectedVersion, ScanResult result)
            throws IOException {
        Map<String, SafeZip.ExtractedFile> files = SafeZip.extract(new ByteArrayInputStream(content),
                limits);
        result.files.addAll(files.keySet());

        // ---- rules mirrored from the FengYu host's SkillManifest validator ----
        SafeZip.ExtractedFile manifest = files.get("manifest.json");
        if (manifest == null) {
            result.error("skill.manifest-missing",
                    "Missing manifest.json (host skill package contract)");
            return;
        }
        JsonNode json;
        try {
            json = mapper.readTree(manifest.content());
        } catch (IOException e) {
            result.error("skill.manifest-invalid", "manifest.json is not valid JSON");
            return;
        }
        result.manifestName = text(json, "name");
        result.manifestVersion = text(json, "version");
        if (json.path("schemaVersion").asInt(-1) != FengYuHostRules.SKILL_SCHEMA_VERSION) {
            result.error("skill.schema-version", "manifest.json schemaVersion must be "
                    + FengYuHostRules.SKILL_SCHEMA_VERSION);
        }
        String id = text(json, "id");
        if (id == null || !FengYuHostRules.ID_PATTERN.matcher(id).matches()) {
            result.error("skill.manifest-field",
                    "manifest.json 'id' must be a lowercase reverse-domain identifier");
        } else if (id.startsWith(FengYuHostRules.OFFICIAL_NAMESPACE)
                || Boolean.TRUE.equals(json.path("official").asBoolean(false))) {
            result.error("skill.official-reserved",
                    "Only FengYu-trusted publishers may use the official namespace");
        }
        if (isBlank(result.manifestName)) {
            result.error("skill.manifest-field", "Manifest field 'name' is required");
        }
        if (result.manifestVersion == null
                || !FengYuHostRules.SKILL_VERSION_PATTERN.matcher(result.manifestVersion)
                        .matches()) {
            result.error("skill.manifest-field",
                    "Manifest field 'version' must be MAJOR.MINOR.PATCH");
        } else if (!result.manifestVersion.equals(expectedVersion)) {
            result.error("plugin.version-mismatch",
                    "Manifest version " + result.manifestVersion + " does not match release version "
                            + expectedVersion);
        }
        // The host only requires SKILL.md at the package root — no frontmatter parsing.
        if (files.get("SKILL.md") == null) {
            result.error("skill.skill-md-missing", "SKILL.md must exist at the package root");
        }
        scanContents(files, result);
        result.sbom = SbomGenerator.generate("SKILL", expectedVersion,
                Ed25519Signer.sha256Hex(content), files);
    }

    // ---- FLOW (.fyflow zip: manifest.json + workflow.json + dependencies.lock.json) ----

    private void scanFlow(byte[] content, String expectedVersion, ScanResult result)
            throws IOException {
        Map<String, SafeZip.ExtractedFile> files = SafeZip.extract(new ByteArrayInputStream(content),
                limits);
        result.files.addAll(files.keySet());
        requireFile(files, "manifest.json", "flow.manifest-missing", result);
        SafeZip.ExtractedFile workflow = requireFile(files, "workflow.json",
                "flow.workflow-missing", result);
        requireFile(files, "dependencies.lock.json", "flow.lock-missing", result);

        if (workflow != null) {
            try {
                JsonNode wf = mapper.readTree(workflow.content());
                if (wf.get("inputSchema") == null || wf.get("plan") == null) {
                    result.error("flow.workflow-schema",
                            "workflow.json must define inputSchema and plan");
                }
            } catch (IOException e) {
                result.error("flow.workflow-invalid", "workflow.json is not valid JSON");
            }
        }
        scanContents(files, result);
        result.sbom = SbomGenerator.generate("FLOW", expectedVersion,
                Ed25519Signer.sha256Hex(content), files);
    }

    // ---- MCP (JSON template, design §6.4) ----

    private void scanMcpTemplate(byte[] content, String expectedVersion, ScanResult result)
            throws IOException {
        JsonNode json;
        try {
            json = mapper.readTree(content);
        } catch (IOException e) {
            result.error("mcp.manifest-invalid", "MCP template is not valid JSON");
            return;
        }
        result.manifestName = text(json, "name");
        if (result.manifestName == null) {
            result.error("mcp.manifest-field", "Template field 'name' is required");
        }
        String transport = text(json, "transport");
        if (transport == null) {
            result.error("mcp.manifest-field", "Template field 'transport' is required");
        } else {
            String url = text(json, "urlTemplate");
            if (transport.equals("STDIO")) {
                String command = text(json, "commandTemplate");
                if (command == null || command.isBlank()) {
                    result.error("mcp.stdio-command-missing",
                            "STDIO templates must declare commandTemplate");
                } else if (command.contains("$(") || command.contains("`")
                        || command.contains(" && ") || command.contains(" || ")) {
                    result.error("mcp.command-injection",
                            "commandTemplate must not contain shell composition");
                }
            } else {
                if (url == null || !url.startsWith("https://")) {
                    result.error("mcp.url-not-https", "Remote MCP templates must use HTTPS URLs");
                }
            }
        }
        JsonNode defaultEnabled = json.get("defaultEnabled");
        if (defaultEnabled != null && defaultEnabled.asBoolean(false)) {
            result.error("mcp.default-enabled", "Templates must never install enabled");
        }
        JsonNode toolPolicy = json.get("toolPolicy");
        if (toolPolicy != null && toolPolicy.path("enabledByDefault").asBoolean(false)) {
            result.error("mcp.tools-enabled", "Tool policy must not enable tools by default");
        }
        Map<String, Object> asMap;
        try {
            asMap = mapper.convertValue(json, Map.class);
        } catch (IllegalArgumentException e) {
            asMap = Map.of();
        }
        ContentScanners.scanJsonForPlaintextSecrets(asMap, result);
        result.sbom = SbomGenerator.generate("MCP", expectedVersion,
                Ed25519Signer.sha256Hex(content), Map.of());
    }

    // ---- APP (platform installers; binaries are covered by code signing) ----

    private void scanApp(byte[] content, String expectedVersion, ScanResult result) {
        result.info("app.binary", "APP packages rely on platform code signing; no deep unpack");
        result.sbom = SbomGenerator.generate("APP", expectedVersion,
                Ed25519Signer.sha256Hex(content), Map.of());
    }

    // ---- helpers ----

    private SafeZip.ExtractedFile requireFile(Map<String, SafeZip.ExtractedFile> files, String name,
            String rule, ScanResult result) {
        SafeZip.ExtractedFile file = files.get(name);
        if (file == null) {
            result.error(rule, "Missing required file " + name);
        }
        return file;
    }

    private void scanContents(Map<String, SafeZip.ExtractedFile> files, ScanResult result) {
        for (Map.Entry<String, SafeZip.ExtractedFile> e : files.entrySet()) {
            ContentScanners.scanFile(e.getKey(), e.getValue().text(), result);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String sniffMime(byte[] content) {
        if (content.length >= 4 && (content[0] & 0xFF) == 0x50 && (content[1] & 0xFF) == 0x4B) {
            return "application/zip";
        }
        if (content.length >= 1 && (content[0] & 0xFF) == '{') {
            return "application/json";
        }
        return "application/octet-stream";
    }
}
