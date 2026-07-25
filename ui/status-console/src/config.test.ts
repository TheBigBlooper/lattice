import { afterEach, describe, expect, it, vi } from "vitest";
import { loadConfig } from "./config.ts";

describe("loadConfig", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  /**
   * Configured values are used as given, including the realm. Every one of these points at THIS
   * baseline: a console never addresses a peer's Keycloak, for the same reason a service never
   * addresses a peer's broker.
   */
  it("reads the configured values", () => {
    vi.stubEnv("VITE_API_BASE_URL", "https://east.svc:8080/api/v1");
    vi.stubEnv("VITE_CLUSTER_ID", "hub-east");
    vi.stubEnv("VITE_KEYCLOAK_URL", "https://east.keycloak:8443");
    vi.stubEnv("VITE_KEYCLOAK_REALM", "lattice");
    vi.stubEnv("VITE_KEYCLOAK_CLIENT_ID", "lattice-console");

    expect(loadConfig()).toEqual({
      apiBaseUrl: "https://east.svc:8080/api/v1",
      clusterId: "hub-east",
      keycloakUrl: "https://east.keycloak:8443",
      keycloakRealm: "lattice",
      keycloakClientId: "lattice-console",
    });
  });

  /**
   * Unset values fall back to the local stack rather than to undefined. A console that renders
   * "undefined" as its baseline name, or reads from the string "undefined", fails in a way that
   * takes a moment to recognise; pointing at the documented local ports fails obviously instead.
   */
  it("falls back to the local stack when nothing is configured", () => {
    vi.stubEnv("VITE_API_BASE_URL", "");
    vi.stubEnv("VITE_CLUSTER_ID", "");
    vi.stubEnv("VITE_KEYCLOAK_URL", "");
    vi.stubEnv("VITE_KEYCLOAK_REALM", "");
    vi.stubEnv("VITE_KEYCLOAK_CLIENT_ID", "");

    const config = loadConfig();

    expect(config.apiBaseUrl).toBe("http://localhost:8082/api/v1");
    expect(config.clusterId).toBe("this baseline");
    expect(config.keycloakUrl).toBe("http://localhost:8083");
    expect(config.keycloakRealm).toBe("lattice");
    expect(config.keycloakClientId).toBe("lattice-console");
  });
});
