package dev.infinia.store.domain.port;

/** Credential hashing port; implemented with BCrypt in store-infrastructure. */
public interface PasswordHasher {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String hash);
}
