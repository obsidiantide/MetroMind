package com.metromind.sim;

/** Something noteworthy that happened in the simulation (delay detected, overload predicted, incident...). */
public record SimEvent(double simTime, Type type, String subjectId, String message) {
    public enum Type { DELAY_DETECTED, DELAY_RECOVERED, OVERLOAD_PREDICTED, INCIDENT, INCIDENT_CLEARED }
}
