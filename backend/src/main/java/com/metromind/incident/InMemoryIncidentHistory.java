package com.metromind.incident;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Incident history for runs without a database: keeps the last {@value #CAPACITY} incidents and
 * {@value #ANALYSIS_CAPACITY} analyses in memory.
 */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "false")
public class InMemoryIncidentHistory implements IncidentHistory {

    static final int CAPACITY = 500;
    /** Reports hold per-train and per-station detail, so fewer of them are kept. */
    static final int ANALYSIS_CAPACITY = 50;

    private final Deque<IncidentRecord> records = new ArrayDeque<>();
    private final Deque<AnalysisRecord> analyses = new ArrayDeque<>();
    private long nextId = 1;
    private long nextAnalysisId = 1;

    @Override
    public synchronized IncidentRecord record(double simTime, IncidentRecord.Kind kind, String subjectId,
                                              double seconds, String detail) {
        IncidentRecord r = new IncidentRecord(nextId++, simTime, kind, subjectId, seconds, detail);
        records.addLast(r);
        while (records.size() > CAPACITY) {
            records.removeFirst();
        }
        return r;
    }

    @Override
    public synchronized List<IncidentRecord> recent(int limit) {
        return newestFirst(records, limit);
    }

    @Override
    public synchronized AnalysisRecord recordAnalysis(double simTime, DelayImpactReport report) {
        AnalysisRecord r = new AnalysisRecord(nextAnalysisId++, simTime, report);
        analyses.addLast(r);
        while (analyses.size() > ANALYSIS_CAPACITY) {
            analyses.removeFirst();
        }
        return r;
    }

    @Override
    public synchronized List<AnalysisRecord> recentAnalyses(int limit) {
        return newestFirst(analyses, limit);
    }

    private static <T> List<T> newestFirst(Deque<T> deque, int limit) {
        List<T> out = new ArrayList<>(Math.min(limit, deque.size()));
        for (Iterator<T> it = deque.descendingIterator(); it.hasNext() && out.size() < limit; ) {
            out.add(it.next());
        }
        return out;
    }
}
