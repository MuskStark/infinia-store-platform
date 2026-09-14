package dev.infinia.store.app;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.StatusService;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.StatusPageDto;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository;
import dev.infinia.store.domain.port.StatusRepositories.IncidentRepository;
import dev.infinia.store.domain.port.StatusRepositories.UptimeRepository;
import dev.infinia.store.infrastructure.blob.BlobStorageProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatusSamplingTest {
    @Test
    void readsDoNotConsumeHttpWindowAndFaultsNeedConfirmedStreaks() throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(mock(ResultSet.class));
        var properties = mock(StoreProperties.class);
        when(properties.monitoring()).thenReturn(new StoreProperties.Monitoring(
                null, null, null, null, null, null, null, null, null, null));
        var uptime = mock(UptimeRepository.class);
        var incidents = mock(IncidentRepository.class);
        var registry = new SimpleMeterRegistry();
        try {
            var service = new StatusService(dataSource, properties,
                    mock(BlobStorageProperties.class), mock(BlobStorage.class),
                    mock(UpstreamSourceRepository.class), uptime, incidents, registry,
                    false, false, 2, 2, 180_000L);
            var ok = registry.timer("http.server.requests", "status", "200");
            var errors = registry.timer("http.server.requests", "status", "500");
            // Bootstrap before the scheduler runs initializes exactly one sample.
            var initial = service.page();
            assertSame(initial, service.page());
            clearInvocations(uptime, incidents);
            record(ok, errors, 95, 5);
            assertSame(initial, service.page());
            verifyNoInteractions(uptime, incidents);

            // One failing window alone is 确认中: the confirmed indicator holds.
            service.sample();
            var pending = httpComponent(service.page());
            assertEquals("operational", pending.indicator());
            assertEquals(Boolean.TRUE, pending.pending());
            assertFalse(Boolean.TRUE.equals(pending.stale()));

            // A second failing window confirms the fault.
            record(ok, errors, 95, 5);
            service.sample();
            var failedPage = service.page();
            var failed = httpComponent(failedPage);
            assertEquals("major_outage", failed.indicator());
            assertEquals(Boolean.FALSE, failed.pending());
            clearInvocations(uptime, incidents);
            // Reads are still side-effect free: no window consumed, no samples added.
            assertSame(failedPage, service.page());
            assertSame(failedPage, service.page());
            verifyNoInteractions(uptime, incidents);

            // Recovery is equally deliberate: two clean windows.
            record(ok, errors, 100, 0);
            service.sample();
            assertEquals("major_outage", httpComponent(service.page()).indicator(),
                    "one clean window must not claim recovery");
            record(ok, errors, 100, 0);
            service.sample();
            assertEquals("operational", httpComponent(service.page()).indicator());
        } finally {
            registry.close();
        }
    }

    private static void record(io.micrometer.core.instrument.Timer ok,
            io.micrometer.core.instrument.Timer errors, int okCount, int errorCount) {
        for (int i = 0; i < okCount; i++) ok.record(1, TimeUnit.MILLISECONDS);
        for (int i = 0; i < errorCount; i++) errors.record(1, TimeUnit.MILLISECONDS);
    }

    private static ComponentDto httpComponent(StatusPageDto page) {
        return page.components().stream().filter(c -> c.key().equals("http-quality"))
                .findFirst().orElseThrow();
    }
}
