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
 * @param crossPlatform interchanges where changing lines costs no walk (empty if the file lists none)
 */
public record MetroNetwork(String city, List<Station> stations, List<Line> lines,
                           Map<String, Map<String, Double>> odWeights, List<CrossPlatformInterchange> crossPlatform) {

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
        crossPlatform = crossPlatform == null ? List.of() : List.copyOf(crossPlatform);
        for (CrossPlatformInterchange x : crossPlatform) {
            List<Line> served = new java.util.ArrayList<>();
            for (String lid : x.lineIds()) {
                served.add(lines.stream().filter(l -> l.id().equals(lid)).findFirst().orElseThrow(
                        () -> new IllegalArgumentException("Cross-platform interchange references unknown line " + lid)));
            }
            for (String sid : x.stationIds()) {
                if (!served.stream().allMatch(l -> l.stationIds().contains(sid))) {
                    throw new IllegalArgumentException("Cross-platform interchange at " + sid
                            + ": not every line " + x.lineIds() + " calls there");
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

    /** Network with no cross-platform interchanges. */
    public MetroNetwork(String city, List<Station> stations, List<Line> lines,
                        Map<String, Map<String, Double>> odWeights) {
        this(city, stations, lines, odWeights, List.of());
    }

    /** Network whose demand follows the default gravity model. */
    public MetroNetwork(String city, List<Station> stations, List<Line> lines) {
        this(city, stations, lines, Map.of());
    }

    /** Whether a passenger changing from {@code fromLine} to {@code toLine} at {@code stationId} just steps across. */
    public boolean isCrossPlatform(String fromLine, String toLine, String stationId) {
        return crossPlatform.stream().anyMatch(x -> x.covers(fromLine, toLine, stationId));
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
