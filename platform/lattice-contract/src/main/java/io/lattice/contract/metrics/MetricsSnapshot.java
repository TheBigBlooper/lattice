package io.lattice.contract.metrics;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * One service's selected meters at the moment it was asked, as the console reads them.
 *
 * <p><b>Selected, not the whole registry.</b> It carries Lattice's own meters; everything the
 * runtime and the toolkit register about themselves stays on the scrape endpoint, which remains the
 * complete surface and the collector's path. Measured before the boundary was drawn there: a running
 * gateway served 52 samples, 40 of them toolkit series that no console surface reads. This is a
 * narrower reader for a browser, not a replacement.
 *
 * <p>The snapshot names the service that produced it because the console reads every service
 * separately and renders them together - without the name, two identical meter names from two
 * services would be indistinguishable once merged.
 *
 * @param service the service that produced these samples, by the name it goes by everywhere else.
 * @param samples the selected samples; empty when the registry holds none yet.
 */
public record MetricsSnapshot(String service, List<MetricSample> samples) {

    /**
     * Normalizes the sample list so it is never null and never mutable from outside.
     *
     * @param service the producing service.
     * @param samples the samples, or null for none.
     */
    public MetricsSnapshot {
        samples = samples == null ? List.of() : List.copyOf(samples);
    }

    /**
     * Serializes this snapshot to JSON.
     *
     * @return the JSON representation.
     */
    public JsonObject toJson() {
        var encoded = new JsonArray();
        samples.forEach(sample -> encoded.add(sample.toJson()));
        return new JsonObject().put("service", service).put("samples", encoded);
    }

    /**
     * Parses a snapshot from JSON, ignoring unknown fields.
     *
     * <p>An absent sample block yields an empty list rather than a null. A service whose registry
     * holds nothing yet is a real state - it has just started - and it must not read as a failure.
     *
     * @param json the JSON to read.
     * @return the parsed snapshot.
     */
    public static MetricsSnapshot fromJson(JsonObject json) {
        var encoded = json.getJsonArray("samples");
        List<MetricSample> samples = new ArrayList<>();
        if (encoded != null) {
            encoded.forEach(entry -> samples.add(MetricSample.fromJson((JsonObject) entry)));
        }
        return new MetricsSnapshot(json.getString("service"), samples);
    }
}
