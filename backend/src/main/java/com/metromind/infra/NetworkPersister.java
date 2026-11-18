package com.metromind.infra;

import com.metromind.network.Branch;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Upserts the network topology into PostGIS so it can be queried spatially (nearest station, buffers...). */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class NetworkPersister {

    private final JdbcTemplate jdbc;
    private final MetroNetwork network;

    public NetworkPersister(JdbcTemplate jdbc, MetroNetwork network) {
        this.jdbc = jdbc;
        this.network = network;
    }

    /**
     * The line's real alignment when the network file gives one, otherwise straight segments between stations; a
     * ring's segments come back to its first station, so either way a ring is stored as a closed linestring.
     */
    String trackWkt(Line line) {
        List<String> stops = new ArrayList<>(line.stationIds());
        if (line.ring()) {
            stops.add(stops.get(0));
        }
        var points = line.hasPath()
                ? line.path().stream().map(p -> p.get(0) + " " + p.get(1))
                : stops.stream().map(network::station).map(s -> s.lon() + " " + s.lat());
        return points.collect(Collectors.joining(", ", "LINESTRING(", ")"));
    }

    /** A branch's real alignment from its junction when the file gives one, otherwise straight segments. */
    String trackWkt(Branch branch) {
        var points = branch.hasPath()
                ? branch.path().stream().map(p -> p.get(0) + " " + p.get(1))
                : branch.trackStationIds().stream().map(network::station).map(s -> s.lon() + " " + s.lat());
        return points.collect(Collectors.joining(", ", "LINESTRING(", ")"));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void persist() {
        for (Station s : network.stations()) {
            jdbc.update("""
                    INSERT INTO station (id, name, location, base_arrivals_per_minute, platform_capacity)
                    VALUES (?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, location = EXCLUDED.location,
                        base_arrivals_per_minute = EXCLUDED.base_arrivals_per_minute,
                        platform_capacity = EXCLUDED.platform_capacity
                    """, s.id(), s.name(), s.lon(), s.lat(), s.baseArrivalsPerMinute(), s.platformCapacity());
        }
        for (Line l : network.lines()) {
            String wkt = trackWkt(l);
            jdbc.update("""
                    INSERT INTO line (id, name, color, path, train_count)
                    VALUES (?, ?, ?, ST_GeomFromText(?, 4326), ?)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, color = EXCLUDED.color,
                        path = EXCLUDED.path, train_count = EXCLUDED.train_count
                    """, l.id(), l.name(), l.color(), wkt, l.trainCount());
            // A line's branches are replaced as a whole, so a branch dropped from the network file disappears too.
            jdbc.update("DELETE FROM line_branch WHERE line_id = ?", l.id());
            for (Branch b : l.branches()) {
                jdbc.update("""
                        INSERT INTO line_branch (line_id, terminal_station_id, junction_station_id, share, path)
                        VALUES (?, ?, ?, ?, ST_GeomFromText(?, 4326))
                        """, l.id(), b.terminalStationId(), b.junctionStationId(), b.share(), trackWkt(b));
            }
        }
    }
}
