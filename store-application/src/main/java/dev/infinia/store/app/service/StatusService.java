package dev.infinia.store.app.service;

import dev.infinia.store.contract.api.StatusDtos;
import dev.infinia.store.contract.api.StatusDtos.ComponentDto;
import dev.infinia.store.contract.api.StatusDtos.DayDto;
import dev.infinia.store.contract.api.StatusDtos.IncidentDto;
import dev.infinia.store.contract.api.StatusDtos.StatusPageDto;
import dev.infinia.store.domain.port.BlobStorage;
import dev.infinia.store.domain.port.PublishingRepositories.UpstreamSourceRepository;
import dev.infinia.store.domain.port.StatusRepositories.DailySample;
import dev.infinia.store.domain.port.StatusRepositories.Incident;
import dev.infinia.store.domain.port.StatusRepositories.IncidentRepository;
import dev.infinia.store.domain.port.StatusRepositories.UptimeRepository;
import dev.infinia.store.domain.service.UuidV7;
import dev.infinia.store.app.config.StoreProperties;
import dev.infinia.store.infrastructure.blob.BlobStorageProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.FileStore;
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

    /**
     * Display order of the page; probed components also open/close incidents.
     * The host-level probes (disk, memory, JVM) feed one merged component —
     * host-load, worst-of — because operators act on "the host is loaded",
     * not on three separately blinking cells; db-pool and http-quality stay
     * separate as application-behaviour signals.
     */
    private static final List<Component> COMPONENTS = List.of(
            new Component("api", false, "Store API"),
            new Component("web", false, "Store Web"),
            new Component("auth", false, "Sign-in & OAuth"),
            new Component("delivery", false, "Update feed & downloads"),
            new Component("database", true, "Database"),
            new Component("blob", true, "Artifact storage"),
            new Component("scanner", false, "Security scanning"),
            new Component("upstream", true, "Upstream sync"),
            new Component("host-load", true, "Host server load"),
            new Component("db-pool", true, "Database connection pool"),
            new Component("http-quality", true, "HTTP response quality"));

    /** Probes run off the request thread so a hung database cannot pile up requests. */
    private static final ExecutorService PROBES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "status-probe");
        thread.setDaemon(true);
        return thread;
    });

    private final DataSource dataSource;
    private final StoreProperties properties;
    private final BlobStorageProperties storageProperties;
    private final BlobStorage blobs;
    private final UpstreamSourceRepository upstreams;
    private final UptimeRepository uptimeRepo;
    private final IncidentRepository incidentRepo;
    private final MeterRegistry registry;

    /** Serializes sample/incident persistence so concurrent requests cannot race an upsert. */
    private final Object recordLock = new Object();

    private LocalDate lastPruneDay = null;

    /** Previous http.server.requests counters; window deltas judge HTTP quality. */
    private volatile HttpCounters lastHttpCounters = null;

    private final boolean seedEnabled;
    /** Demo history variety is opt-in per profile: tests keep the honest empty comb. */
    private final boolean statusHistorySeeded;

    public StatusService(DataSource dataSource, StoreProperties properties,
            BlobStorageProperties storageProperties, BlobStorage blobs,
            UpstreamSourceRepository upstreams, UptimeRepository uptimeRepo,
            IncidentRepository incidentRepo, MeterRegistry registry,
            @Value("${store.seed.enabled:false}") boolean seedEnabled,
            @Value("${store.seed.status-history:false}") boolean statusHistorySeeded) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.storageProperties = storageProperties;
        this.blobs = blobs;
        this.upstreams = upstreams;
        this.uptimeRepo = uptimeRepo;
        this.incidentRepo = incidentRepo;
        this.registry = registry;
        this.seedEnabled = seedEnabled;
        this.statusHistorySeeded = statusHistorySeeded;
    }

    /** Background sampler so downtime is recorded even when nobody is watching. */
    @Scheduled(fixedDelayString = "${store.status.sample-interval-ms:60000}")
    public void sample() {
        page();
    }

    /**
     * Demo environments (local/dev: {@code store.seed.enabled=true}) boot with an
     * empty samples table, which would paint 89 gray "no data" days next to the
     * single day the server has been up. Backfill a believable history instead:
     * mostly operational with deterministic sprinkles of degraded, partial and
     * major outage days plus a few empty cells, so the monitoring hive shows
     * every state the page can render. Only gap days are written — real sample
     * days are never overwritten — and the last five days stay healthy so the
     * page opens on a living hive. Real deployments leave the seed switch off
     * and keep their honest history.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillDemoHistory() {
        if (!seedEnabled || !statusHistorySeeded) {
            return;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(HISTORY_DAYS - 1L);
        synchronized (recordLock) {
            for (int componentIndex = 0; componentIndex < COMPONENTS.size(); componentIndex++) {
                Component component = COMPONENTS.get(componentIndex);
                Map<LocalDate, DailySample> sampled = new LinkedHashMap<>();
                for (DailySample sample : uptimeRepo.findSince(component.key(), from)) {
                    sampled.put(sample.day(), sample);
                }
                for (int i = 1; i < HISTORY_DAYS; i++) {
                    LocalDate day = today.minusDays(i);
                    if (sampled.containsKey(day)) {
                        continue; // never overwrite a real sample day
                    }
                    if (i < 5) {
                        uptimeRepo.record(new DailySample(component.key(), day, 1, 0, 0));
                        continue;
                    }
                    int roll = (componentIndex * 31 + i * 7) % 23;
                    if (roll == 3) {
                        // 性能下降: some requests slow, none failing
                        uptimeRepo.record(new DailySample(component.key(), day, 1, 2, 0));
                    } else if (roll == 7) {
                        // 局部故障: a share of requests failing
                        uptimeRepo.record(new DailySample(component.key(), day, 1, 0, 1));
                    } else if (roll == 11) {
                        // 严重故障: everything down that day
                        uptimeRepo.record(new DailySample(component.key(), day, 0, 0, 2));
                    } else if (roll == 17) {
                        // 暂无数据: spare comb, no samples that day
                    } else {
                        uptimeRepo.record(new DailySample(component.key(), day, 1, 0, 0));
                    }
                }
            }
        }
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
                case "host-load" -> probeHostLoad();
                case "db-pool" -> probeDbPool();
                case "http-quality" -> probeHttpQuality();
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
        // Off the request thread with a hard cap: with S3 storage this probe
        // makes real network calls, and the status page must stay reachable
        // exactly when the backend is not answering.
        Future<String> probe = PROBES.submit(() -> {
            blobs.checkWritable();
            return OPERATIONAL;
        });
        try {
            return probe.get(6, TimeUnit.SECONDS);
        } catch (Exception e) {
            probe.cancel(true);
            return MAJOR_OUTAGE;
        }
    }

    /**
     * Host server load: worst-of storage capacity, memory headroom and JVM
     * heap pressure — one honest cell for "the host is in trouble", with the
     * specific probe logged at debug level for diagnosis.
     */
    private String probeHostLoad() throws Exception {
        String disk = probeHostDisk();
        String memory = probeHostMemory();
        String jvm = probeJvmHeap();
        String worst = worst(List.of(disk, memory, jvm));
        if (!OPERATIONAL.equals(worst)) {
            org.slf4j.LoggerFactory.getLogger(StatusService.class)
                    .debug("host-load probes: disk={} memory={} jvm={}", disk, memory, jvm);
        }
        return worst;
    }

    /**
     * Remaining capacity of the filesystem holding the store's local state —
     * the blob directory with local storage, or the key directory when
     * artifacts live in object storage (S3), where that volume still carries
     * keys, git exports and logs. Unquotable filesystems (total ≤ 0) report
     * operational rather than failing the page on a platform quirk.
     */
    private String probeHostDisk() throws Exception {
        Path dir = Path.of(storageProperties.s3Mode()
                ? properties.keyDir() : properties.blobDir());
        Files.createDirectories(dir);
        FileStore store = Files.getFileStore(dir);
        long total = store.getTotalSpace();
        if (total <= 0) {
            return OPERATIONAL;
        }
        double freePercent = 100.0 * store.getUsableSpace() / total;
        return classifyFreePercent(freePercent,
                properties.monitoring().diskCriticalFreePercent(),
                properties.monitoring().diskWarnFreePercent());
    }

    /**
     * Host memory headroom. On Linux the JDK's free-physical counter is
     * {@code MemFree} — near-zero on any healthy box because the kernel uses
     * spare RAM for page cache — so /proc/meminfo's {@code MemAvailable} is
     * authoritative when present; elsewhere (macOS, Windows) the free-size
     * counter is the best available signal.
     */
    private String probeHostMemory() {
        var os = (com.sun.management.OperatingSystemMXBean)
                ManagementFactory.getOperatingSystemMXBean();
        long total = os.getTotalPhysicalMemorySize();
        if (total <= 0) {
            return OPERATIONAL;
        }
        long available = linuxMemAvailable();
        if (available < 0) {
            available = os.getFreePhysicalMemorySize();
        }
        double freePercent = 100.0 * available / total;
        return classifyFreePercent(freePercent,
                properties.monitoring().memoryCriticalFreePercent(),
                properties.monitoring().memoryWarnFreePercent());
    }

    /** {@code MemAvailable} from /proc/meminfo in bytes; −1 when not Linux. */
    private static long linuxMemAvailable() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) * 1024L;
                }
            }
        } catch (Exception ignored) {
            // not Linux, or unreadable — caller falls back to the MXBean counter
        }
        return -1;
    }

    private String probeJvmHeap() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        if (heap.getMax() <= 0) {
            return OPERATIONAL;
        }
        double usedPercent = 100.0 * heap.getUsed() / heap.getMax();
        return usedPercent >= properties.monitoring().heapWarnUsedPercent()
                ? DEGRADED : OPERATIONAL;
    }

    /**
     * Hikari pool saturation. A non-Hikari datasource (tests, exotic setups)
     * reports operational — an unknown pool must not paint the page red.
     */
    private String probeDbPool() throws Exception {
        HikariDataSource pool;
        if (dataSource instanceof HikariDataSource hikari) {
            pool = hikari;
        } else if (dataSource.isWrapperFor(HikariDataSource.class)) {
            pool = dataSource.unwrap(HikariDataSource.class);
        } else {
            return OPERATIONAL;
        }
        var metrics = pool.getHikariPoolMXBean();
        if (metrics == null) {
            return OPERATIONAL; // pool not started yet
        }
        return classifyPool(metrics.getActiveConnections(), metrics.getTotalConnections(),
                metrics.getThreadsAwaitingConnection(), properties.monitoring());
    }

    /**
     * 5xx ratio and p95 latency over the http.server.requests counters observed
     * since the previous check. Windows with too little traffic skip the ratio
     * so a single error on a quiet instance cannot flash a false outage.
     */
    private String probeHttpQuality() {
        HttpCounters current = httpCounters();
        HttpCounters previous = lastHttpCounters;
        lastHttpCounters = current;
        if (previous == null || current.total() < previous.total()) {
            return OPERATIONAL; // first window after boot, or a registry reset
        }
        return classifyHttpWindow(current.total() - previous.total(),
                current.errors() - previous.errors(), httpP95Millis(),
                properties.monitoring());
    }

    private HttpCounters httpCounters() {
        long total = 0;
        long errors = 0;
        for (Timer timer : registry.find("http.server.requests").timers()) {
            long count = timer.count();
            String status = timer.getId().getTag("status");
            total += count;
            if (status != null && status.startsWith("5")) {
                errors += count;
            }
        }
        return new HttpCounters(total, errors);
    }

    /**
     * Count-weighted mean of per-route p95 values — an approximation that needs
     * no second histogram; the value is published by the
     * {@code management.metrics.distribution.percentiles} config on the timer
     * and is absent (−1) when that config is missing.
     */
    private double httpP95Millis() {
        long weightedCount = 0;
        double weightedSum = 0;
        for (Timer timer : registry.find("http.server.requests").timers()) {
            long count = timer.count();
            double p95Nanos = -1;
            for (var valueAtPercentile : timer.takeSnapshot().percentileValues()) {
                if (valueAtPercentile.percentile() == 0.95) {
                    p95Nanos = valueAtPercentile.value(); // timer snapshots report nanos
                }
            }
            if (count > 0 && p95Nanos > 0) {
                weightedCount += count;
                weightedSum += p95Nanos / 1_000_000.0 * count;
            }
        }
        return weightedCount == 0 ? -1 : weightedSum / weightedCount;
    }

    /** Below critical free space is an outage, below warn is degraded; unit: percent. */
    public static String classifyFreePercent(double freePercent, int criticalBelow, int warnBelow) {
        if (freePercent <= criticalBelow) {
            return MAJOR_OUTAGE;
        }
        return freePercent <= warnBelow ? DEGRADED : OPERATIONAL;
    }

    public static String classifyPool(int active, int total, int awaiting,
            StoreProperties.Monitoring thresholds) {
        if (awaiting >= thresholds.poolAwaitingOutageThreads()) {
            return MAJOR_OUTAGE;
        }
        if (awaiting > 0 || (total > 0
                && 100.0 * active / total >= thresholds.poolWarnActivePercent())) {
            return DEGRADED;
        }
        return OPERATIONAL;
    }

    public static String classifyHttpWindow(long windowTotal, long windowErrors, double p95Millis,
            StoreProperties.Monitoring thresholds) {
        if (windowTotal >= 10) {
            double errorPercent = 100.0 * windowErrors / windowTotal;
            if (errorPercent >= thresholds.http5xxCriticalPercent()) {
                return MAJOR_OUTAGE;
            }
            if (errorPercent >= thresholds.http5xxWarnPercent()) {
                return PARTIAL_OUTAGE;
            }
        }
        if (p95Millis > 0 && p95Millis >= thresholds.httpP95WarnMillis()) {
            return DEGRADED;
        }
        return OPERATIONAL;
    }

    /** http.server.requests counters feeding the HTTP-quality window deltas. */
    private record HttpCounters(long total, long errors) {}

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
