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

            // The committed MAPPING_JSON is a mappings body, not a whole create-index body - it is
            // nested under .mappings() exactly as EsRepository.ensureIndex does. Passing it at the
            // top level fails on the first field it does not recognise ("Unknown field 'dynamic'").
            //
            // The settings are applied for the same reason the mapping is: the new index has to be the
            // index that was committed, not merely one holding the same documents. Built from the
            // mapping alone it took the cluster default replica count instead, which put a single-node
            // baseline back to permanently yellow the moment anyone reindexed it.
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
            // Resolved to concrete names first, then deleted by name. Elasticsearch refuses a
            // wildcard delete outright (action.destructive_requires_name, on by default), and that
            // default is worth working with rather than around: naming what is being deleted is
            // exactly the property this job should have.
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
     * @throws Exception if a write is refused.
     */
    void seed() throws Exception {
        // REFUSED BEFORE ANYTHING IS WRITTEN, and the order is the whole point. Elasticsearch
        // auto-creates an index for an unknown write target, so a seed that runs before the owning
        // services have bootstrapped does not merely fail - the first write creates an INDEX carrying
        // the write alias's name, and the service's bootstrap can then never create that alias:
        //
        //   Invalid alias name [orders-write]: an index or data stream exists with the same name
        //
        // The baseline is broken from that moment, every later seed fails with "no such index", and
        // recovery means deleting the bogus indices by hand and rolling the owners. Checking first
        // costs two requests and turns permanent damage into a sentence telling the operator what to
        // do about it.
        requireBootstrapped("orders");
        requireBootstrapped("inventory");

        for (var order : DevDataset.orders()) {
            client.index(i -> i.index(EsRepository.writeAlias("orders"))
                    .id(String.valueOf(order.get("orderId")))
                    .document(order));
        }
        for (var item : DevDataset.inventory()) {
            client.index(i -> i.index(EsRepository.writeAlias("inventory"))
                    .id(String.valueOf(item.get("sku")))
                    .document(item));
        }
        client.indices().refresh(r -> r.index("orders", "inventory"));
        LOG.info(
                "seeded {} orders and {} inventory items",
                DevDataset.orders().size(),
                DevDataset.inventory().size());
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
