package com.metromind.incident;

import org.junit.jupiter.api.Test;

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
}
