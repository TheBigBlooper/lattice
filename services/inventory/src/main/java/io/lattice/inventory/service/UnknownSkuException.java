package io.lattice.inventory.service;

/**
 * Signals that a reserve targeted a sku with no inventory item - the business outcome the route maps
 * to 404 {@code NOT_FOUND}, distinct from a dependency failure or a stock conflict.
 */
public final class UnknownSkuException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception naming the unknown sku.
     *
     * @param sku the sku that has no inventory item.
     */
    public UnknownSkuException(String sku) {
        super("no inventory item for sku " + sku);
    }
}
