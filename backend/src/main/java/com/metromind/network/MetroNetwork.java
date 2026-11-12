package com.metromind.network;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public record MetroNetwork(String city, List<Station> stations, List<Line> lines) {

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
