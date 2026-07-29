/**
 * The console's configuration, read once from the build-time environment.
 *
 * Every value arrives as a `VITE_*` variable, which Vite inlines at build time. That has a
 * consequence worth stating where the values are defined: a variable absent when the image was
 * built is absent in the image, however the container is later configured. Setting it at runtime
 * does nothing.
 *
 * Reading them here rather than in components is what keeps that surface one file wide, and is why
 * the API base is a parameter to the data layer rather than something it reaches for itself.
 */
export interface ConsoleConfig {
  /**
   * The **mesh-gateway's** API base, e.g. http://localhost:8082/api/v1
   *
   * <p>This serves `getBaseline` and `getPeers` and nothing else. Each service narrows the shared
   * contract to the operations it owns before building its router, so asking the gateway for an
   * order clears its auth, matches no route, and returns a 404 page.
   */
  apiBaseUrl: string;
  /**
   * The **orders service's** API base, e.g. http://localhost:8080/api/v1
   *
   * <p>Separate because a baseline's services are separate processes on separate ports. A deployed
   * baseline puts them behind one ingress and these collapse to paths on one host; locally they do
   * not, and pretending otherwise is what made the Orders view read HTML from the gateway.
   */
  ordersBaseUrl: string;
  /** The **inventory service's** API base, e.g. http://localhost:8081/api/v1 */
  inventoryBaseUrl: string;
  /** The baseline this console belongs to, shown to an operator working across several. */
  clusterId: string;
  /**
   * Where this baseline runs, for the signed-out screen.
   *
   * It is build-time config rather than a read, because the endpoint that reports it needs a token
   * and the screen that shows it is the one an operator sees before they have one. Empty when the
   * image was built without it, and the screen then says nothing rather than guessing.
   */
  region: string;
  /** The baseline version this console image was built for. Empty when unset, and then unshown. */
  baselineVersion: string;
  /** This baseline's own Keycloak base URL. Never a peer's. */
  keycloakUrl: string;
  /** This baseline's realm. */
  keycloakRealm: string;
  /** The public client the console authenticates as. */
  keycloakClientId: string;
}

/**
 * Reads the configuration from the environment.
 *
 * @returns the console's configuration, with local-development defaults where a value is unset.
 */
export function loadConfig(): ConsoleConfig {
  const env = import.meta.env;
  return {
    apiBaseUrl: env.VITE_API_BASE_URL || "http://localhost:8082/api/v1",
    ordersBaseUrl: env.VITE_ORDERS_BASE_URL || "http://localhost:8080/api/v1",
    inventoryBaseUrl: env.VITE_INVENTORY_BASE_URL || "http://localhost:8081/api/v1",
    clusterId: env.VITE_CLUSTER_ID || "this baseline",
    region: env.VITE_REGION || "",
    baselineVersion: env.VITE_BASELINE_VERSION || "",
    keycloakUrl: env.VITE_KEYCLOAK_URL || "http://localhost:8083",
    keycloakRealm: env.VITE_KEYCLOAK_REALM || "lattice",
    keycloakClientId: env.VITE_KEYCLOAK_CLIENT_ID || "lattice-console",
  };
}
