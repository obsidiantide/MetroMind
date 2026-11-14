package com.metromind.sim;

/** What trains do when the track ahead of them is closed. */
public enum ClosureResponse {
    /** Stay at the platform before the closed section until it reopens. */
    WAIT,
    /**
     * Reverse at the last open station before the section and run back the other way, so the rest of the line keeps
     * a service. Passengers still on board get off and wait on the platform for the line to reopen.
     */
    TURN_BACK
}
