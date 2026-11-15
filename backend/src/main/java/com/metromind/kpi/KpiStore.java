package com.metromind.kpi;

import java.util.List;

/** Where closed {@link MinuteKpi minutes} are kept. */
public interface KpiStore {

    /** Stores a minute, replacing any earlier value for the same minute (e.g. after a consumer restart). */
    void save(MinuteKpi kpi);

    /** The {@code limit} most recent minutes, oldest first so they can be charted directly. */
    List<MinuteKpi> recent(int limit);
}
