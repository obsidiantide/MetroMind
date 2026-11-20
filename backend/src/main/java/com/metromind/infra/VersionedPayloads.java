package com.metromind.infra;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON of the snapshots and telemetry that outlive the process that wrote them: the replay history in Redis and the
 * Kafka topics the KPI consumer reads, possibly from another instance running another build. Every payload carries
 * {@value #VERSION_FIELD}; a reader upgrades older layouts step by step to {@link #CURRENT_VERSION} before binding
 * them, and ignores fields it does not know, so entries written before a field was added still read, and a field
 * renamed in a later version only needs one more upgrade step here.
 *
 * <p>Versions:
 * <ol>
 *   <li>Entries written before payloads were versioned. Depending on their age they may lack a train's
 *       {@code patternId} (it was then always the line id) and lists added since, such as a station's
 *       {@code queues} or the snapshot's {@code activeIncidents}.</li>
 *   <li>The first versioned layout.</li>
 * </ol>
 */
public final class VersionedPayloads {

    public static final String VERSION_FIELD = "schemaVersion";
    public static final int CURRENT_VERSION = 2;

    /** What a payload holds, which decides how its older layouts are upgraded. */
    public enum Kind { SNAPSHOT, POSITION, NETWORK_METRICS, EVENT }

    private final ObjectMapper mapper;
    private final ObjectMapper reader;

    public VersionedPayloads(ObjectMapper mapper) {
        this.mapper = mapper;
        this.reader = mapper.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /** {@code payload} as JSON tagged with the current version. */
    public String write(Object payload) {
        ObjectNode node = mapper.valueToTree(payload);
        node.put(VERSION_FIELD, CURRENT_VERSION);
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise " + payload.getClass().getSimpleName(), e);
        }
    }

    /**
     * Reads a payload of any version: an untagged one counts as version 1. A payload from a newer writer is read as
     * far as this build understands it.
     */
    public <T> T read(String json, Kind kind, Class<T> type) throws JsonProcessingException {
        JsonNode tree = reader.readTree(json);
        if (!(tree instanceof ObjectNode node)) {
            throw new JsonMappingException(null, "Expected a JSON object for " + kind + ", got " + tree.getNodeType());
        }
        int version = node.path(VERSION_FIELD).asInt(1);
        node.remove(VERSION_FIELD);
        for (int v = version; v < CURRENT_VERSION; v++) {
            upgrade(kind, v, node);
        }
        return reader.treeToValue(node, type);
    }

    /** Rewrites {@code node} from layout {@code from} to {@code from + 1}. */
    private static void upgrade(Kind kind, int from, ObjectNode node) {
        if (from == 1) {
            switch (kind) {
                case SNAPSHOT -> {
                    for (String list : new String[] {"trains", "stations", "recentEvents", "closedSections",
                            "activeIncidents"}) {
                        ensureArray(node, list);
                    }
                    node.get("trains").forEach(t -> fillPattern((ObjectNode) t));
                    node.get("stations").forEach(s -> ensureArray((ObjectNode) s, "queues"));
                }
                case POSITION -> {
                    if (node.get("train") instanceof ObjectNode train) {
                        fillPattern(train);
                    }
                }
                case NETWORK_METRICS, EVENT -> {
                    // Unchanged since before versioning.
                }
            }
        }
    }

    /** Before branched lines every train followed its line's only pattern, whose id is the line id. */
    private static void fillPattern(ObjectNode train) {
        if (train.path("patternId").isMissingNode() || train.path("patternId").isNull()) {
            train.set("patternId", train.get("lineId"));
        }
    }

    private static void ensureArray(ObjectNode node, String field) {
        if (!node.path(field).isArray()) {
            node.putArray(field);
        }
    }
}
