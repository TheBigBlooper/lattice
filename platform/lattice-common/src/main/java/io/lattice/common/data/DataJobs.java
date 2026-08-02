package io.lattice.common.data;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.indices.update_aliases.Action;
import io.lattice.common.es.EsRepository;
import io.lattice.common.es.IndexDefinition;
import java.io.StringReader;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The data jobs themselves, separated from {@link DataJobRunner} so the work is callable and
 * readable without going through a {@code main}.
 *
 * <p>The reindex follows the procedure written down in {@code data_model.md} rather than inventing
 * one: create the next concrete index with the current mapping, copy the documents into it, then
 * move the read and write aliases onto it. Callers only ever address the aliases, so the swap is
 * invisible to a running service.
 */
final class DataJobs {

    private static final Logger LOG = LoggerFactory.getLogger(DataJobs.class);

    private final ElasticsearchClient client;

    /**
     * @param client the Elasticsearch client to act through.
     */
    DataJobs(ElasticsearchClient client) {
        this.client = client;
    }

    /**
     * Rebuilds each index behind its aliases and keeps every document.
     *
     * <p>The old concrete index is deliberately <b>left in place</b>. It is the only copy of the data
     * until someone has looked at the new one, and a job that deletes it in the same breath removes
     * the thing you would want if the mapping change turns out to be wrong.
     *
     * @param indices logical index name to the definition it is built from.
     * @throws Exception if Elasticsearch refuses any step, so the job exits non-zero rather than
     *     leaving a half-moved alias unreported.
     */
    void reindex(Map<String, IndexDefinition> indices) throws Exception {
        for (var entry : indices.entrySet()) {
            var logical = entry.getKey();
            var definition = entry.getValue();
            var current = concreteIndexBehind(logical);
            var next = nextConcreteIndex(current);

            LOG.info("reindex {}: {} -> {}", logical, current, next);

            // MAPPING_JSON is a mappings body, so it nests under .mappings() as ensureIndex does;
            // at the top level it fails on "Unknown field 'dynamic'". Settings are applied for the
            // same reason - built from the mapping alone the index takes the default replica count.
            client.indices().create(c -> {
                c.index(next).mappings(m -> m.withJson(new StringReader(definition.mappingJson())));
                if (definition.settingsJson() != null) {
                    c.settings(s -> s.withJson(new StringReader(definition.settingsJson())));
                }
                return c;
            });
            client.reindex(r -> r.source(s -> s.index(current)).dest(d -> d.index(next)));
            // Refreshed before the swap, so a read through the moved alias cannot land on documents
            // that are copied but not yet searchable.
            client.indices().refresh(r -> r.index(next));

            // One updateAliases call, so the read and write aliases move together. Two calls would
            // leave a window where reads and writes address different indices.
            var detach = Action.of(
                    act -> act.remove(rm -> rm.index(current).aliases(logical, EsRepository.writeAlias(logical))));
            var attachRead = Action.of(act -> act.add(ad -> ad.index(next).alias(logical)));
            var attachWrite = Action.of(act -> act.add(
                    ad -> ad.index(next).alias(EsRepository.writeAlias(logical)).isWriteIndex(true)));

            client.indices().updateAliases(a -> a.actions(detach, attachRead, attachWrite));

            LOG.info("reindex {}: aliases now point at {}; {} kept, delete it once satisfied", logical, next, current);
        }
    }

    /**
     * Deletes every index behind each alias. Guarded away from production by {@link DataJobGuard}.
     *
     * <p>Only the names are read. This job deletes and never creates, so the definitions play no part
     * in what it does; the indices come back the next time a service starts and runs its bootstrap,
     * which is where the committed mapping and settings are applied. It takes the same map as the
     * other jobs so a caller has one set of targets to pass, not two shapes to keep in step.
     *
     * @param indices logical index name to the definition it is built from, of which only the names
     *                are used.
     * @throws Exception if Elasticsearch refuses a delete.
     */
    void reset(Map<String, IndexDefinition> indices) throws Exception {
        for (var logical : indices.keySet()) {
            // Resolved to concrete names first, then deleted by name. Elasticsearch refuses a wildcard
            // delete by default, and naming what is being deleted is the property this job wants
            // anyway - so the default is worked with rather than around.
            var concrete = client.indices()
                    .get(g -> g.index(logical + "-*").ignoreUnavailable(true))
                    .result()
                    .keySet();
            if (concrete.isEmpty()) {
                LOG.info("reset {}: nothing to delete", logical);
                continue;
            }
            LOG.warn("reset {}: deleting {}", logical, concrete);
            client.indices().delete(d -> d.index(List.copyOf(concrete)));
        }
        LOG.info("reset complete - a service recreates its indices from the mapping and settings on next start");
    }

    /**
     * Loads the dev dataset through the write aliases.
     *
     * <p>Through the alias rather than a concrete index deliberately: seeding is exactly where
     * someone would hardcode {@code orders-000001} and quietly break the day a reindex moves the
     * alias to {@code orders-000002}.
     *
     * @param clusterId this baseline's cluster id, which selects the dataset it is seeded with. Each
     *     baseline holds its own data, and one holds an inventory model its peers do not declare
     *     (locked #14), so the documents written here are a function of the baseline.
     * @throws Exception if a write is refused.
     */
    void seed(String clusterId) throws Exception {
        // Defect note. Symptom: a service can never bootstrap again, reporting
        //   Invalid alias name [orders-write]: an index or data stream exists with the same name
        // and every later seed fails with "no such index".
        //
        // Elasticsearch auto-creates an index for an unknown write target, so a seed running before
        // the owning services have bootstrapped creates an index carrying the write alias's name.
        // The baseline is permanently broken from that moment, and recovery is by hand - hence the
        // refusal before anything is written, which is why the order of these checks is the point.
        requireBootstrapped("orders");
        requireBootstrapped("inventory");

        var orders = DevDataset.orders(clusterId);
        var inventory = DevDataset.inventory(clusterId);
        bulkIndex("orders", "orderId", orders);
        bulkIndex("inventory", "sku", inventory);

        client.indices().refresh(r -> r.index("orders", "inventory"));
        LOG.info("seeded {} orders and {} inventory items for cluster {}", orders.size(), inventory.size(), clusterId);
    }

    /**
     * Writes one logical index's seed documents in a single bulk request.
     *
     * <p>A request per document was fine for a handful and is not for a few hundred: the round trips
     * dominate, and a seed slow enough to look hung is a seed someone interrupts half-written.
     *
     * @param logical the logical index name; the write alias is derived from it.
     * @param idField the document field whose value becomes the document id, so re-running the seed
     *     overwrites rather than accumulating duplicates.
     * @param documents the documents to write.
     * @throws IllegalStateException if Elasticsearch rejects any document in the batch.
     */
    private void bulkIndex(String logical, String idField, List<Map<String, Object>> documents) throws Exception {
        var response = client.bulk(bulk -> {
            bulk.index(EsRepository.writeAlias(logical));
            for (var document : documents) {
                bulk.operations(op -> op.index(
                        idx -> idx.id(String.valueOf(document.get(idField))).document(document)));
            }
            return bulk;
        });
        if (response.errors()) {
            // A strict mapping rejects an undeclared field per document rather than failing the request,
            // so a bulk that "succeeded" can still have written nothing. Name the first cause: they are
            // almost always the same one repeated, and printing all of them buries it.
            var firstFailure = response.items().stream()
                    .filter(item -> item.error() != null)
                    .findFirst()
                    .map(item -> item.id() + ": " + item.error().reason())
                    .orElse("unreported");
            throw new IllegalStateException(
                    "seeding " + logical + " was partially refused - first failure " + firstFailure);
        }
    }

    /**
     * The concrete index the read alias currently points at.
     *
     * <p>A missing alias arrives as a 404 from Elasticsearch rather than as an empty result - an
     * alias exists only as an attachment to an index, so deleting the index takes the alias with it.
     * It is translated here because the raw error names an index nobody asked about, while the
     * likely cause is simply that this cluster's services have never started.
     */
    private String concreteIndexBehind(String logical) throws Exception {
        try {
            var response = client.indices().getAlias(a -> a.name(logical));
            return List.copyOf(response.result().keySet()).get(0);
        } catch (ElasticsearchException missing) {
            throw new IllegalStateException(
                    "no index behind alias '" + logical
                            + "' - start the service that owns it once, so it bootstraps its indices",
                    missing);
        }
    }

    /**
     * Refuses unless the logical index has been bootstrapped by the service that owns it.
     *
     * <p>A guard rather than a lookup, which is why it discards what it finds: the caller does not
     * want the concrete index, it wants to know that writing through the alias is safe. Named for
     * that intent, because {@code concreteIndexBehind(...)} called for its exception alone reads
     * like a mistake somebody would later "clean up".
     */
    private void requireBootstrapped(String logical) throws Exception {
        concreteIndexBehind(logical);
    }

    /** {@code orders-000001} to {@code orders-000002}; the counter is what makes a reindex repeatable. */
    static String nextConcreteIndex(String current) {
        var split = current.lastIndexOf('-');
        var prefix = current.substring(0, split + 1);
        var number = Integer.parseInt(current.substring(split + 1));
        return prefix + String.format("%06d", number + 1);
    }
}
