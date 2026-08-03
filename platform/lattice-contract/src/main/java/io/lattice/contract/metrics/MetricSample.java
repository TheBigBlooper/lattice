package io.lattice.contract.metrics;

import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One measured value from a service's registry, with the labels that identify which series it is.
 *
 * <p><b>Flat rather than nested.</b> A meter with several label sets becomes several samples here,
 * not one sample carrying a list. The console renders both its cards and its full series table from
 * the same payload, and the table is a flat list by nature - a nested shape would be flattened on
 * arrival to draw it, so the flattening happens once, on the side that already knows the structure.
 *
 * @param name   the meter name, in the registry's own dotted form.
 * @param kind   what the value means, so a reader knows whether to derive a rate from it.
 * @param labels the label set identifying this series; empty when the meter carries none.
 * @param value  the value at the moment the snapshot was taken.
 */
public record MetricSample(String name, MetricKind kind, Map<String, String> labels, double value) {

    /**
     * Normalizes the label map so it is never null and never mutable from outside.
     *
     * @param name   the meter name.
     * @param kind   the meter kind.
     * @param labels the label set, or null for none.
     * @param value  the measured value.
     */
    public MetricSample {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }

    /**
     * Serializes this sample to JSON.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var encodedLabels = new JsonObject();
        labels.forEach(encodedLabels::put);
        return new JsonObject()
                .put("name", name)
                .put("kind", kind.name())
                .put("labels", encodedLabels)
                .put("value", value);
    }

    /**
     * Parses a sample from JSON, ignoring unknown fields.
     *
     * <p>An absent label block yields an empty map rather than a null, so no reader has to guard a
     * collection - the same null-safety rule the rest of the contract follows.
     *
     * @param json the JSON to read.
     * @return the parsed sample.
     */
    public static MetricSample fromJson(JsonObject json) {
        var encodedLabels = json.getJsonObject("labels");
        Map<String, String> labels = new LinkedHashMap<>();
        if (encodedLabels != null) {
            encodedLabels.forEach(entry -> labels.put(entry.getKey(), String.valueOf(entry.getValue())));
        }
        return new MetricSample(
                json.getString("name"),
                MetricKind.valueOf(json.getString("kind")),
                labels,
                json.getDouble("value", 0.0));
    }
}
