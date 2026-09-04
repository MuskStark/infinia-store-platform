package dev.infinia.store.infrastructure.persistence;

import dev.infinia.store.domain.port.StatusRepositories;
import dev.infinia.store.domain.port.StatusRepositories.DailySample;
import dev.infinia.store.domain.port.StatusRepositories.Incident;
import dev.infinia.store.infrastructure.persistence.entity.ServiceIncidentEntity;
import dev.infinia.store.infrastructure.persistence.entity.ServiceUptimeDayEntity;
import dev.infinia.store.infrastructure.persistence.repository.ServiceIncidentJpaRepository;
import dev.infinia.store.infrastructure.persistence.repository.ServiceUptimeDayJpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JPA adapter for the service-status page uptime buckets and incidents. */
@Component
public class StatusAdapter implements StatusRepositories.UptimeRepository,
        StatusRepositories.IncidentRepository {

    private final ServiceUptimeDayJpaRepository uptime;
    private final ServiceIncidentJpaRepository incidents;

    public StatusAdapter(ServiceUptimeDayJpaRepository uptime,
            ServiceIncidentJpaRepository incidents) {
        this.uptime = uptime;
        this.incidents = incidents;
    }

    @Override
    @Transactional
    public void record(DailySample sample) {
        ServiceUptimeDayEntity e = uptime
                .findById(new ServiceUptimeDayEntity.Key(sample.component(), sample.day()))
                .orElseGet(() -> {
                    ServiceUptimeDayEntity fresh = new ServiceUptimeDayEntity();
                    fresh.component = sample.component();
                    fresh.day = sample.day();
                    fresh.ok = 0;
                    fresh.degraded = 0;
                    fresh.down = 0;
                    return fresh;
                });
        e.ok += sample.ok();
        e.degraded += sample.degraded();
        e.down += sample.down();
        uptime.save(e);
    }

    @Override
    public List<DailySample> findSince(String component, LocalDate from) {
        return uptime.findByComponentAndDayGreaterThanEqual(component, from).stream()
                .map(e -> new DailySample(e.component, e.day, e.ok, e.degraded, e.down))
                .toList();
    }

    /** Keeps the bucket table bounded to the observation window (plus headroom). */
    @Override
    @Transactional
    public int pruneBefore(LocalDate day) {
        return Math.toIntExact(uptime.deleteByDayBefore(day));
    }

    @Override
    public Optional<Incident> findUnresolvedByComponent(String component) {
        return incidents.findFirstByComponentAndResolvedAtIsNull(component)
                .map(StatusAdapter::toDomain);
    }

    @Override
    public Incident save(Incident incident) {
        ServiceIncidentEntity e = new ServiceIncidentEntity();
        e.incidentId = incident.id();
        e.component = incident.component();
        e.title = incident.title();
        e.impact = incident.impact();
        e.status = incident.status();
        e.startedAt = incident.startedAt();
        e.resolvedAt = incident.resolvedAt();
        e.updatedAt = incident.updatedAt();
        incidents.save(e);
        return incident;
    }

    @Override
    public List<Incident> findRecent(int limit) {
        return incidents.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit)).stream()
                .map(StatusAdapter::toDomain).toList();
    }

    private static Incident toDomain(ServiceIncidentEntity e) {
        return new Incident(e.incidentId, e.component, e.title, e.impact, e.status,
                e.startedAt, e.resolvedAt, e.updatedAt);
    }
}
