package com.metromind.network;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @param odWeights optional origin–destination weights: {@code odWeights[origin][destination]} is the relative
 *                  attractiveness of {@code destination} for passengers entering at {@code origin}. Origins that
 *                  are not listed use a gravity model (destinations weighted by their own ridership). Weights are
 *                  normalised per origin, so only their ratios matter.
 */
public record MetroNetwork(String city, List<Station> stations, List<Line> lines,
                           Map<String, Map<String, Double>> odWeights) {

    public MetroNetwork {
        stations = List.copyOf(stations);
        lines = List.copyOf(lines);
        Map<String, Station> byId = stations.stream().collect(Collectors.toMap(Station::id, Function.identity()));
        for (Line line : lines) {
            for (String sid : line.stationIds()) {
                if (!byId.containsKey(sid)) {
                    throw new IllegalArgumentException("Line " + line.id() + " references unknown station " + sid);
                }
            }
        }
        Map<String, Map<String, Double>> od = new LinkedHashMap<>();
        if (odWeights != null) {
            odWeights.forEach((origin, row) -> {
                if (!byId.containsKey(origin)) {
                    throw new IllegalArgumentException("OD weights reference unknown origin " + origin);
                }
                row.forEach((dest, w) -> {
                    if (!byId.containsKey(dest)) {
                        throw new IllegalArgumentException("OD weights reference unknown destination " + dest);
                    }
                    if (w == null || w < 0 || !Double.isFinite(w)) {
                        throw new IllegalArgumentException("OD weight " + origin + "->" + dest + " must be >= 0");
                    }
                });
                od.put(origin, Map.copyOf(row));
            });
        }
        odWeights = java.util.Collections.unmodifiableMap(od);
    }

    /** Network whose demand follows the default gravity model. */
    public MetroNetwork(String city, List<Station> stations, List<Line> lines) {
        this(city, stations, lines, Map.of());
    }

    public Station station(String id) {
        return stations.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown station " + id));
    }

    public Line line(String id) {
        return lines.stream().filter(l -> l.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown line " + id));
    }
}
