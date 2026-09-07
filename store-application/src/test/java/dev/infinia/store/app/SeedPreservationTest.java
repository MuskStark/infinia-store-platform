package dev.infinia.store.app;

import dev.infinia.store.app.seed.SeedData;
import dev.infinia.store.domain.model.Credential;
import dev.infinia.store.domain.model.StoreUser;
import dev.infinia.store.domain.port.IdentityRepositories;
import dev.infinia.store.domain.port.PasswordHasher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seed hygiene (audit P1-8): seeding may create MISSING demo accounts, but must
 * never reset an existing credential back to the publicly documented demo
 * password — the old "repair" behavior handed the admin account to anyone who
 * read the repo whenever seed was enabled.
 */
@SpringBootTest
@ActiveProfiles("test")
class SeedPreservationTest {

    @Autowired
    SeedData seedData;

    @Autowired
    IdentityRepositories.UserRepository users;

    @Autowired
    IdentityRepositories.CredentialRepository credentials;

    @Autowired
    PasswordHasher hasher;

    /**
     * All test contexts share {@code jdbc:h2:mem:store}, so the rotated password
     * must go back to the documented demo value — other test classes (admin
     * console flows, app-release admin uploads) log in as admin@infinia.local
     * with the demo password.
     */
    @AfterEach
    void restoreDemoAdminPassword() {
        users.findByEmailNormalized(SeedData.ADMIN_EMAIL).ifPresent(admin ->
                credentials.findByUserIdAndType(admin.id, Credential.CredentialType.PASSWORD)
                        .ifPresent(credential -> credentials.save(new Credential(
                                credential.id(), admin.id, Credential.CredentialType.PASSWORD,
                                hasher.hash(SeedData.DEMO_PASSWORD), credential.createdAt()))));
    }

    @Test
    void reSeedingNeverOverwritesAnExistingPassword() {
        StoreUser admin = users.findByEmailNormalized(SeedData.ADMIN_EMAIL).orElseThrow();
        Credential credential = credentials.findByUserIdAndType(admin.id,
                Credential.CredentialType.PASSWORD).orElseThrow();

        // The operator changed the admin password away from the public demo value.
        String changedHash = hasher.hash("Rotated#Admin$42");
        credentials.save(new Credential(credential.id(), admin.id,
                Credential.CredentialType.PASSWORD, changedHash, credential.createdAt()));

        // A reboot triggers the seed again.
        seedData.seed();

        Credential after = credentials.findByUserIdAndType(admin.id,
                Credential.CredentialType.PASSWORD).orElseThrow();
        assertTrue(hasher.matches("Rotated#Admin$42", after.secretHash()),
                "the operator's password must survive re-seeding");
        assertFalse(hasher.matches(SeedData.DEMO_PASSWORD, after.secretHash()),
                "the public demo password must NOT be restored");
    }

    @Test
    void missingDemoAccountsAreCreatedWithoutTouchingExistingOnes() {
        StoreUser admin = users.findByEmailNormalized(SeedData.ADMIN_EMAIL).orElseThrow();
        Credential credential = credentials.findByUserIdAndType(admin.id,
                Credential.CredentialType.PASSWORD).orElseThrow();
        String hashBefore = credential.secretHash();

        // Missing accounts (e.g. an older deployment without the CI account) are
        // still provisioned so the upstream sync keeps working.
        seedData.seed();

        assertTrue(users.findByEmailNormalized(SeedData.CI_EMAIL).isPresent());
        assertTrue(users.findByEmailNormalized(SeedData.REVIEWER_EMAIL).isPresent());
        assertTrue(users.findByEmailNormalized(SeedData.PUBLISHER_EMAIL).isPresent());
        assertTrue(users.findByEmailNormalized(SeedData.USER_EMAIL).isPresent());

        Credential after = credentials.findByUserIdAndType(admin.id,
                Credential.CredentialType.PASSWORD).orElseThrow();
        assertTrue(after.secretHash().equals(hashBefore),
                "existing credentials are untouched by account provisioning");
    }
}
