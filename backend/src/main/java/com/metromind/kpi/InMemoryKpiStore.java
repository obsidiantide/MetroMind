package com.metromind.kpi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** KPI store for runs without a database: keeps the last {@value #CAPACITY} minutes (one simulated day). */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "false")
public class InMemoryKpiStore implements KpiStore {

    static final int CAPACITY = 24 * 60;

    private final TreeMap<Long, MinuteKpi> minutes = new TreeMap<>();

    @Override
    public synchronized void save(MinuteKpi kpi) {
        minutes.put(kpi.minute(), kpi);
        while (minutes.size() > CAPACITY) {
            minutes.pollFirstEntry();
        }
    }

    @Override
    public synchronized List<MinuteKpi> recent(int limit) {
        List<MinuteKpi> out = new ArrayList<>(Math.min(limit, minutes.size()));
        for (MinuteKpi k : minutes.descendingMap().values()) {
            if (out.size() == limit) {
                break;
            }
            out.add(k);
        }
        return out.reversed();
    }
}
