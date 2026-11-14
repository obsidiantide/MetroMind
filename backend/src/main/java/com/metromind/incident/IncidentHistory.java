package com.metromind.incident;

import java.util.List;

/** Where injected incidents and what-if analyses are kept so operators can review what happened. Implementations are thread-safe. */
public interface IncidentHistory {

    /** Stores an incident and returns it with its assigned id. */
    IncidentRecord record(double simTime, IncidentRecord.Kind kind, String subjectId, double seconds, String detail);

    /** Most recent incidents first, at most {@code limit}. */
    List<IncidentRecord> recent(int limit);

    /** Stores the result of a what-if analysis started from the live state at {@code simTime}. */
    AnalysisRecord recordAnalysis(double simTime, DelayImpactReport report);

    /** Most recent analyses first, at most {@code limit}. */
    List<AnalysisRecord> recentAnalyses(int limit);
}
