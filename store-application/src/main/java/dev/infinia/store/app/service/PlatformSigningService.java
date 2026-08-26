package dev.infinia.store.app.service;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.domain.model.SigningKeyInfo;
import dev.infinia.store.domain.port.PublishingRepositories;
import dev.infinia.store.scanner.Ed25519Signer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Optional;

/**
 * Platform release signing (design §8.3, ADR-006). The private key is kept outside
 * the database (dev: file under data/keys; prod: KMS-backed). Approved releases are
 * signed with Ed25519 over the canonical envelope JSON.
 */
@Service
public class PlatformSigningService {

    public static final String PLATFORM_KEY_ID_PREFIX = "platform-ed25519-";

    private static final Logger log = LoggerFactory.getLogger(PlatformSigningService.class);

    private final PublishingRepositories.SigningKeyRepository signingKeys;
    private final Path keyDir;
    private PrivateKey privateKey;
    private String keyId;

    public PlatformSigningService(PublishingRepositories.SigningKeyRepository signingKeys,
            StoreProperties properties) {
        this.signingKeys = signingKeys;
        this.keyDir = Path.of(properties.keyDir());
    }

    @PostConstruct
    void init() {
        Optional<SigningKeyInfo> existing = signingKeys.findByOwnerTypeAndStatus(
                SigningKeyInfo.OWNER_PLATFORM, "ACTIVE").stream().findFirst();
        if (existing.isPresent()) {
            keyId = existing.get().keyId();
            privateKey = loadPrivate(keyId);
            if (privateKey != null) {
                log.info("Using existing platform signing key {}", keyId);
                return;
            }
            log.warn("Platform key {} registered but private part missing; generating new key",
                    keyId);
        }
        var pair = Ed25519Signer.generateKeyPair();
        keyId = PLATFORM_KEY_ID_PREFIX + java.time.Year.now();
        try {
            Files.createDirectories(keyDir);
            Files.writeString(keyDir.resolve(keyId + ".b64"),
                    Ed25519Signer.encodePrivate(pair.getPrivate()));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot persist platform signing key", e);
        }
        privateKey = pair.getPrivate();
        signingKeys.save(new SigningKeyInfo(keyId, "Ed25519",
                Ed25519Signer.encodePublic(pair.getPublic()), SigningKeyInfo.OWNER_PLATFORM, null,
                "ACTIVE", Instant.now(), null));
        log.info("Generated platform signing key {}", keyId);
    }

    public String sign(String canonicalJson) {
        return Ed25519Signer.signBase64(privateKey, canonicalJson);
    }

    public String currentKeyId() {
        return keyId;
    }

    public boolean verify(String keyId, String canonicalJson, String signatureBase64) {
        return signingKeys.findByKeyId(keyId)
                .filter(k -> "ACTIVE".equals(k.status()))
                .map(k -> {
                    try {
                        return Ed25519Signer.verifyBase64(
                                Ed25519Signer.decodePublic(k.publicKeyBase64()), canonicalJson,
                                signatureBase64);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }
                })
                .orElse(false);
    }

    private PrivateKey loadPrivate(String keyId) {
        try {
            Path file = keyDir.resolve(keyId + ".b64");
            if (!Files.exists(file)) {
                return null;
            }
            return Ed25519Signer.decodePrivate(Files.readString(file).trim());
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
