package com.metromind.network;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a network from a GeoJSON {@code FeatureCollection}, the format real station and track data usually comes
 * in (OpenStreetMap exports, city open-data portals, QGIS).
 *
 * <ul>
 *   <li>Each {@code Point} feature is a station. Properties: {@code id}, {@code name},
 *       {@code baseArrivalsPerMinute}, optional {@code platformCapacity}.</li>
 *   <li>Each {@code LineString} feature is a line whose geometry is its track, drawn from the first station to the
 *       last. Properties: {@code id}, {@code name}, {@code color}, {@code stationIds} (in track order),
 *       {@code trainCount}, {@code trainCapacity}, optional {@code spareTrains}, and optionally either
 *       {@code runSeconds} or {@code averageSpeedKmh}. A ring line sets {@code ring: true}; its track is a closed
 *       LineString that starts and ends at the first station, and its trains run the way it is drawn.</li>
 *   <li>The collection's own {@code properties} may hold {@code city}, {@code odWeights} and
 *       {@code crossPlatform} (as in the native format).</li>
 * </ul>
 *
 * <p>When a line has no {@code runSeconds}, each one is the track length between the two stations divided by the
 * line's average speed (start-to-stop, including acceleration and braking; {@value #DEFAULT_AVERAGE_SPEED_KMH}
 * km/h unless given). Stations must lie on the track, within {@value #MAX_STATION_OFFSET_METERS} m of it, and in
 * the order the track runs.
 */
public class GeoJsonNetworkReader {

    /** Farthest apart the two ends of a ring's track may be (it must come back to where it started). */
    public static final double MAX_RING_GAP_METERS = 50;
    /** Average start-to-stop speed of a typical metro, matching the seed network's run times. */
    public static final double DEFAULT_AVERAGE_SPEED_KMH = 32.4;
    /** Farthest a station may be from its line's track (platforms are offset from the track centreline). */
    public static final double MAX_STATION_OFFSET_METERS = 250;

    private final ObjectMapper mapper;

    public GeoJsonNetworkReader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public MetroNetwork read(InputStream in) throws IOException {
        return read(mapper.readTree(in));
    }

    public MetroNetwork read(JsonNode root) {
        if (!"FeatureCollection".equals(root.path("type").asText())) {
            throw new IllegalArgumentException("GeoJSON network must be a FeatureCollection");
        }
        JsonNode meta = root.path("properties");
        String city = meta.path("city").asText(root.path("name").asText("Unnamed network"));

        List<Station> stations = new ArrayList<>();
        List<JsonNode> lineFeatures = new ArrayList<>();
        for (JsonNode feature : root.path("features")) {
            String geometry = feature.path("geometry").path("type").asText();
            switch (geometry) {
                case "Point" -> stations.add(station(feature));
                case "LineString" -> lineFeatures.add(feature);
                default -> throw new IllegalArgumentException(
                        "Unsupported geometry '" + geometry + "' in feature " + featureId(feature)
                                + ": use Point for stations and LineString for lines");
            }
        }
        Map<String, Station> byId = new LinkedHashMap<>();
        for (Station s : stations) {
            if (byId.put(s.id(), s) != null) {
                throw new IllegalArgumentException("Duplicate station " + s.id());
            }
        }
        List<Line> lines = lineFeatures.stream().map(f -> line(f, byId)).toList();

        Map<String, Map<String, Double>> od = meta.has("odWeights")
                ? mapper.convertValue(meta.get("odWeights"), new TypeReference<>() { })
                : Map.of();
        List<CrossPlatformInterchange> crossPlatform = meta.has("crossPlatform")
                ? mapper.convertValue(meta.get("crossPlatform"), new TypeReference<>() { })
                : List.of();
        return new MetroNetwork(city, stations, lines, od, crossPlatform);
    }

    private Station station(JsonNode feature) {
        JsonNode p = feature.path("properties");
        String id = featureId(feature);
        JsonNode coords = feature.path("geometry").path("coordinates");
        double[] lonLat = point(coords, id);
        if (!p.path("baseArrivalsPerMinute").isNumber()) {
            throw new IllegalArgumentException("Station " + id + ": baseArrivalsPerMinute is required");
        }
        Integer capacity = p.path("platformCapacity").isNumber() ? p.get("platformCapacity").asInt() : null;
        return new Station(id, p.path("name").asText(id), lonLat[1], lonLat[0],
                p.get("baseArrivalsPerMinute").asDouble(), capacity);
    }

    private Line line(JsonNode feature, Map<String, Station> stations) {
        JsonNode p = feature.path("properties");
        String id = featureId(feature);
        List<double[]> track = new ArrayList<>();
        for (JsonNode c : feature.path("geometry").path("coordinates")) {
            track.add(point(c, id));
        }
        List<String> stationIds = new ArrayList<>();
        p.path("stationIds").forEach(n -> stationIds.add(n.asText()));
        for (String sid : stationIds) {
            if (!stations.containsKey(sid)) {
                throw new IllegalArgumentException("Line " + id + " references unknown station " + sid);
            }
        }
        for (String required : List.of("trainCount", "trainCapacity")) {
            if (!p.path(required).isInt()) {
                throw new IllegalArgumentException("Line " + id + ": " + required + " is required");
            }
        }

        boolean ring = p.path("ring").asBoolean(false);
        if (ring && track.size() >= 2) {
            double[] first = track.get(0);
            double[] last = track.get(track.size() - 1);
            if (InMemoryStationLocator.haversineMeters(first[1], first[0], last[1], last[0]) > MAX_RING_GAP_METERS) {
                throw new IllegalArgumentException("Ring line " + id
                        + ": the track must be closed (end where it starts, at the first station)");
            }
        }
        List<Integer> runSeconds;
        if (p.has("runSeconds")) {
            runSeconds = new ArrayList<>();
            p.get("runSeconds").forEach(n -> runSeconds.add(n.asInt()));
        } else {
            double speedKmh = p.path("averageSpeedKmh").asDouble(DEFAULT_AVERAGE_SPEED_KMH);
            if (!(speedKmh > 0)) {
                throw new IllegalArgumentException("Line " + id + ": averageSpeedKmh must be positive");
            }
            runSeconds = deriveRunSeconds(id, new TrackGeometry(track), stationIds, stations, speedKmh / 3.6, ring);
        }

        List<List<Double>> path = track.stream().map(c -> List.of(c[0], c[1])).toList();
        return new Line(id, p.path("name").asText(id), p.path("color").asText("#888888"), stationIds, runSeconds,
                p.get("trainCount").asInt(), p.get("trainCapacity").asInt(), p.path("spareTrains").asInt(0), path, ring);
    }

    /**
     * Run time between consecutive stations: their distance along the track over the average speed. A ring also
     * gets the run from its last station over the end of the track and round to the first.
     */
    static List<Integer> deriveRunSeconds(String lineId, TrackGeometry track, List<String> stationIds,
                                          Map<String, Station> stations, double metersPerSecond, boolean ring) {
        List<Integer> runSeconds = new ArrayList<>();
        double previous = Double.NaN;
        double first = Double.NaN;
        for (int i = 0; i < stationIds.size(); i++) {
            Station s = stations.get(stationIds.get(i));
            TrackGeometry.Projection at = track.project(s.lat(), s.lon());
            if (at.offsetMeters() > MAX_STATION_OFFSET_METERS) {
                throw new IllegalArgumentException(String.format(java.util.Locale.ROOT,
                        "Line %s: station %s is %.0f m from the track (at most %.0f m allowed)",
                        lineId, s.id(), at.offsetMeters(), MAX_STATION_OFFSET_METERS));
            }
            if (i > 0) {
                double meters = at.chainageMeters() - previous;
                if (meters <= 0) {
                    throw new IllegalArgumentException("Line " + lineId + ": station " + s.id()
                            + " is not further along the track than " + stationIds.get(i - 1)
                            + "; list stationIds in the direction the track is drawn");
                }
                runSeconds.add((int) Math.max(1, Math.round(meters / metersPerSecond)));
            } else {
                first = at.chainageMeters();
            }
            previous = at.chainageMeters();
        }
        if (ring) {
            double closing = track.lengthMeters() - previous + first;
            runSeconds.add((int) Math.max(1, Math.round(closing / metersPerSecond)));
        }
        return runSeconds;
    }

    private static String featureId(JsonNode feature) {
        JsonNode id = feature.path("properties").path("id");
        if (id.isMissingNode() || id.isNull()) {
            id = feature.path("id");
        }
        if (id.isMissingNode() || id.isNull() || id.asText().isBlank()) {
            throw new IllegalArgumentException("Every feature needs an id (feature.id or properties.id)");
        }
        return id.asText();
    }

    private static double[] point(JsonNode coords, String featureId) {
        if (!coords.isArray() || coords.size() < 2 || !coords.get(0).isNumber() || !coords.get(1).isNumber()) {
            throw new IllegalArgumentException("Feature " + featureId + ": coordinates must be [lon, lat]");
        }
        return new double[]{coords.get(0).asDouble(), coords.get(1).asDouble()};
    }
}
