package com.metromind.sim;

import com.metromind.incident.DelayImpactReport;
import com.metromind.incident.DelayPropagationAnalyzer;
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
    private final DelayPropagationAnalyzer analyzer = new DelayPropagationAnalyzer();
    private volatile boolean paused;
    private volatile SimulationSnapshot last;

    public SimulationService(MetroNetwork network, SimulationProperties props, List<SnapshotListener> listeners) {
        this.props = props;
        this.listeners = listeners;
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
        }
    }

    public void injectBreakdown(String trainId, double repairSeconds) {
        synchronized (engine) {
            engine.injectBreakdown(trainId, repairSeconds);
        }
    }

    public void setStrategy(String name) {
        synchronized (engine) {
            engine.setStrategy(HeadwayStrategy.byName(name));
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
        return analyzer.analyze(fork, trainId, seconds, horizonSeconds, HeadwayStrategy.all());
    }
}
