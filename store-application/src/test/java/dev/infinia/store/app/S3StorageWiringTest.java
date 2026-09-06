package dev.infinia.store.app;

import dev.infinia.store.app.service.StatusService;
import dev.infinia.store.contract.api.StatusDtos;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.infrastructure.blob.S3BlobStorage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * store.storage.type=s3 re-points the artifact plane at an S3-compatible
 * bucket: the whole application context must boot against it, and an
 * unreachable bucket must degrade the status page's artifact-storage component
 * instead of taking the store (or the page) down.
 *
 * <p>Dedicated H2 database on purpose: the default test URL is one shared
 * in-memory store per surefire JVM, and this class deliberately records a
 * failing blob probe (samples + an auto-incident) that would otherwise leak
 * into every other context's "fresh data" assertions.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:s3-wiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "store.storage.type=s3",
        // Port 1 has no listener: connection refused, so the probe fails fast
        // instead of hanging on an unroutable address.
        "store.storage.s3.endpoint=http://127.0.0.1:1",
        "store.storage.s3.bucket=store-blobs",
        "store.storage.s3.access-key=store",
        "store.storage.s3.secret-key=store-secret",
        "store.seed.demo-content=false"})
@ActiveProfiles("test")
class S3StorageWiringTest {

    @Autowired
    BlobStorage blobs;

    @Autowired
    StatusService statusService;

    @Test
    void s3BackendServesTheBlobPort() {
        assertInstanceOf(S3BlobStorage.class, blobs);
    }

    @Test
    void unreachableBucketPaintsTheBlobComponentNotThePage() {
        StatusDtos.StatusPageDto page = statusService.page();

        var blob = page.components().stream()
                .filter(component -> "blob".equals(component.key()))
                .findFirst().orElseThrow();
        assertEquals(StatusService.MAJOR_OUTAGE, blob.indicator());
        // The store itself keeps serving; in-process components stay healthy.
        var api = page.components().stream()
                .filter(component -> "api".equals(component.key()))
                .findFirst().orElseThrow();
        assertEquals(StatusService.OPERATIONAL, api.indicator());
        assertTrue(page.components().size() > 1);
    }
}
