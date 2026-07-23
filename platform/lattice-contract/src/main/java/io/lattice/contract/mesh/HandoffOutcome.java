package io.lattice.contract.mesh;

/**
 * The outcome a receiving cluster reports for a {@link FulfillmentHandoff}, carried
 * in a {@link HandoffAck}.
 *
 * <p>A closed set of simple status labels (not variant types with data), so an enum
 * is the right shape here.
 */
public enum HandoffOutcome {
    /** The peer accepted the handoff (e.g. reserved stock and took ownership). */
    ACCEPTED,
    /** The peer could not fulfil the handoff (e.g. also out of stock, or an unsupported version). */
    REJECTED
}
