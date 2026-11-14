package com.metromind.incident;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Incident history for runs without a database: keeps the last {@value #CAPACITY} incidents in memory. */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "false")
public class InMemoryIncidentHistory implements IncidentHistory {

    static final int CAPACITY = 500;

    private final Deque<IncidentRecord> records = new ArrayDeque<>();
    private long nextId = 1;

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
        List<IncidentRecord> out = new ArrayList<>(Math.min(limit, records.size()));
        for (Iterator<IncidentRecord> it = records.descendingIterator(); it.hasNext() && out.size() < limit; ) {
            out.add(it.next());
        }
        return out;
    }
}
