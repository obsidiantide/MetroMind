package com.metromind.incident;

/**
 * One incident injected into the live simulation.
 *
 * @param id        increasing identifier, in the order incidents were recorded
 * @param simTime   simulation time at which it was injected (seconds since midnight)
 * @param subjectId train, line or station the incident applies to, depending on {@code kind}
 * @param seconds   injected delay, repair time or closure duration
 * @param detail    extra description, e.g. the closed section and how trains react; empty if none
 */
public record IncidentRecord(long id, double simTime, Kind kind, String subjectId, double seconds, String detail) {

    public enum Kind { DELAY, BREAKDOWN, SECTION_CLOSURE, STATION_CLOSURE }
}
