package io.lattice.common.data;

/**
 * The data-maintenance jobs a baseline can be asked to run against its own Elasticsearch.
 *
 * <p>They are separated by <b>what they do to data</b> rather than by convenience, because that is
 * what {@link DataJobGuard} has to reason about: one of these rebuilds an index and keeps every
 * document, and two of them do not.
 */
public enum DataJob {

    /**
     * Rebuilds an index from the committed mapping and moves the aliases onto it, copying the
     * documents across. Non-destructive: it is the supported way to apply a mapping change that
     * cannot be applied in place, and it is legitimate in production.
     */
    REINDEX,

    /**
     * Loads the dev dataset. Writes invented records, so it is refused in production - the
     * environment map says production carries real data only.
     */
    SEED,

    /**
     * Deletes a baseline's indices and recreates them empty. The bluntest of the three, for a local
     * or dev cluster that has been left in a state nobody wants to unpick. Refused in production.
     */
    RESET
}
