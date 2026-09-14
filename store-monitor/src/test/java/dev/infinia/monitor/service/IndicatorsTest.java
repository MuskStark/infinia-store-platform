package dev.infinia.monitor.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IndicatorsTest {
    @Test void missingSamplesAreNotHealthy() {
        assertEquals(Indicators.NO_DATA, Indicators.worst(List.of()));
        assertEquals(Indicators.NO_DATA, Indicators.worst(List.of(Indicators.NO_DATA)));
        assertEquals(Indicators.PARTIAL_OUTAGE, Indicators.worst(List.of(
                Indicators.NO_DATA, Indicators.OPERATIONAL, Indicators.PARTIAL_OUTAGE)));
    }
}
