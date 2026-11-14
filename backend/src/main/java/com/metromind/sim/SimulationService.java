package com.metromind.sim;

import com.metromind.incident.AnalysisRecord;
import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.DelayPropagationAnalyzer;
import com.metromind.incident.IncidentHistory;
import com.metromind.incident.IncidentRecord;
import com.metromind.network.MetroNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/** Owns the live engine, advances it on a timer and fans snapshots out to listeners. */
@Service
public class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    private final SimulationEngine engine;
    private final SimulationProperties props;
    private final List<SnapshotListener> listeners;
    private final IncidentHistory history;
    private final DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
    private volatile boolean paused;
    private volatile SimulationSnapshot last;

    public SimulationService(MetroNetwork network, SimulationProperties props, List<SnapshotListener> listeners,
                             IncidentHistory history) {
        this.props = props;
        this.listeners = listeners;
        this.history = history;
        this.engine = new SimulationEngine(network, props.startSeconds(), HeadwayStrategy.byName(props.strategy()));
        this.last = engine.snapshot();
    }

    @Scheduled(fixedRateString = "${metromind.sim.tick-millis:500}")
    public void tick() {
        SimulationSnapshot snap;
        synchronized (engine) {
            if (!paused) {
                engine.run(props.speed() * props.tickMillis() / 1000.0, 1.0);
            }
            snap = engine.snapshot();
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
        }
    }

    public void injectBreakdown(String trainId, double repairSeconds) {
        synchronized (engine) {
            engine.injectBreakdown(trainId, repairSeconds);
            history.record(engine.simTime(), IncidentRecord.Kind.BREAKDOWN, trainId, repairSeconds, "");
        }
    }

    public void closeSection(String lineId, String fromStationId, String toStationId, double seconds,
                             ClosureResponse response) {
        synchronized (engine) {
            engine.closeSection(lineId, fromStationId, toStationId, seconds, response);
            history.record(engine.simTime(), IncidentRecord.Kind.SECTION_CLOSURE, lineId, seconds,
                    "%s-%s, %s".formatted(fromStationId, toStationId, response));
        }
    }

    public void closeStation(String stationId, double seconds) {
        synchronized (engine) {
            engine.closeStation(stationId, seconds);
            history.record(engine.simTime(), IncidentRecord.Kind.STATION_CLOSURE, stationId, seconds, "");
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
        DelayImpactReport report = analyzer.analyze(fork, trainId, seconds, horizonSeconds, HeadwayStrategy.all());
        history.recordAnalysis(fork.simTime(), report);
        return report;
    }

    /** What-if analyses run so far, most recent first. */
    public List<AnalysisRecord> analysisHistory(int limit) {
        return history.recentAnalyses(limit);
    }
}
