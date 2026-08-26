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
                case "PLUGIN" -> scanZipPackage(content, expectedVersion, result, "plugin.json",
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
        if (result.manifestName == null) {
            result.error("plugin.manifest-field", "Manifest field 'name' is required");
        }
        if (result.manifestVersion == null) {
            result.error("plugin.manifest-field", "Manifest field 'version' is required");
        } else if (!result.manifestVersion.equals(expectedVersion)) {
            result.error("plugin.version-mismatch",
                    "Manifest version " + result.manifestVersion + " does not match release version "
                            + expectedVersion);
        }
        if (json.has("entry")) {
            String entry = text(json, "entry");
            if (entry != null && (files.get(entry) == null)) {
                result.error("plugin.entry-missing", "Declared entry '" + entry + "' is missing");
            }
        }
        JsonNode permissions = json.get("permissions");
        if (permissions != null && permissions.isArray()) {
            for (JsonNode p : permissions) {
                if (p.isObject()) {
                    result.extractedPermissions.add(mapper.convertValue(p, Map.class));
                }
            }
        }
        scanContents(files, result);
        result.sbom = SbomGenerator.generate(typeLabel, expectedVersion,
                Ed25519Signer.sha256Hex(content), files);
    }

    // ---- SKILL (.fys zip with SKILL.md) ----

    private void scanSkill(byte[] content, String expectedVersion, ScanResult result)
            throws IOException {
        Map<String, SafeZip.ExtractedFile> files = SafeZip.extract(new ByteArrayInputStream(content),
                limits);
        result.files.addAll(files.keySet());
        SafeZip.ExtractedFile skill = files.get("SKILL.md");
        if (skill == null) {
            // tolerate a nested single directory: <dir>/SKILL.md
            skill = files.entrySet().stream()
                    .filter(e -> e.getKey().endsWith("/SKILL.md") && e.getKey().indexOf('/') ==
                            e.getKey().lastIndexOf('/'))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }
        if (skill == null) {
            result.error("skill.manifest-missing", "Missing SKILL.md");
            return;
        }
        String md = skill.text();
        if (!md.startsWith("---")) {
            result.error("skill.frontmatter-missing", "SKILL.md must start with YAML frontmatter");
            return;
        }
        String frontmatter = md.substring(3, md.indexOf("---", 3));
        if (!frontmatter.contains("name:")) {
            result.error("skill.frontmatter-field", "SKILL.md frontmatter must define 'name'");
        }
        if (!frontmatter.contains("description:")) {
            result.error("skill.frontmatter-field",
                    "SKILL.md frontmatter must define 'description'");
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
