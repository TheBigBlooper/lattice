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
  /** This baseline's API base, e.g. http://localhost:8082/api/v1 */
  apiBaseUrl: string;
  /** The baseline this console belongs to, shown to an operator working across several. */
  clusterId: string;
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
    clusterId: env.VITE_CLUSTER_ID || "this baseline",
  };
}
