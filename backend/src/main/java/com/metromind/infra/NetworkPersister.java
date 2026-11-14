package com.metromind.infra;

import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

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
            String wkt = l.stationIds().stream().map(network::station)
                    .map(s -> s.lon() + " " + s.lat()).collect(Collectors.joining(", ", "LINESTRING(", ")"));
            jdbc.update("""
                    INSERT INTO line (id, name, color, path, train_count)
                    VALUES (?, ?, ?, ST_GeomFromText(?, 4326), ?)
                    ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, color = EXCLUDED.color,
                        path = EXCLUDED.path, train_count = EXCLUDED.train_count
                    """, l.id(), l.name(), l.color(), wkt, l.trainCount());
        }
    }
}
