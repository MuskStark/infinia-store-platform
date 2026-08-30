package dev.infinia.store.app.upstream;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.List;
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

    /** Builds the compatibility package directly on disk with bounded buffers. */
    public Path buildSkillPackage(String namespace, String slug, String name,
            String description, Path skillDirectory, String version, Path target)
            throws IOException {
        Path root = skillDirectory.toAbsolutePath().normalize();
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", 1);
        manifest.put("id", namespace + "." + slug);
        manifest.put("name", name);
        manifest.put("description", description);
        manifest.put("version", version);
        manifest.put("author", namespace);
        manifest.put("official", false);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            ZipEntry manifestEntry = new ZipEntry("manifest.json");
            manifestEntry.setTime(0);
            zip.putNextEntry(manifestEntry);
            zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
            zip.closeEntry();
            List<Path> files;
            try (var paths = Files.walk(root)) {
                files = paths.filter(Files::isRegularFile)
                        .filter(path -> !Files.isSymbolicLink(path))
                        .filter(path -> !"manifest.json".equals(
                                root.relativize(path).toString().replace('\\', '/')))
                        .sorted(java.util.Comparator.comparing(
                                path -> root.relativize(path).toString()))
                        .toList();
            }
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                ZipEntry entry = new ZipEntry(relative);
                entry.setTime(0);
                zip.putNextEntry(entry);
                Files.copy(file, zip);
                zip.closeEntry();
            }
        }
        return target;
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

    /** Stable identity for a catalog row; deliberately excludes payload bytes. */
    public String metadataDigest(UpstreamAdapter.NormalizedItem item) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of(
                    nullToEmpty(item.externalId()), nullToEmpty(item.kind()),
                    nullToEmpty(item.name()), nullToEmpty(item.slug()),
                    nullToEmpty(item.description()), nullToEmpty(item.version()),
                    nullToEmpty(item.sourcePath()), nullToEmpty(item.sourceUrl()),
                    nullToEmpty(item.license()))) {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
