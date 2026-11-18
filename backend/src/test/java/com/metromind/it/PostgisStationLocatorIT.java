package com.metromind.it;

import com.metromind.infra.PostgisStationLocator;
import com.metromind.network.InMemoryStationLocator;
import com.metromind.network.Line;
import com.metromind.network.MetroNetwork;
import com.metromind.network.Station;
import com.metromind.network.StationLocator;
import com.metromind.network.StationLocator.StationDistance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PostgisStationLocatorIT extends InfrastructureIT {

    @Autowired
    StationLocator locator;

    @Autowired
    MetroNetwork network;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void networkIsPersistedAsPointsAndLineStrings() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM station", Integer.class))
                .isEqualTo(network.stations().size());
        for (Line line : network.lines()) {
            assertThat(jdbc.queryForObject("SELECT ST_NPoints(path) FROM line WHERE id = ?", Integer.class,
                    line.id())).isEqualTo(line.stationIds().size());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM line_branch", Integer.class))
                .isEqualTo(network.lines().stream().mapToInt(l -> l.branches().size()).sum());
        Station first = network.stations().getFirst();
        assertThat(jdbc.queryForObject("SELECT ST_Y(location::geometry) FROM station WHERE id = ?", Double.class,
                first.id())).isCloseTo(first.lat(), within(1e-9));
    }

    @Test
    void postgisAgreesWithTheInMemoryHaversineLocator() {
        assertThat(locator).isInstanceOf(PostgisStationLocator.class);
        var reference = new InMemoryStationLocator(network);
        Station centre = network.stations().get(network.stations().size() / 2);
        double lat = centre.lat() + 0.004;
        double lon = centre.lon() - 0.003;

        assertSameStations(locator.nearest(lat, lon, 5), reference.nearest(lat, lon, 5));
        var nearby = reference.within(lat, lon, 2_500);
        assertThat(nearby).hasSizeGreaterThan(1);
        assertSameStations(locator.within(lat, lon, 2_500), nearby);
    }

    @Test
    void radiusZeroAtAStationFindsOnlyThatStation() {
        Station s = network.stations().getFirst();
        assertThat(locator.within(s.lat(), s.lon(), 0)).extracting(d -> d.station().id()).containsExactly(s.id());
    }

    /** Same stations in the same order; distances on the spheroid stay within 0.5 % of the spherical ones. */
    private static void assertSameStations(List<StationDistance> actual, List<StationDistance> expected) {
        assertThat(actual).extracting(d -> d.station().id())
                .containsExactlyElementsOf(expected.stream().map(d -> d.station().id()).toList());
        for (int i = 0; i < actual.size(); i++) {
            double reference = expected.get(i).distanceMeters();
            assertThat(actual.get(i).distanceMeters()).isCloseTo(reference, within(reference * 0.005 + 0.01));
        }
    }
}
