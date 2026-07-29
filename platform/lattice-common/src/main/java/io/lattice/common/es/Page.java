package io.lattice.common.es;

import java.util.List;

/**
 * One slice of a collection, plus how large the whole collection is.
 *
 * <p><b>The total is carried, not inferred.</b> The number of items in hand cannot tell a caller
 * whether there is another page - a full page might be the last one, and an empty page might sit
 * past the end of a collection that still has items before it. Elasticsearch already returns the
 * hit total with every search, so carrying it costs nothing and removes the guess.
 *
 * <p>It deliberately knows nothing about page indexes or sizes. Those belong to the request that
 * produced this slice, and duplicating them here would create a second place for them to disagree
 * with the query that actually ran.
 *
 * @param items the items in this slice, in the order the query sorted them.
 * @param total how many items exist across the whole collection.
 * @param <T>   the item type.
 */
public record Page<T>(List<T> items, long total) {

    /** Defensive copy: the list is exposed on a record accessor. */
    public Page {
        items = List.copyOf(items);
    }
}
