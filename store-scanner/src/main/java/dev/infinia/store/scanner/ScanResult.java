package dev.infinia.store.scanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Result of scanning one uploaded package (design §8.2 steps 3-6). */
public class ScanResult {

    /** INFO | WARN | ERROR | CRITICAL */
    public record Finding(String severity, String rule, String message, String file) {

        public boolean blocking() {
            return "ERROR".equals(severity) || "CRITICAL".equals(severity);
        }
    }

    public final List<Finding> findings = new ArrayList<>();
    /** File inventory used for SBOM generation. */
    public final List<String> files = new ArrayList<>();
    /** Permissions extracted from the native manifest, if any. */
    public final List<Map<String, Object>> extractedPermissions = new ArrayList<>();
    /** Name from the native manifest, if any. */
    public String manifestName;
    /** Version from the native manifest, if any. */
    public String manifestVersion;
    /** Generated CycloneDX SBOM (JSON string). */
    public String sbom;
    /** MIME sniffed from content. */
    public String mimeType;

    public void info(String rule, String message) {
        findings.add(new Finding("INFO", rule, message, null));
    }

    public void info(String rule, String message, String file) {
        findings.add(new Finding("INFO", rule, message, file));
    }

    public void warn(String rule, String message) {
        findings.add(new Finding("WARN", rule, message, null));
    }

    public void error(String rule, String message) {
        findings.add(new Finding("ERROR", rule, message, null));
    }

    public void critical(String rule, String message) {
        findings.add(new Finding("CRITICAL", rule, message, null));
    }

    public boolean hasBlockingFindings() {
        return findings.stream().anyMatch(Finding::blocking);
    }
}
