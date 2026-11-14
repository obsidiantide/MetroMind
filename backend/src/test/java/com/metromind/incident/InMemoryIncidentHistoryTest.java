package com.metromind.incident;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryIncidentHistoryTest {

    @Test
    void returnsMostRecentIncidentsFirst() {
        InMemoryIncidentHistory history = new InMemoryIncidentHistory();
        history.record(100, IncidentRecord.Kind.DELAY, "RED-1", 240, "");
        history.record(200, IncidentRecord.Kind.STATION_CLOSURE, "R3", 600, "");
        IncidentRecord last = history.record(300, IncidentRecord.Kind.SECTION_CLOSURE, "RED", 900, "R4-R5, WAIT");

        assertThat(last.id()).isEqualTo(3);
        assertThat(history.recent(10)).extracting(IncidentRecord::subjectId).containsExactly("RED", "R3", "RED-1");
        assertThat(history.recent(2)).extracting(IncidentRecord::id).containsExactly(3L, 2L);
    }

    @Test
    void forgetsTheOldestIncidentsBeyondCapacity() {
        InMemoryIncidentHistory history = new InMemoryIncidentHistory();
        for (int i = 0; i < InMemoryIncidentHistory.CAPACITY + 5; i++) {
            history.record(i, IncidentRecord.Kind.DELAY, "T", 60, "");
        }
        var all = history.recent(Integer.MAX_VALUE);
        assertThat(all).hasSize(InMemoryIncidentHistory.CAPACITY);
        assertThat(all.get(all.size() - 1).id()).isEqualTo(6);
    }

    @Test
    void keepsAnalysesSeparatelyFromIncidents() {
        InMemoryIncidentHistory history = new InMemoryIncidentHistory();
        for (int i = 0; i < InMemoryIncidentHistory.ANALYSIS_CAPACITY + 1; i++) {
            history.recordAnalysis(1000 + i, new DelayImpactReport("RED-" + i, 60, 1800, List.of(), "BALANCED"));
        }
        assertThat(history.recent(10)).isEmpty();
        List<AnalysisRecord> analyses = history.recentAnalyses(Integer.MAX_VALUE);
        assertThat(analyses).hasSize(InMemoryIncidentHistory.ANALYSIS_CAPACITY);
        assertThat(analyses.get(0).report().trainId()).isEqualTo("RED-" + InMemoryIncidentHistory.ANALYSIS_CAPACITY);
        assertThat(analyses.get(0).simTime()).isEqualTo(1000 + InMemoryIncidentHistory.ANALYSIS_CAPACITY);
    }
}
