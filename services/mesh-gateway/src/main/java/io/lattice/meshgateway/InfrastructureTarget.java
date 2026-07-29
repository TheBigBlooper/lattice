package io.lattice.meshgateway;

import io.lattice.contract.mesh.ComponentKind;

/**
 * One infrastructure component this baseline reports on, as configured.
 *
 * @param name the label the console renders, chosen by the deployment.
 * @param kind which piece of infrastructure it is, and so which probe the gateway runs.
 * @param url  where to probe it, empty for a kind whose state is read from the gateway itself.
 */
public record InfrastructureTarget(String name, ComponentKind kind, String url) {}
