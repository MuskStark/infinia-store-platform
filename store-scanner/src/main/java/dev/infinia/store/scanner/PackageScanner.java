package dev.infinia.store.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

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

    /**
     * Disk-backed scan entry point for live upstream delivery. Skill archives are
     * inspected through {@link ZipFile}; only individual text files up to 2 MiB
     * are read for content rules, never the complete package.
     */
    public ScanResult scan(String listingType, String expectedVersion, Path content) {
        if (!"SKILL".equalsIgnoreCase(listingType)) {
            try {
                long size = Files.size(content);
                if (size > 16L * 1024 * 1024) {
                    ScanResult result = new ScanResult();
                    result.error("scanner.file-too-large",
                            "Non-archive template exceeds the 16 MiB scan limit");
                    return result;
                }
                return scan(listingType, expectedVersion, Files.readAllBytes(content));
            } catch (IOException e) {
                ScanResult result = new ScanResult();
                result.error("scanner.io", "Package could not be read: " + e.getMessage());
                return result;
            }
        }
        ScanResult result = new ScanResult();
        result.mimeType = "application/zip";
        try {
            scanSkill(content, expectedVersion, result);
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

    private void scanSkill(Path content, String expectedVersion, ScanResult result)
            throws IOException {
        Map<String, Long> inventory = new LinkedHashMap<>();
        byte[] manifestBytes = null;
        boolean hasSkillMd = false;
        long total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(content.toFile())) {
            Enumeration<? extends ZipEntry> cursor = zip.entries();
            while (cursor.hasMoreElements()) {
                ZipEntry entry = cursor.nextElement();
                entries++;
                if (entries > limits.maxEntries()) {
                    throw new ScanViolation("zip.too-many-entries",
                            "Archive exceeds the maximum of " + limits.maxEntries()
                                    + " entries");
                }
                SafeZip.validatePath(entry.getName());
                if (entry.isDirectory()) {
                    continue;
                }
                long size = entry.getSize();
                long compressed = entry.getCompressedSize();
                if (size < 0 || size > limits.maxEntryBytes()) {
                    throw new ScanViolation("zip.entry-too-large",
                            "Entry " + entry.getName() + " exceeds the single-entry size limit");
                }
                total += size;
                if (total > limits.maxTotalBytes()) {
                    throw new ScanViolation("zip.total-too-large",
                            "Archive exceeds the total uncompressed size limit");
                }
                if (compressed > 0 && size / compressed > limits.maxRatio()) {
                    throw new ScanViolation("zip.bomb",
                            "Entry " + entry.getName() + " exceeds the compression ratio limit");
                }
                if (inventory.putIfAbsent(entry.getName(), size) != null) {
                    throw new ScanViolation("zip.duplicate-entry",
                            "Archive contains duplicate entry " + entry.getName());
                }
                result.files.add(entry.getName());
                if ("SKILL.md".equals(entry.getName())) {
                    hasSkillMd = true;
                }
                if ("manifest.json".equals(entry.getName())) {
                    manifestBytes = readEntry(zip, entry, 2L * 1024 * 1024);
                }
                if (size <= 2L * 1024 * 1024) {
                    byte[] text = readEntry(zip, entry, 2L * 1024 * 1024);
                    ContentScanners.scanFile(entry.getName(),
                            new String(text, StandardCharsets.UTF_8), result);
                }
            }
        }
        if (manifestBytes == null) {
            result.error("skill.manifest-missing",
                    "Missing manifest.json (host skill package contract)");
            return;
        }
        JsonNode json;
        try {
            json = mapper.readTree(manifestBytes);
        } catch (IOException e) {
            result.error("skill.manifest-invalid", "manifest.json is not valid JSON");
            return;
        }
        validateSkillManifest(json, expectedVersion, hasSkillMd, result);
        result.sbom = SbomGenerator.generateInventory("SKILL", expectedVersion,
                sha256Hex(content), inventory);
    }

    private void validateSkillManifest(JsonNode json, String expectedVersion,
            boolean hasSkillMd, ScanResult result) {
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
                    "Manifest version " + result.manifestVersion
                            + " does not match release version " + expectedVersion);
        }
        if (!hasSkillMd) {
            result.error("skill.skill-md-missing", "SKILL.md must exist at the package root");
        }
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
            } else if (!url.startsWith("https://") && url != null) {
                result.error("mcp.url-not-https", "Remote MCP templates must use HTTPS URLs");
            }
            if (url == null && json.get("stdioDeployment") == null) {
                result.error("mcp.url-not-https",
                        "Templates need either an HTTPS urlTemplate or a stdioDeployment");
            }
        }
        // stdioDeployment block (aggregation plan §6.2): a pinned package with
        // registry, version and digest; commands must be structured, never shell.
        JsonNode stdio = json.get("stdioDeployment");
        if (stdio != null && stdio.isObject()) {
            String runtime = text(stdio, "runtime");
            if (runtime == null || !Set.of("npm", "pypi", "nuget", "docker", "mcpb")
                    .contains(runtime)) {
                result.error("mcp.stdio-runtime", "stdioDeployment runtime must be one of "
                        + "npm, pypi, nuget, docker, mcpb");
            }
            for (String required : new String[] {"package", "version", "digest"}) {
                if (isBlank(text(stdio, required))) {
                    result.error("mcp.stdio-pinned-package",
                            "stdioDeployment requires registry " + required
                                    + " — unpinned package installs are blocked");
                }
            }
            String command = text(stdio, "command");
            if (isBlank(command)) {
                result.error("mcp.stdio-command-missing",
                        "stdioDeployment must declare the launch command");
            } else if (command.contains("$(") || command.contains("`")
                    || command.contains(";") || command.contains("|")) {
                result.error("mcp.command-injection",
                        "stdioDeployment command must be a single executable, no shell composition");
            }
            JsonNode args = stdio.get("args");
            if (args != null && !args.isArray()) {
                result.error("mcp.stdio-args-structured",
                        "stdioDeployment args must be a structured array");
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

    private static byte[] readEntry(ZipFile zip, ZipEntry entry, long maxBytes)
            throws IOException {
        if (entry.getSize() > maxBytes) {
            throw new IOException("Entry exceeds read limit: " + entry.getName());
        }
        try (InputStream in = zip.getInputStream(entry);
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("Entry exceeds read limit: " + entry.getName());
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
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
