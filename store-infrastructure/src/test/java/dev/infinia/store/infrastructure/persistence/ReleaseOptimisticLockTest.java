package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.contract.semver.SemVer;
import dev.infinia.store.contract.type.ReleaseStatus;
import dev.infinia.store.domain.model.Release;
import dev.infinia.store.infrastructure.persistence.repository.ReleaseJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Release optimistic locking (audit P1-4): the async scan worker and human
 * reviewers mutate the same release row. A stale domain snapshot must fail its
 * save instead of silently overwriting the concurrent decision, and consecutive
 * saves of one domain object must carry the bumped row version forward.
 */
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
// The JPA slice only registers @Repository beans; the adapter is a plain
// @Component, so import it explicitly to exercise its save() path.
@Import(ReleaseRepositoryAdapter.class)
@TestPropertySource(properties = {"spring.datasource.url="
        + "jdbc:h2:mem:release-optlock;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
        + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect"})
class ReleaseOptimisticLockTest {

    @Autowired
    ReleaseRepositoryAdapter releases;

    @Autowired
    ReleaseJpaRepository jpa;

    @Test
    void staleSnapshotSaveFailsInsteadOfOverwriting() {
        UUID id = seedRelease("1.0.0");

        // Two writers load the same version (reviewer + scan worker).
        Release reviewerCopy = releases.findById(id).orElseThrow();
        Release scanCopy = releases.findById(id).orElseThrow();
        assertThat(reviewerCopy.rowVersion).isEqualTo(scanCopy.rowVersion);

        // The reviewer commits first: REJECTED, version bumps.
        reviewerCopy.status = ReleaseStatus.REJECTED;
        releases.save(reviewerCopy);

        // The scan worker's stale snapshot must not overwrite the rejection back
        // to IN_REVIEW — the save fails loudly.
        scanCopy.status = ReleaseStatus.IN_REVIEW;
        assertThrows(OptimisticLockingFailureException.class,
                () -> releases.save(scanCopy));
        assertThat(releases.findById(id).orElseThrow().status)
                .isEqualTo(ReleaseStatus.REJECTED);
    }

    @Test
    void consecutiveSavesOfOneDomainObjectStayValid() {
        UUID id = seedRelease("2.0.0");
        Release release = releases.findById(id).orElseThrow();
        long initialVersion = release.rowVersion;

        release.status = ReleaseStatus.UPLOADING;
        releases.save(release);
        release.status = ReleaseStatus.SCANNING;
        releases.save(release);

        // UpstreamSyncService-style multi-save sequences must keep working: the
        // repository writes the bumped version back into the domain object.
        assertThat(release.rowVersion).isGreaterThan(initialVersion);
        assertThat(releases.findById(id).orElseThrow().status)
                .isEqualTo(ReleaseStatus.SCANNING);
    }

    /**
     * Cross-transaction stale write (the production shape of audit P1-4): the scan
     * worker and the reviewer run in SEPARATE transactions and persistence
     * contexts. Both load version N; the reviewer commits; the scan worker's save
     * of its stale snapshot must then fail with the Spring-translated optimistic
     * lock exception instead of silently overwriting the decision. Runs outside
     * the slice's default test transaction so every repository call opens its own.
     *
     * <p>A wall-clock overlapping race is deliberately NOT asserted here: H2's
     * MVStore does not re-evaluate the {@code WHERE row_version=?} predicate after
     * waiting on a row lock, so a fully overlapped double-write can legitimately
     * succeed on H2 while failing on PostgreSQL. The sequential stale-snapshot
     * ordering below is the deterministic guarantee the row-version column gives.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void staleWriteInASeparateTransactionFailsCrossContext() throws Exception {
        UUID id = seedRelease("3.0.0");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Both writers read version 0, each in its own transaction/context.
            Release scanWorkerCopy = releases.findById(id).orElseThrow();
            Release reviewerCopy = releases.findById(id).orElseThrow();
            assertThat(scanWorkerCopy.rowVersion).isEqualTo(reviewerCopy.rowVersion);

            // The reviewer's decision commits first.
            Exception reviewerFailure = pool.submit(() -> saveQuietly(reviewerCopy,
                    ReleaseStatus.REJECTED)).get(30, TimeUnit.SECONDS);
            assertThat(reviewerFailure).as("first writer commits cleanly").isNull();
            assertThat(reviewerCopy.rowVersion)
                    .as("the bumped row version is written back to the domain object")
                    .isGreaterThan(scanWorkerCopy.rowVersion);

            // The scan worker's late, stale save must lose — translated, not raw
            // jakarta.persistence.OptimisticLockException, so callers can catch it.
            assertThat(releases.findById(id).orElseThrow().status)
                    .isEqualTo(ReleaseStatus.REJECTED);
            Exception scanFailure = pool.submit(() -> saveQuietly(scanWorkerCopy,
                    ReleaseStatus.IN_REVIEW)).get(30, TimeUnit.SECONDS);
            assertThat(scanFailure)
                    .as("the stale cross-transaction snapshot must be rejected")
                    .isInstanceOf(OptimisticLockingFailureException.class);

            // The committed decision stands.
            assertThat(releases.findById(id).orElseThrow().status)
                    .isEqualTo(ReleaseStatus.REJECTED);
        } finally {
            pool.shutdownNow();
        }
    }

    private Exception saveQuietly(Release release, ReleaseStatus target) {
        try {
            release.status = target;
            releases.save(release);
            return null;
        } catch (Exception e) {
            return e;
        }
    }

    private UUID seedRelease(String version) {
        Release release = new Release();
        release.id = UUID.randomUUID();
        release.listingId = UUID.randomUUID();
        release.version = SemVer.parse(version);
        release.status = ReleaseStatus.SCANNING;
        release.createdAt = Instant.now();
        releases.save(release);
        return release.id;
    }
}
