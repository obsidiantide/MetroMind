package com.metromind.sim;

import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.BenchmarkJobStore;
import com.metromind.incident.BenchmarkJobs;
import com.metromind.incident.BenchmarkReport;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.DelayPropagationAnalyzer;
import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentImpactReport;
import com.metromind.incident.IncidentRecord;
import com.metromind.incident.StrategyBenchmark;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ToDoubleFunction;

/** Owns the live engine, advances it on a timer and fans snapshots out to listeners. */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationEngine engine;
    private final SimulationProperties props;
    private final List<SnapshotListener> listeners;
    private final IncidentHistory history;
    private final DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
    private final StrategyBenchmark benchmark = new StrategyBenchmark();
    /** One benchmark job at a time: each already keeps a core busy, and jobs queue behind it. */
    private final ExecutorService benchmarkExecutor = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("benchmark-job").daemon(true).factory());
    private final BenchmarkJobs benchmarkJobs;
    private final Timer tickTimer;
    private final MeterRegistry meters;
    private volatile boolean paused;
    private volatile SimulationSnapshot last;

    public SimulationService(MetroNetwork network, SimulationProperties props, List<SnapshotListener> listeners,
                             IncidentHistory history, MeterRegistry meters,
                             ObjectProvider<BenchmarkJobStore> jobStore) {
        this.props = props;
        this.benchmarkJobs = new BenchmarkJobs(benchmarkExecutor, 20, Clock.systemUTC(),
                jobStore.getIfAvailable(() -> BenchmarkJobStore.NONE));
        this.listeners = listeners;
        this.history = history;
        this.engine = new SimulationEngine(network, props.startSeconds(), HeadwayStrategy.byName(props.strategy()));
        this.last = engine.snapshot();
        this.meters = meters;
        this.tickTimer = Timer.builder("metromind.sim.tick")
                .description("Wall-clock time to advance the live engine by one tick and take its snapshot")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
        gauge("metromind.sim.time", "Simulated seconds since midnight", "seconds", SimulationSnapshot::simTime);
        gauge("metromind.passengers.waiting", "Passengers waiting at all stations", "passengers",
                snap -> snap.metrics().totalWaiting());
        gauge("metromind.trains.delay.average", "Average lateness of trains in service", "seconds",
                snap -> snap.metrics().averageDelaySeconds());
        gauge("metromind.trains.delay.max", "Lateness of the latest train in service", "seconds",
                snap -> snap.metrics().maxDelaySeconds());
        gauge("metromind.trains.in.service", "Trains running on the lines", "trains",
                snap -> snap.metrics().trainsInService());
        gauge("metromind.headway.cv", "Coefficient of variation of headways: 0 is perfectly regular", null,
                snap -> snap.metrics().headwayCoefficientOfVariation());
        for (Line line : network.lines()) {
            if (!line.branches().isEmpty()) {
                Gauge.builder("metromind.headway.trunk.cv", this,
                                service -> service.last.metrics().trunkHeadwayCv().getOrDefault(line.id(), 0.0))
                        .description("Headway CV of the combined service of a branched line's patterns on the trunk "
                                + "they share: 0 is perfectly regular")
                        .tag("line", line.id())
                        .register(meters);
            }
        }
    }

    /** A gauge read from the latest published snapshot, so scraping never takes the engine lock. */
    private void gauge(String name, String description, String unit, ToDoubleFunction<SimulationSnapshot> value) {
        Gauge.builder(name, this, service -> value.applyAsDouble(service.last))
                .description(description)
                .baseUnit(unit)
                .register(meters);
    }

    private void countIncident(IncidentRecord.Kind kind) {
        meters.counter("metromind.incidents.injected", "kind", kind.name().toLowerCase(Locale.ROOT)).increment();
    }

    @Scheduled(fixedRateString = "${metromind.sim.tick-millis:500}")
    public void tick() {
        SimulationSnapshot snap;
        synchronized (engine) {
            Timer.Sample sample = Timer.start();
            if (!paused) {
                engine.run(props.speed() * props.tickMillis() / 1000.0, 1.0);
            }
            snap = engine.snapshot();
            sample.stop(tickTimer);
        }
        last = snap;
        for (SnapshotListener l : listeners) {
            try {
                l.onSnapshot(snap);
            } catch (RuntimeException e) {
                log.warn("Snapshot listener {} failed: {}", l.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    public SimulationSnapshot snapshot() {
        return last;
    }

    public void injectDelay(String trainId, double seconds) {
        synchronized (engine) {
            engine.injectDelay(trainId, seconds);
            history.record(engine.simTime(), IncidentRecord.Kind.DELAY, trainId, seconds, "");
            countIncident(IncidentRecord.Kind.DELAY);
        }
    }

    public void injectBreakdown(String trainId, double repairSeconds) {
        synchronized (engine) {
            engine.injectBreakdown(trainId, repairSeconds);
            history.record(engine.simTime(), IncidentRecord.Kind.BREAKDOWN, trainId, repairSeconds, "");
            countIncident(IncidentRecord.Kind.BREAKDOWN);
        }
    }

    public void closeSection(String lineId, String fromStationId, String toStationId, double seconds,
                             ClosureResponse response) {
        synchronized (engine) {
            engine.closeSection(lineId, fromStationId, toStationId, seconds, response);
            history.record(engine.simTime(), IncidentRecord.Kind.SECTION_CLOSURE, lineId, seconds,
                    "%s-%s, %s".formatted(fromStationId, toStationId, response));
            countIncident(IncidentRecord.Kind.SECTION_CLOSURE);
        }
    }

    public void closeStation(String stationId, double seconds) {
        synchronized (engine) {
            engine.closeStation(stationId, seconds);
            history.record(engine.simTime(), IncidentRecord.Kind.STATION_CLOSURE, stationId, seconds, "");
            countIncident(IncidentRecord.Kind.STATION_CLOSURE);
        }
    }

    /** Incidents injected so far, most recent first. */
    public List<IncidentRecord> incidentHistory(int limit) {
        return history.recent(limit);
    }

    public void setStrategy(String name) {
        synchronized (engine) {
            engine.setStrategy(HeadwayStrategy.byName(name));
        }
    }

    public List<Departure> departures(String stationId, double horizonSeconds) {
        synchronized (engine) {
            return engine.departures(stationId, horizonSeconds);
        }
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    public DelayImpactReport analyzeDelay(String trainId, double seconds, double horizonSeconds) {
        SimulationEngine fork;
        synchronized (engine) {
            fork = engine.copy();
        }
        DelayImpactReport report = analysisTimer("delay").record(
                () -> analyzer.analyze(fork, trainId, seconds, horizonSeconds, HeadwayStrategy.all()));
        history.recordAnalysis(fork.simTime(), report);
        return report;
    }

    /**
     * What-if for any incident, such as a track-section or station closure, compared across every headway strategy
     * from a copy of the live state. Unlike delay analyses these reports are not kept in the history.
     */
    public IncidentImpactReport analyzeIncident(BenchmarkReport.Scenario incident, double horizonSeconds) {
        SimulationEngine fork;
        synchronized (engine) {
            fork = engine.copy();
        }
        return analysisTimer(incident.kind().name().toLowerCase(Locale.ROOT)).record(
                () -> analyzer.analyze(fork, incident, horizonSeconds, HeadwayStrategy.all()));
    }

    /**
     * Ranks every headway strategy over {@code scenarios} random incidents drawn from {@code seed}, starting from a
     * copy of the live state. Like the delay analysis it never touches the live engine.
     */
    public BenchmarkReport benchmarkStrategies(int scenarios, long seed, double horizonSeconds,
                                               Set<BenchmarkReport.IncidentKind> kinds,
                                               BenchmarkReport.RankingMetric metric) {
        SimulationEngine fork;
        synchronized (engine) {
            fork = engine.copy();
        }
        return analysisTimer("benchmark").record(
                () -> benchmark.run(fork, scenarios, seed, horizonSeconds, HeadwayStrategy.all(), kinds, metric));
    }

    /**
     * Queues the same benchmark as {@link #benchmarkStrategies} as a background job and returns it at once. The live
     * state is copied now, so the job ranks strategies from the moment it was submitted however long it queues.
     *
     * @throws BenchmarkJobs.QueueFullException if too many jobs are already queued or running
     */
    public BenchmarkJobs.Job submitBenchmark(int scenarios, long seed, double horizonSeconds,
                                             Set<BenchmarkReport.IncidentKind> kinds,
                                             BenchmarkReport.RankingMetric metric) {
        SimulationEngine fork;
        synchronized (engine) {
            fork = engine.copy();
        }
        List<HeadwayStrategy> strategies = HeadwayStrategy.all();
        return benchmarkJobs.submit(StrategyBenchmark.totalRuns(scenarios, strategies.size()),
                progress -> analysisTimer("benchmark").record(() -> benchmark.run(fork, scenarios, seed,
                        horizonSeconds, strategies, kinds, metric, progress)));
    }

    public Optional<BenchmarkJobs.Job> benchmarkJob(String id) {
        return benchmarkJobs.get(id);
    }

    /** Cancels a queued or running benchmark job; see {@link BenchmarkJobs#cancel}. */
    public Optional<BenchmarkJobs.Job> cancelBenchmarkJob(String id) {
        return benchmarkJobs.cancel(id);
    }

    /** Benchmark jobs still kept, most recently submitted first. */
    public List<BenchmarkJobs.Job> benchmarkJobs(int limit) {
        return benchmarkJobs.recent(limit);
    }

    @PreDestroy
    void stopBenchmarkJobs() {
        benchmarkExecutor.shutdownNow();
    }

    /**
     * Wall-clock duration of what-if work, tagged by what was analysed: {@code delay}, an incident kind such as
     * {@code station_closure}, or {@code benchmark}. Analyses run on forked engines, so this is the time a request
     * spends simulating, not time the live clock loses.
     */
    private Timer analysisTimer(String kind) {
        return Timer.builder("metromind.analysis.duration")
                .description("Wall-clock time of what-if analyses and strategy benchmarks")
                .tag("kind", kind)
                .register(meters);
    }

    /** What-if analyses run so far, most recent first. */
    public List<AnalysisRecord> analysisHistory(int limit) {
        return history.recentAnalyses(limit);
    }
}
