package dev.infinia.store.app.upstream;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Deterministic package builder shared by sync and pass-through download so the
 * same upstream revision always yields byte-identical artifacts (sorted entries,
 * zero timestamps). Aggregated plan §5.1: the store adds only the outer
 * manifest; SKILL.md and resources are preserved verbatim.
 */
@Component
public class UpstreamPackageBuilder {

    private final ObjectMapper mapper = new ObjectMapper();

    public byte[] buildSkillPackage(String namespace, String slug, String name,
            String description, Map<String, byte[]> skillFiles, String version)
            throws IOException {
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", namespace + "." + slug);
        manifest.put("name", name);
        manifest.put("description", description);
        manifest.put("version", version);
        manifest.put("author", namespace);
        manifest.put("official", false);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry manifestEntry = new ZipEntry("manifest.json");
            manifestEntry.setTime(0);
            zip.putNextEntry(manifestEntry);
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            for (Map.Entry<String, byte[]> e : new TreeMap<>(skillFiles).entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                entry.setTime(0);
                zip.putNextEntry(entry);
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** Order-independent digest over normalized content — the integrity key. */
    public String contentDigest(Map<String, byte[]> skillFiles, byte[] mcpTemplate) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (mcpTemplate != null) {
                digest.update(mcpTemplate);
            } else if (skillFiles != null) {
                new TreeMap<>(skillFiles).forEach((name, bytes) -> {
                    digest.update(name.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(bytes);
                });
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
