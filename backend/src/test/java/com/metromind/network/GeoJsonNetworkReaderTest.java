package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class GeoJsonNetworkReaderTest {

    private final GeoJsonNetworkReader reader = new GeoJsonNetworkReader(new ObjectMapper());

    /** Three stations on a track that bends north between B and C. */
    private static String network(String lineExtras, String cLon) {
        return """
                {"type": "FeatureCollection",
                 "properties": {"city": "Testgrad", "odWeights": {"A": {"C": 3, "B": 1}}},
                 "features": [
                  {"type": "Feature", "id": "A", "geometry": {"type": "Point", "coordinates": [49.80, 40.00]},
                   "properties": {"name": "Alpha", "baseArrivalsPerMinute": 4}},
                  {"type": "Feature", "geometry": {"type": "Point", "coordinates": [49.82, 40.00]},
                   "properties": {"id": "B", "name": "Bravo", "baseArrivalsPerMinute": 6, "platformCapacity": 300}},
                  {"type": "Feature", "geometry": {"type": "Point", "coordinates": [%s, 40.00]},
                   "properties": {"id": "C", "baseArrivalsPerMinute": 2}},
                  {"type": "Feature", "geometry": {"type": "LineString",
                     "coordinates": [[49.80, 40.00], [49.82, 40.00], [49.83, 40.01], [49.84, 40.00]]},
                   "properties": {"id": "L1", "name": "Line 1", "color": "#e53935", "stationIds": ["A", "B", "C"],
                                  "trainCount": 3, "trainCapacity": 900 %s}}
                 ]}
                """.formatted(cLon, lineExtras);
    }

    private MetroNetwork read(String json) throws Exception {
        return reader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void readsStationsLinesAndCollectionProperties() throws Exception {
        MetroNetwork n = read(network(", \"spareTrains\": 1", "49.84"));

        assertThat(n.city()).isEqualTo("Testgrad");
        assertThat(n.stations()).extracting(Station::id).containsExactly("A", "B", "C");
        Station b = n.station("B");
        assertThat(b.name()).isEqualTo("Bravo");
        assertThat(b.lat()).isEqualTo(40.00);
        assertThat(b.lon()).isEqualTo(49.82);
        assertThat(b.platformCapacity()).isEqualTo(300);
        assertThat(n.station("C").name()).as("name defaults to the id").isEqualTo("C");
        assertThat(n.station("C").hasPlatformLimit()).isFalse();

        Line line = n.line("L1");
        assertThat(line.stationIds()).containsExactly("A", "B", "C");
        assertThat(line.trainCount()).isEqualTo(3);
        assertThat(line.spareTrains()).isEqualTo(1);
        assertThat(line.path()).hasSize(4).element(2).isEqualTo(java.util.List.of(49.83, 40.01));
        assertThat(n.odWeights().get("A")).containsEntry("C", 3.0);
    }

    @Test
    void readsCrossPlatformInterchangesFromTheCollectionProperties() throws Exception {
        String json = network("", "49.84")
                .replace("\"odWeights\"", "\"crossPlatform\": [{\"lineIds\": [\"L1\", \"L2\"], \"stationIds\": [\"B\"]}], \"odWeights\"")
                .replaceFirst("\\s*]}\\s*$", """
                  ,{"type": "Feature", "geometry": {"type": "LineString",
                     "coordinates": [[49.84, 40.00], [49.83, 40.01], [49.82, 40.00]]},
                   "properties": {"id": "L2", "stationIds": ["C", "B"], "trainCount": 2, "trainCapacity": 600}}
                 ]}""");

        MetroNetwork n = read(json);

        assertThat(n.crossPlatform()).hasSize(1);
        assertThat(n.isCrossPlatform("L2", "L1", "B")).isTrue();
        assertThat(n.isCrossPlatform("L2", "L1", "C")).isFalse();
        assertThat(read(network("", "49.84")).crossPlatform()).isEmpty();
    }

    @Test
    void derivesRunTimesFromTrackLengthNotStraightLineDistance() throws Exception {
        Line line = read(network("", "49.84")).line("L1");

        double ab = InMemoryStationLocator.haversineMeters(40.00, 49.80, 40.00, 49.82);
        double bc = InMemoryStationLocator.haversineMeters(40.00, 49.82, 40.01, 49.83)
                + InMemoryStationLocator.haversineMeters(40.01, 49.83, 40.00, 49.84);
        double speed = GeoJsonNetworkReader.DEFAULT_AVERAGE_SPEED_KMH / 3.6;
        assertThat(line.runSeconds().get(0)).isCloseTo((int) Math.round(ab / speed), within(1));
        assertThat(line.runSeconds().get(1)).isCloseTo((int) Math.round(bc / speed), within(1));
        // B–C detours north, so it takes longer than A–B although the stations are equally far apart.
        assertThat(line.runSeconds().get(1)).isGreaterThan(line.runSeconds().get(0) + 40);
    }

    @Test
    void averageSpeedScalesRunTimesAndExplicitRunSecondsWin() throws Exception {
        Line slow = read(network(", \"averageSpeedKmh\": 16.2", "49.84")).line("L1");
        Line normal = read(network("", "49.84")).line("L1");
        assertThat(slow.runSeconds().get(0)).isCloseTo(2 * normal.runSeconds().get(0), within(1));

        Line given = read(network(", \"runSeconds\": [100, 150]", "49.84")).line("L1");
        assertThat(given.runSeconds()).containsExactly(100, 150);
    }

    @Test
    void rejectsStationsOffTheTrack() {
        // C placed ~1.7 km east of the track's end.
        assertThatThrownBy(() -> read(network("", "49.86")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("station C is")
                .hasMessageContaining("from the track");
    }

    @Test
    void rejectsStationsListedAgainstTheTrackDirection() {
        String reversed = network("", "49.84").replace("[\"A\", \"B\", \"C\"]", "[\"B\", \"A\", \"C\"]");
        assertThatThrownBy(() -> read(reversed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("station A is not further along the track than B");
    }

    @Test
    void rejectsMissingDataWithMessagesNamingTheFeature() {
        assertThatThrownBy(() -> read("{\"type\": \"Feature\"}"))
                .hasMessageContaining("FeatureCollection");
        assertThatThrownBy(() -> read(network("", "49.84").replace("\"baseArrivalsPerMinute\": 2", "\"x\": 2")))
                .hasMessageContaining("Station C: baseArrivalsPerMinute is required");
        assertThatThrownBy(() -> read(network("", "49.84").replace("\"trainCount\": 3,", "")))
                .hasMessageContaining("Line L1: trainCount is required");
        assertThatThrownBy(() -> read(network("", "49.84").replace("\"B\", \"C\"]", "\"B\", \"Z\"]")))
                .hasMessageContaining("unknown station Z");
        assertThatThrownBy(() -> read(network("", "49.84").replace("\"type\": \"LineString\"", "\"type\": \"Polygon\"")))
                .hasMessageContaining("Unsupported geometry 'Polygon' in feature L1");
        assertThatThrownBy(() -> read(network("", "49.84").replace("\"id\": \"B\"", "\"id\": \"A\"")))
                .hasMessageContaining("Duplicate station A");
    }

    /** Three stations on a closed triangular track A→B→C→A. */
    private static String ringNetwork(String closingCoordinate) {
        return """
                {"type": "FeatureCollection",
                 "features": [
                  {"type": "Feature", "id": "A", "geometry": {"type": "Point", "coordinates": [49.80, 40.00]},
                   "properties": {"baseArrivalsPerMinute": 4}},
                  {"type": "Feature", "id": "B", "geometry": {"type": "Point", "coordinates": [49.82, 40.00]},
                   "properties": {"baseArrivalsPerMinute": 4}},
                  {"type": "Feature", "id": "C", "geometry": {"type": "Point", "coordinates": [49.81, 40.02]},
                   "properties": {"baseArrivalsPerMinute": 4}},
                  {"type": "Feature", "geometry": {"type": "LineString",
                     "coordinates": [[49.80, 40.00], [49.82, 40.00], [49.81, 40.02], %s]},
                   "properties": {"id": "K", "stationIds": ["A", "B", "C"], "ring": true,
                                  "trainCount": 2, "trainCapacity": 900}}
                 ]}
                """.formatted(closingCoordinate);
    }

    @Test
    void readsRingLinesWithTheClosingRunDerivedFromTheTrack() throws Exception {
        Line ring = read(ringNetwork("[49.80, 40.00]")).line("K");

        assertThat(ring.ring()).isTrue();
        assertThat(ring.runSeconds()).hasSize(3);
        double ca = InMemoryStationLocator.haversineMeters(40.02, 49.81, 40.00, 49.80);
        double speed = GeoJsonNetworkReader.DEFAULT_AVERAGE_SPEED_KMH / 3.6;
        assertThat(ring.runSeconds().get(2)).isCloseTo((int) Math.round(ca / speed), within(1));
        assertThat(read(network("", "49.84")).line("L1").ring()).as("ring defaults to false").isFalse();
    }

    @Test
    void rejectsRingsWhoseTrackIsNotClosed() {
        assertThatThrownBy(() -> read(ringNetwork("[49.805, 40.01]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ring line K")
                .hasMessageContaining("closed");
    }
}
