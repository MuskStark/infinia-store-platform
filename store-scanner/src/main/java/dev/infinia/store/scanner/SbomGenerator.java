package dev.infinia.store.scanner;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Minimal CycloneDX 1.5 SBOM generation from the extracted file inventory (design §8.3). */
public final class SbomGenerator {

    private SbomGenerator() {}

    public static String generate(String coordinate, String version, String sha256,
            Map<String, SafeZip.ExtractedFile> files) {
        Map<String, Long> inventory = new java.util.LinkedHashMap<>();
        files.forEach((name, file) -> inventory.put(name, (long) file.content().length));
        return generateInventory(coordinate, version, sha256, inventory);
    }

    /** Generates an SBOM from disk-backed inventory without retaining file bytes. */
    public static String generateInventory(String coordinate, String version, String sha256,
            Map<String, Long> files) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        sb.append("  \"bomFormat\": \"CycloneDX\",\n");
        sb.append("  \"specVersion\": \"1.5\",\n");
        sb.append("  \"serialNumber\": \"urn:uuid:").append(UUID.randomUUID()).append("\",\n");
        sb.append("  \"version\": 1,\n");
        sb.append("  \"metadata\": {\n");
        sb.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        sb.append("    \"component\": {\n");
        sb.append("      \"type\": \"container\",\n");
        sb.append("      \"name\": \"").append(json(coordinate)).append("\",\n");
        sb.append("      \"version\": \"").append(json(version)).append("\"\n");
        sb.append("    }\n");
        sb.append("  },\n");
        sb.append("  \"components\": [\n");
        boolean first = true;
        for (Map.Entry<String, Long> e : files.entrySet()) {
            if (!first) {
                sb.append(",\n");
            }
            first = false;
            sb.append("    {\"type\": \"file\", \"name\": \"").append(json(e.getKey()))
                    .append("\", \"size\": ").append(e.getValue()).append("}");
        }
        sb.append("\n  ]\n");
        sb.append("}");
        return sb.toString();
    }

    private static String json(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "\\r").replace("\t", "\\t");
    }
}
