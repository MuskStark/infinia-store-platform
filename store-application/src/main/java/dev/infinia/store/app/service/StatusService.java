package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.StatusDtos;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import dev.infinia.store.contract.api.StatusDtos.StatusPageDto;
import dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository;
import dev.infinia.store.domain.port.StatusRepositories.DailySample;
import dev.infinia.store.domain.port.StatusRepositories.Incident;
import dev.infinia.store.domain.port.StatusRepositories.IncidentRepository;
import dev.infinia.store.domain.port.StatusRepositories.UptimeRepository;
import dev.infinia.store.domain.service.UuidV7;
import dev.infinia.store.app.config.StoreProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Public service status (需求：store 服务监控页, modeled on the npm status
 * page): live probes per component, per-UTC-day uptime sampling for the 90-day
 * history bars, and incidents that open/resolve themselves from probe outcomes
 * so the page needs no manual tooling.
 *
 * <p>Indicator ladder (contract {@code StatusIndicator}): operational, degraded,
 * partial_outage, major_outage — plus no_data for history days without samples.</p>
 */
@Service
public class StatusService {

    public static final String OPERATIONAL = "operational";
    public static final String DEGRADED = "degraded";
    public static final String PARTIAL_OUTAGE = "partial_outage";
    public static final String MAJOR_OUTAGE = "major_outage";
    private static final String NO_DATA = "no_data";

    /** Days shown in the per-component history bars. */
    static final int HISTORY_DAYS = 90;

    /** A database round-trip above this is reported as degraded, not down. */
    private static final long DEGRADED_DB_MS = 1500;

    private record Component(String key, boolean probed, String displayName) {}

    /** Display order of the page; probed components also open/close incidents. */
    private static final List<Component> COMPONENTS = List.of(
            new Component("api", false, "Store API"),
            new Component("web", false, "Store Web"),
            new Component("auth", false, "Sign-in & OAuth"),
            new Component("delivery", false, "Update feed & downloads"),
            new Component("database", true, "Database"),
            new Component("blob", true, "Artifact storage"),
            new Component("scanner", false, "Security scanning"),
            new Component("upstream", true, "Upstream sync"));

    /** Probes run off the request thread so a hung database cannot pile up requests. */
    private static final ExecutorService PROBES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "status-probe");
        thread.setDaemon(true);
        return thread;
    });

    private final DataSource dataSource;
    private final StoreProperties properties;
    private final UpstreamSourceRepository upstreams;
    private final UptimeRepository uptimeRepo;
    private final IncidentRepository incidentRepo;

    /** Serializes sample/incident persistence so concurrent requests cannot race an upsert. */
    private final Object recordLock = new Object();

    private LocalDate lastPruneDay = null;

    public StatusService(DataSource dataSource, StoreProperties properties,
            UpstreamSourceRepository upstreams, UptimeRepository uptimeRepo,
            IncidentRepository incidentRepo) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.upstreams = upstreams;
        this.uptimeRepo = uptimeRepo;
        this.incidentRepo = incidentRepo;
    }

    /** Background sampler so downtime is recorded even when nobody is watching. */
    @Scheduled(fixedDelayString = "${store.status.sample-interval-ms:60000}")
    public void sample() {
        page();
    }

    /** Runs the live probes, records today's samples, and assembles the page. */
    public StatusPageDto page() {
        Instant now = Instant.now();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        Map<String, String> live = new LinkedHashMap<>();
        for (Component component : COMPONENTS) {
            live.put(component.key(), probe(component));
        }

        synchronized (recordLock) {
            for (Component component : COMPONENTS) {
                String indicator = live.get(component.key());
                uptimeRepo.record(new DailySample(component.key(), today,
                        OPERATIONAL.equals(indicator) ? 1 : 0,
                        DEGRADED.equals(indicator) ? 1 : 0,
                        isOutage(indicator) ? 1 : 0));
            }
            for (Component component : COMPONENTS) {
                if (component.probed()) {
                    trackIncident(component, live.get(component.key()), now);
                }
            }
            if (!today.equals(lastPruneDay)) {
                uptimeRepo.pruneBefore(today.minusDays(HISTORY_DAYS + 30));
                lastPruneDay = today;
            }
        }

        List<ComponentDto> components = new ArrayList<>();
        for (Component component : COMPONENTS) {
            components.add(componentPage(component.key(), live.get(component.key()), today));
        }
        return new StatusPageDto(worst(live.values()), components, now.toString());
    }

    /** Newest-first incident feed for the "Past Incidents" section. */
    public List<IncidentDto> incidents(int limit) {
        return incidentRepo.findRecent(Math.clamp(limit, 1, 200)).stream()
                .map(i -> new IncidentDto(i.id().toString(), i.component(), i.title(),
                        i.impact(), i.status(), i.startedAt().toString(),
                        i.resolvedAt() == null ? null : i.resolvedAt().toString(),
                        i.updatedAt().toString()))
                .toList();
    }

    private ComponentDto componentPage(String key, String liveIndicator, LocalDate today) {
        LocalDate from = today.minusDays(HISTORY_DAYS - 1L);
        Map<LocalDate, DailySample> byDay = new LinkedHashMap<>();
        for (DailySample sample : uptimeRepo.findSince(key, from)) {
            byDay.put(sample.day(), sample);
        }
        List<DayDto> history = new ArrayList<>(HISTORY_DAYS);
        long ok = 0;
        long total = 0;
        for (int i = 0; i < HISTORY_DAYS; i++) {
            LocalDate day = from.plusDays(i);
            DailySample sample = byDay.get(day);
            if (sample == null || sample.ok() + sample.degraded() + sample.down() == 0) {
                history.add(new DayDto(day.toString(), NO_DATA, null));
                continue;
            }
            long dayTotal = sample.ok() + sample.degraded() + sample.down();
            ok += sample.ok();
            total += dayTotal;
            double uptimePercent = 100.0 * sample.ok() / dayTotal;
            String dayIndicator;
            if (sample.down() > 0) {
                dayIndicator = sample.down() == dayTotal ? MAJOR_OUTAGE : PARTIAL_OUTAGE;
            } else if (sample.degraded() > 0) {
                dayIndicator = DEGRADED;
            } else {
                dayIndicator = OPERATIONAL;
            }
            history.add(new DayDto(day.toString(), dayIndicator, uptimePercent));
        }
        Double uptime90d = total == 0 ? null : Math.round(1000.0 * ok / total) / 10.0;
        return new ComponentDto(key, liveIndicator, uptime90d, history);
    }

    private String probe(Component component) {
        try {
            return switch (component.key()) {
                // In-process components: serving this request is the proof of life.
                case "api", "web", "scanner" -> OPERATIONAL;
                case "database" -> probeDatabase();
                case "blob" -> probeBlobStorage();
                case "delivery" -> worst(List.of(probeDatabase(), probeBlobStorage()));
                case "auth" -> probeDatabase();
                case "upstream" -> probeUpstream();
                default -> OPERATIONAL;
            };
        } catch (Exception e) {
            return MAJOR_OUTAGE;
        }
    }

    private String probeDatabase() {
        Future<String> probe = PROBES.submit(() -> {
            try (Connection connection = dataSource.getConnection();
                    var statement = connection.createStatement()) {
                long start = System.currentTimeMillis();
                try (var rs = statement.executeQuery("SELECT 1")) {
                    rs.next();
                }
                return System.currentTimeMillis() - start > DEGRADED_DB_MS
                        ? DEGRADED : OPERATIONAL;
            }
        });
        try {
            return probe.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            probe.cancel(true);
            return MAJOR_OUTAGE;
        }
    }

    private String probeBlobStorage() {
        try {
            Path dir = Path.of(properties.blobDir());
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "status-probe", ".tmp");
            Files.delete(probe);
            return OPERATIONAL;
        } catch (Exception e) {
            return MAJOR_OUTAGE;
        }
    }

    /**
     * Upstream health rides the sync ledger: an enabled source whose last sync
     * failed degrades the component (indexing keeps serving stale metadata).
     * Sources never synced yet are not an alarm right after first boot.
     */
    private String probeUpstream() {
        for (var source : upstreams.findAll()) {
            if (source.enabled() && Boolean.FALSE.equals(source.lastSyncOk())) {
                return DEGRADED;
            }
        }
        return OPERATIONAL;
    }

    /**
     * Auto incident lifecycle: a failing probe opens (or keeps open) one
     * incident per component, a recovered probe resolves it. Only really
     * probed components participate — derived ones share the database's fate.
     */
    private void trackIncident(Component component, String indicator, Instant now) {
        var existing = incidentRepo.findUnresolvedByComponent(component.key());
        if (isOutage(indicator) || DEGRADED.equals(indicator)) {
            String impact = isOutage(indicator) ? "outage" : "degraded";
            if (existing.isPresent()) {
                Incident incident = existing.get();
                incidentRepo.save(new Incident(incident.id(), incident.component(),
                        incident.title(), impact, incident.status(),
                        incident.startedAt(), null, now));
            } else {
                incidentRepo.save(new Incident(UuidV7.generate(), component.key(),
                        component.displayName() + ("outage".equals(impact)
                                ? " is unavailable" : " is degraded"),
                        impact, "investigating", now, null, now));
            }
        } else if (existing.isPresent()) {
            Incident incident = existing.get();
            incidentRepo.save(new Incident(incident.id(), incident.component(),
                    incident.title(), incident.impact(), "resolved",
                    incident.startedAt(), now, now));
        }
    }

    private static boolean isOutage(String indicator) {
        return PARTIAL_OUTAGE.equals(indicator) || MAJOR_OUTAGE.equals(indicator);
    }

    private static String worst(Iterable<String> indicators) {
        int rank = 0;
        for (String indicator : indicators) {
            rank = Math.max(rank, rank(indicator));
        }
        return switch (rank) {
            case 1 -> DEGRADED;
            case 2 -> PARTIAL_OUTAGE;
            case 3 -> MAJOR_OUTAGE;
            default -> OPERATIONAL;
        };
    }

    private static int rank(String indicator) {
        return switch (indicator) {
            case DEGRADED -> 1;
            case PARTIAL_OUTAGE -> 2;
            case MAJOR_OUTAGE -> 3;
            default -> 0;
        };
    }
}
