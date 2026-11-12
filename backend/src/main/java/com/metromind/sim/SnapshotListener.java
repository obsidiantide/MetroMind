package com.metromind.sim;

/** Receives every published snapshot (WebSocket, Kafka, Redis, ...). */
public interface SnapshotListener {
    void onSnapshot(SimulationSnapshot snapshot);
}
