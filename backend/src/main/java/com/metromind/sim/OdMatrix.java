package com.metromind.sim;

import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;

import java.util.List;
import java.util.Map;

/**
 * Origin–destination split of passenger demand: {@code share(o, d)} is the fraction of passengers entering at
 * station {@code o} who travel to station {@code d}. Each row sums to 1 over the destinations reachable from
 * {@code o} (or is all zeros when nothing is reachable or every weight is zero).
 *
 * <p>By default a gravity model is used: a destination attracts trips in proportion to its own ridership
 * ({@link Station#baseArrivalsPerMinute()}). Rows given in {@link MetroNetwork#odWeights()} replace the gravity
 * row for that origin; unlisted destinations then get weight 0.
 */
final class OdMatrix {

    private final double[][] share;

    OdMatrix(MetroNetwork network, RoutePlanner routes) {
        List<Station> stations = network.stations();
        int n = stations.size();
        share = new double[n][n];
        for (int o = 0; o < n; o++) {
            Map<String, Double> override = network.odWeights().get(stations.get(o).id());
            double total = 0;
            for (int d = 0; d < n; d++) {
                if (d == o || !routes.reachable(o, d)) {
                    continue;
                }
                double w = override != null
                        ? override.getOrDefault(stations.get(d).id(), 0.0)
                        : stations.get(d).baseArrivalsPerMinute();
                share[o][d] = w;
                total += w;
            }
            for (int d = 0; d < n; d++) {
                share[o][d] = total > 0 ? share[o][d] / total : 0;
            }
        }
    }

    double share(int origin, int destination) {
        return share[origin][destination];
    }
}
