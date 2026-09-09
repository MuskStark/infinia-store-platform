package dev.infinia.store.app.config;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Signing key material for the local deployment (design §8.3, ADR-006).
 *
 * <p>Keys are RSA-2048, generated on first boot and persisted as base64 PEM
 * files under {@code store.key-dir} (in containers: the /var/lib/infinia-store
 * volume, backed up by scripts/backup-stack.sh). Restarts load the existing
 * pair; to rotate, generate a replacement pair offline, swap both files, and
 * restart — every outstanding access token is invalidated. There is no
 * environment/KMS injection path: the key directory IS the key store, so its
 * permissions and backups are part of the deployment's secret hygiene. The
 * platform Ed25519 release-signing key lives in
 * {@link dev.infinia.store.app.service.PlatformSigningService}.</p>
 */
@Component
public class KeyMaterial {

    private final KeyPair jwtKeyPair;

    public KeyMaterial(StoreProperties properties) {
        this.jwtKeyPair = loadOrGenerateRsa(Path.of(properties.keyDir()));
    }

    public KeyPair jwtKeyPair() {
        return jwtKeyPair;
    }

    public RSAPublicKey jwtPublicKey() {
        return (RSAPublicKey) jwtKeyPair.getPublic();
    }

    private static KeyPair loadOrGenerateRsa(Path dir) {
        Path publicPem = dir.resolve("jwt-rsa-public.b64");
        Path privatePem = dir.resolve("jwt-rsa-private.b64");
        try {
            if (Files.exists(publicPem) && Files.exists(privatePem)) {
                KeyFactory factory = KeyFactory.getInstance("RSA");
                return new KeyPair(
                        factory.generatePublic(new X509EncodedKeySpec(
                                Base64.getDecoder().decode(Files.readString(publicPem).trim()))),
                        factory.generatePrivate(new PKCS8EncodedKeySpec(
                                Base64.getDecoder().decode(Files.readString(privatePem).trim()))));
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Files.createDirectories(dir);
            writeKeyFile(publicPem,
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                    "rw-r--r--");
            writeKeyFile(privatePem,
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                    "rw-------");
            return pair;
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to prepare JWT signing keys", e);
        }
    }

    /** Key files are written owner-only for the private half (best effort on
     *  non-POSIX filesystems) through the atomic temp-and-swap used by the
     *  other key writers. */
    private static void writeKeyFile(Path file, String content, String permissions)
            throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem: rely on the directory's own permissions.
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
