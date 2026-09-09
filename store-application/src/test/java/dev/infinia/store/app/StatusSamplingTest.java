package dev.infinia.store.app;

import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.app.service.StatusService;
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
    void readsDoNotConsumeHttpWindowOrResolveFaultBeforeNextSample() throws Exception {
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
                    mock(UpstreamSourceRepository.class), uptime, incidents, registry, false, false);
            var ok = registry.timer("http.server.requests", "status", "200");
            var errors = registry.timer("http.server.requests", "status", "500");
            // Bootstrap before the scheduler runs initializes exactly one sample.
            var initial = service.page();
            assertSame(initial, service.page());
            clearInvocations(uptime, incidents);
            for (int i = 0; i < 95; i++) ok.record(1, TimeUnit.MILLISECONDS);
            for (int i = 0; i < 5; i++) errors.record(1, TimeUnit.MILLISECONDS);
            assertSame(initial, service.page());
            verifyNoInteractions(uptime, incidents);

            service.sample();
            var failed = service.page();
            assertEquals("major_outage", httpIndicator(failed));
            clearInvocations(uptime, incidents);
            // Blackbox probe followed by mirror fetch must see the same failure.
            assertSame(failed, service.page());
            assertSame(failed, service.page());
            verifyNoInteractions(uptime, incidents);

            for (int i = 0; i < 100; i++) ok.record(1, TimeUnit.MILLISECONDS);
            assertSame(failed, service.page());
            service.sample();
            assertEquals("operational", httpIndicator(service.page()));
            assertNotSame(failed, service.page());
        } finally {
            registry.close();
        }
    }

    private static String httpIndicator(StatusPageDto page) {
        return page.components().stream().filter(c -> c.key().equals("http-quality"))
                .findFirst().orElseThrow().indicator();
    }
}
