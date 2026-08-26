package dev.infinia.store.scanner;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

import static org.junit.jupiter.api.Assertions.*;

class Ed25519SignerTest {

    @Test
    void signVerifyRoundtrip() {
        KeyPair pair = Ed25519Signer.generateKeyPair();
        String content = "infinia://plugin/official/markdown@4.0.0-beta.5";
        String signature = Ed25519Signer.signBase64(pair.getPrivate(), content);

        assertTrue(Ed25519Signer.verifyBase64(pair.getPublic(), content, signature));
    }

    @Test
    void tamperedContentFailsVerification() {
        KeyPair pair = Ed25519Signer.generateKeyPair();
        String signature = Ed25519Signer.signBase64(pair.getPrivate(), "original");

        assertFalse(Ed25519Signer.verifyBase64(pair.getPublic(), "tampered", signature));
    }

    @Test
    void wrongKeyFailsVerification() {
        KeyPair signer = Ed25519Signer.generateKeyPair();
        KeyPair other = Ed25519Signer.generateKeyPair();
        String signature = Ed25519Signer.signBase64(signer.getPrivate(), "payload");

        assertFalse(Ed25519Signer.verifyBase64(other.getPublic(), "payload", signature));
    }

    @Test
    void keyEncodingRoundtrip() {
        KeyPair pair = Ed25519Signer.generateKeyPair();
        PublicKey publicKey = Ed25519Signer.decodePublic(Ed25519Signer.encodePublic(pair.getPublic()));
        PrivateKey privateKey = Ed25519Signer.decodePrivate(
                Ed25519Signer.encodePrivate(pair.getPrivate()));
        assertEquals(pair.getPublic(), publicKey);
        assertEquals(pair.getPrivate(), privateKey);

        String signature = Ed25519Signer.signBase64(privateKey, "after-reload");
        assertTrue(Ed25519Signer.verifyBase64(publicKey, "after-reload", signature));
    }

    @Test
    void invalidBase64SignatureIsRejected() {
        KeyPair pair = Ed25519Signer.generateKeyPair();
        assertFalse(Ed25519Signer.verifyBase64(pair.getPublic(), "x", "not-base64!!!"));
    }

    @Test
    void sha256IsStable() {
        byte[] content = "hello".getBytes();
        assertEquals(Ed25519Signer.sha256Hex(content), Ed25519Signer.sha256Hex(content));
        assertEquals(64, Ed25519Signer.sha256Hex(content).length());
    }
}
