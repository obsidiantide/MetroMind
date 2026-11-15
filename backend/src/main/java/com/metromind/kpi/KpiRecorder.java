package com.metromind.kpi;

import com.metromind.sim.SimEvent;
import com.metromind.sim.SimulationSnapshot;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Feeds telemetry into a {@link KpiAggregator} and saves every minute it closes. Fed from Kafka when it is
 * enabled ({@code TelemetryKpiConsumer}), otherwise straight from the simulator ({@code LocalKpiFeed}).
 */
@Component
public class KpiRecorder {

    private final KpiAggregator aggregator;
    private final KpiStore store;

    @Autowired
    public KpiRecorder(KpiStore store) {
        this(new KpiAggregator(), store);
    }

    KpiRecorder(KpiAggregator aggregator, KpiStore store) {
        this.aggregator = aggregator;
        this.store = store;
    }

    public void position(double simTime, SimulationSnapshot.TrainView train) {
        save(aggregator.onPosition(simTime, train));
    }

    public void metrics(double simTime, SimulationSnapshot.Metrics metrics) {
        save(aggregator.onMetrics(simTime, metrics));
    }

    public void event(SimEvent event) {
        save(aggregator.onEvent(event));
    }

    /** The {@code limit} most recent closed minutes, oldest first. */
    public List<MinuteKpi> recent(int limit) {
        return store.recent(limit);
    }

    /** Samples dropped because they arrived after their minute was closed. */
    public long lateSamples() {
        return aggregator.lateSamples();
    }

    /** Saves the minutes still open so a clean shutdown loses nothing. */
    @PreDestroy
    public void flush() {
        save(aggregator.flush());
    }

    private void save(List<MinuteKpi> closed) {
        closed.forEach(store::save);
    }
}
