package com.metromind.infra;

import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import com.metromind.network.StationLocator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Station lookups in PostGIS over the {@code station} table that {@link NetworkPersister} fills. Distances use the
 * {@code geography} type (on the WGS84 spheroid); the nearest-station query is a KNN search ({@code <->}) and the
 * radius query an {@code ST_DWithin}, both able to use the GiST index on {@code station.location}.
 */
@Component
@ConditionalOnProperty(name = "metromind.persistence.enabled", havingValue = "true")
public class PostgisStationLocator implements StationLocator {

    private static final String POINT = "ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography";

    private final JdbcTemplate jdbc;
    private final MetroNetwork network;

    public PostgisStationLocator(JdbcTemplate jdbc, MetroNetwork network) {
        this.jdbc = jdbc;
        this.network = network;
    }

    @Override
    public List<StationDistance> nearest(double lat, double lon, int limit) {
        StationLocator.checkPoint(lat, lon);
        return jdbc.query("""
                SELECT id, ST_Distance(location, %1$s) AS meters FROM station
                WHERE id = ANY(?)
                ORDER BY location <-> %1$s, id LIMIT ?
                """.formatted(POINT), this::row, lon, lat, stationIds(), lon, lat, limit);
    }

    @Override
    public List<StationDistance> within(double lat, double lon, double radiusMeters) {
        StationLocator.checkPoint(lat, lon);
        return jdbc.query("""
                SELECT id, ST_Distance(location, %1$s) AS meters FROM station
                WHERE id = ANY(?) AND ST_DWithin(location, %1$s, ?)
                ORDER BY meters, id
                """.formatted(POINT), this::row, lon, lat, stationIds(), lon, lat, radiusMeters);
    }

    /** Stations of an earlier network may still be in the table: only the loaded network's are considered. */
    private String[] stationIds() {
        return network.stations().stream().map(Station::id).toArray(String[]::new);
    }

    private StationDistance row(ResultSet rs, int i) throws SQLException {
        return new StationDistance(network.station(rs.getString("id")), rs.getDouble("meters"));
    }
}
