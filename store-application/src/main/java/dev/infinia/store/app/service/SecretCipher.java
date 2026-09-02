package dev.infinia.store.app.service;

import dev.infinia.store.app.config.StoreProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM sealing for remote-database credentials (远程数据库配置). The key
 * lives next to the platform's other key material ({@code store.key-dir}); the
 * sealed form is {@code base64(iv ‖ ciphertext)} so stored rows never contain
 * plaintext passwords.
 */
@Component
public class SecretCipher {

    private static final String KEY_FILE = "remote-db.aeskey";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(StoreProperties properties) {
        this.key = loadOrCreate(Path.of(properties.keyDir()).resolve(KEY_FILE));
    }

    public String seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + sealed.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(sealed, 0, out, IV_BYTES, sealed.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Credential sealing failed", e);
        }
    }

    public String unseal(String sealed) {
        try {
            byte[] in = Base64.getDecoder().decode(sealed);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, in, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(in, IV_BYTES, in.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Credential unsealing failed", e);
        }
    }

    private static SecretKey loadOrCreate(Path file) {
        try {
            if (Files.exists(file)) {
                byte[] raw = Base64.getDecoder().decode(
                        Files.readString(file, StandardCharsets.UTF_8).trim());
                return new SecretKeySpec(raw, "AES");
            }
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey generated = generator.generateKey();
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Base64.getEncoder().encodeToString(generated.getEncoded()),
                    StandardCharsets.UTF_8);
            Files.move(tmp, file);
            return generated;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot load/create " + file, e);
        }
    }
}
