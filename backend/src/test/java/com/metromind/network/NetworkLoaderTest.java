package com.metromind.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NetworkLoaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TWO_STATIONS = """
            {"type": "FeatureCollection", "properties": {"city": "Twotown"}, "features": [
              {"type": "Feature", "id": "A", "geometry": {"type": "Point", "coordinates": [49.80, 40.0]},
               "properties": {"baseArrivalsPerMinute": 3}},
              {"type": "Feature", "id": "B", "geometry": {"type": "Point", "coordinates": [49.82, 40.0]},
               "properties": {"baseArrivalsPerMinute": 3}},
              {"type": "Feature", "id": "L", "geometry": {"type": "LineString",
                 "coordinates": [[49.80, 40.0], [49.82, 40.0]]},
               "properties": {"stationIds": ["A", "B"], "trainCount": 2, "trainCapacity": 800}}]}
            """;

    @Test
    void readsTheNativeFormatByDefault() throws Exception {
        MetroNetwork n = NetworkLoader.load(mapper, new ClassPathResource("network/metropolis.json"));
        assertThat(n.city()).isEqualTo("Metropolis");
        assertThat(n.lines()).noneMatch(Line::hasPath);
    }

    @Test
    void readsGeoJsonByFileExtension(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("twotown.GeoJSON");
        Files.writeString(file, TWO_STATIONS);

        MetroNetwork n = NetworkLoader.load(mapper, new FileSystemResource(file));
        assertThat(n.city()).isEqualTo("Twotown");
        assertThat(n.line("L").hasPath()).isTrue();
        assertThat(n.line("L").runSeconds()).hasSize(1);
    }

    @Test
    void namesTheFileWhenTheNetworkIsInvalid(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("broken.geojson");
        Files.writeString(file, TWO_STATIONS.replace("\"trainCount\": 2, ", ""));

        assertThatThrownBy(() -> NetworkLoader.load(mapper, new FileSystemResource(file)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("broken.geojson")
                .hasMessageContaining("trainCount is required");
    }
}
