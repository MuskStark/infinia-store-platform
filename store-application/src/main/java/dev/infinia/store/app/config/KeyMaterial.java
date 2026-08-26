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

/**
 * Signing key material for the local deployment (design §8.3, ADR-006).
 *
 * <p>Development keys are generated once and persisted under {@code data/keys} so
 * restarts keep issuing valid tokens. Production deployments inject keys via the
 * environment (KMS-backed); the platform Ed25519 release-signing key lives in
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
            Files.writeString(publicPem,
                    Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
            Files.writeString(privatePem,
                    Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
            return pair;
        } catch (IOException | NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Failed to prepare JWT signing keys", e);
        }
    }
}
