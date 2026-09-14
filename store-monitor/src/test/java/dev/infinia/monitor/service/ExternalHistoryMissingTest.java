package dev.infinia.monitor.service;

import dev.infinia.monitor.MonitorPropertiesTest;
import dev.infinia.monitor.persistence.ExternalDayRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ExternalHistoryMissingTest {
    @Test void missingObservationDoesNotIncreaseAvailableSamples() {
        var repository = mock(ExternalDayRepository.class);
        var history = new ExternalHistory(repository, MonitorPropertiesTest.defaults());
        history.record(Indicators.NO_DATA, Instant.now());
        assertEquals(Indicators.NO_DATA, history.liveIndicator());
        verifyNoInteractions(repository);
    }
    @Test void unknownObservationIsRejected() {
        var repository = mock(ExternalDayRepository.class);
        var history = new ExternalHistory(repository, MonitorPropertiesTest.defaults());
        assertThrows(IllegalArgumentException.class, () -> history.record("unknown", Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> history.observe("unknown", Instant.now()));
        verifyNoInteractions(repository);
    }
}
