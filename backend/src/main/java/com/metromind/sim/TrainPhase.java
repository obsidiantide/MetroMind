package com.metromind.sim;

public enum TrainPhase {
    /** Doors open at a platform, passengers boarding/alighting. */
    DWELLING,
    /** Ready to depart but held by the headway strategy. */
    HELD,
    /** Ready to depart but the next block (segment + platform) is occupied. */
    WAITING_SIGNAL,
    /** Moving between two stations. */
    RUNNING
}
