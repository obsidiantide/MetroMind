package com.metromind.incident;

import java.util.List;

/** Where injected incidents are kept so operators can review what happened. Implementations are thread-safe. */
public interface IncidentHistory {

    /** Stores an incident and returns it with its assigned id. */
    IncidentRecord record(double simTime, IncidentRecord.Kind kind, String subjectId, double seconds, String detail);

    /** Most recent incidents first, at most {@code limit}. */
    List<IncidentRecord> recent(int limit);
}
