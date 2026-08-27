package dev.infinia.store.infrastructure.export;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

/**
 * Minimal writer for local bare git repositories (loose objects only).
 *
 * The FengYu host installs skills and MCP servers through its CLAUDE ecosystem
 * source type, whose installer git-clones each entry (JGit, scheme restricted to
 * http/https/file). The store therefore exports every eligible listing as a tiny
 * local git repo under {@code store.export-dir}; the marketplace points at it with
 * a {@code file://} URL (same-machine deployments). Content-addressed exports keep
 * updates meaningful: unchanged content keeps the commit, changed content gets a
 * child commit the host picks up on its next clone.
 */
@Component
public class LocalGitExporter {

    private final Path baseDir;

    public LocalGitExporter(@Value("${store.export-dir:data/git-exports}") String exportDir) {
        this.baseDir = Path.of(exportDir).toAbsolutePath();
    }

    /** Absolute file:// URL of the exported repository for the given key. */
    public String repoUrl(String key) {
        return repoDir(key).toUri().toString();
    }

    /**
     * Ensures a bare repo exists for {@code key} with exactly {@code files} in the
     * work tree; returns the HEAD commit sha (unchanged when the tree is identical).
     */
    public synchronized String export(String key, Map<String, byte[]> files, String message)
            throws IOException {
        Path repo = ensureRepo(key);
        String tree = writeTree(repo, files);
        String parent = readHead(repo);
        if (parent != null && treeOf(repo, parent) != null
                && treeOf(repo, parent).equals(tree)) {
            return parent; // content unchanged — keep the published sha stable
        }
        String commit = writeCommit(repo, tree, parent, message);
        writeHead(repo, commit);
        return commit;
    }

    // ---- repository scaffolding ----

    private Path ensureRepo(String key) throws IOException {
        Path repo = repoDir(key);
        Files.createDirectories(repo.resolve("objects"));
        Files.createDirectories(repo.resolve("refs/heads"));
        Path head = repo.resolve("HEAD");
        if (Files.notExists(head)) {
            Files.writeString(head, "ref: refs/heads/master\n");
        }
        return repo;
    }

    private Path repoDir(String key) {
        return baseDir.resolve(key.replaceAll("[^a-zA-Z0-9._-]", "_") + ".git");
    }

    private String readHead(Path repo) throws IOException {
        Path ref = repo.resolve("refs/heads/master");
        return Files.exists(ref) ? Files.readString(ref).trim() : null;
    }

    private void writeHead(Path repo, String commit) throws IOException {
        Files.writeString(repo.resolve("refs/heads/master"), commit + "\n");
    }

    // ---- object writing ----

    private String writeObject(Path repo, byte[] headerAndBody) throws IOException {
        String sha = sha1(headerAndBody);
        Path object = repo.resolve("objects").resolve(sha.substring(0, 2))
                .resolve(sha.substring(2));
        if (Files.notExists(object)) {
            Files.createDirectories(object.getParent());
            Files.write(object, deflate(headerAndBody));
        }
        return sha;
    }

    private String writeBlob(Path repo, byte[] content) throws IOException {
        return writeObject(repo, concat(("blob " + content.length + "\0")
                .getBytes(StandardCharsets.ISO_8859_1), content));
    }

    /** Writes the tree for the flat path→content map, creating intermediate dirs. */
    private String writeTree(Path repo, Map<String, byte[]> files) throws IOException {
        Node root = new Node();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            Node dir = root;
            String[] parts = e.getKey().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                dir = dir.children.computeIfAbsent(parts[i], k -> new Node());
            }
            dir.children.put(parts[parts.length - 1], new Node(e.getValue()));
        }
        return writeTreeNode(repo, root);
    }

    private String writeTreeNode(Path repo, Node node) throws IOException {
        List<String> lines = new ArrayList<>();
        record Entry(int mode, String name, String sha) {}
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Node> e : node.children.entrySet()) {
            Node child = e.getValue();
            if (child.isDir()) {
                entries.add(new Entry(040000, e.getKey(), writeTreeNode(repo, child)));
            } else {
                entries.add(new Entry(0100644, e.getKey(), writeBlob(repo, child.content)));
            }
        }
        // git tree order: plain byte order with directories sorted as name + "/"
        entries.sort(Comparator.comparing(a -> a.mode == 040000 ? a.name() + "/" : a.name()));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            out.writeBytes((String.format("%06o", entry.mode()) + " " + entry.name() + "\0")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(HexFormat.of().parseHex(entry.sha()));
        }
        byte[] body = out.toByteArray();
        return writeObject(repo, concat(("tree " + body.length + "\0")
                .getBytes(StandardCharsets.ISO_8859_1), body));
    }

    private String writeCommit(Path repo, String tree, String parent, String message)
            throws IOException {
        long seconds = Instant.now().getEpochSecond();
        StringBuilder body = new StringBuilder();
        body.append("tree ").append(tree).append('\n');
        if (parent != null) {
            body.append("parent ").append(parent).append('\n');
        }
        body.append("author Infinia Store <store@infinia.local> ").append(seconds)
                .append(" +0000\n");
        body.append("committer Infinia Store <store@infinia.local> ").append(seconds)
                .append(" +0000\n\n");
        body.append(message).append('\n');
        byte[] content = body.toString().getBytes(StandardCharsets.UTF_8);
        return writeObject(repo, concat(("commit " + content.length + "\0")
                .getBytes(StandardCharsets.ISO_8859_1), content));
    }

    private String treeOf(Path repo, String commitSha) throws IOException {
        Path object = repo.resolve("objects").resolve(commitSha.substring(0, 2))
                .resolve(commitSha.substring(2));
        if (Files.notExists(object)) {
            return null;
        }
        // Loose objects store "<type> <len>\0<body>" — strip the header before parsing.
        String raw = new String(inflate(Files.readAllBytes(object)),
                StandardCharsets.ISO_8859_1);
        int headerEnd = raw.indexOf('\0');
        String content = headerEnd >= 0 ? raw.substring(headerEnd + 1) : raw;
        for (String line : content.split("\n")) {
            if (line.startsWith("tree ")) {
                return line.substring(5).trim();
            }
            if (line.isEmpty()) {
                break;
            }
        }
        return null;
    }

    // ---- helpers ----

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String sha1(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-1").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] deflate(byte[] content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream deflater = new DeflaterOutputStream(out)) {
            deflater.write(content);
        }
        return out.toByteArray();
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(compressed);
        java.io.ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.InflaterInputStream inflater =
                new java.util.zip.InflaterInputStream(in)) {
            inflater.transferTo(out);
        }
        return out.toByteArray();
    }

    private static final class Node {
        final byte[] content;
        final Map<String, Node> children = new LinkedHashMap<>();

        Node() {
            this.content = null;
        }

        Node(byte[] content) {
            this.content = content;
        }

        boolean isDir() {
            return content == null;
        }
    }
}
