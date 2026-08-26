package dev.infinia.store.app.security;

import dev.infinia.store.domain.model.Credential;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.PasswordHasher;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;

/** Backs the authorization server's form login with store accounts. */
@Service
public class StoreUserDetailsService implements UserDetailsService {

    private final IdentityRepositories.UserRepository users;
    private final IdentityRepositories.CredentialRepository credentials;
    private final PasswordHasher hasher;

    public StoreUserDetailsService(IdentityRepositories.UserRepository users,
            IdentityRepositories.CredentialRepository credentials, PasswordHasher hasher) {
        this.users = users;
        this.credentials = credentials;
        this.hasher = hasher;
    }

    /** Lookup used by the token customizer; returns null when unknown. */
    public dev.infinia.store.domain.model.StoreUser findByEmailNormalized(String normalized) {
        return users.findByEmailNormalized(normalized).orElse(null);
    }

    /** Authenticate by email + password; used by tests and headless flows. */
    public StoreUser verifyPassword(String email, String rawPassword) {
        StoreUser user = users.findByEmailNormalized(normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        String hash = credentials.findByUserIdAndType(user.id, Credential.CredentialType.PASSWORD)
                .map(Credential::secretHash)
                .orElseThrow(() -> new UsernameNotFoundException("No password credential"));
        if (!hasher.matches(rawPassword, hash)) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        return user;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        StoreUser user = users.findByEmailNormalized(normalize(email))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown account"));
        // The password field is filled with the stored BCrypt hash; form login compares
        // against it via the DelegatingPasswordEncoder (bcrypt ids are stored).
        String hash = credentials.findByUserIdAndType(user.id, Credential.CredentialType.PASSWORD)
                .map(Credential::secretHash)
                .orElseThrow(() -> new UsernameNotFoundException("No password credential"));
        // User.roles(...) adds the ROLE_ prefix itself.
        List<String> roles = user.roles.stream().map(Enum::name).toList();
        return User.withUsername(user.emailNormalized)
                .password("{bcrypt}" + stripBcryptPrefix(hash))
                .roles(roles.toArray(String[]::new))
                .accountLocked(!"ACTIVE".equals(user.status))
                .build();
    }

    /**
     * BCryptPasswordEncoder hashes carry no {id} prefix; the DelegatingPasswordEncoder
     * used by form login requires one, so we add the bcrypt id lazily. Hashes stored
     * by this platform always come from BCryptPasswordEncoder.
     */
    private static String stripBcryptPrefix(String hash) {
        return hash.startsWith("{bcrypt}") ? hash.substring("{bcrypt}".length()) : hash;
    }

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
