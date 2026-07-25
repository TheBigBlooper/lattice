import { afterEach, describe, expect, it, vi } from "vitest";
import { loadConfig } from "./config.ts";

describe("loadConfig", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  /** Configured values are used as given. */
  it("reads the configured values", () => {
    vi.stubEnv("VITE_API_BASE_URL", "https://east.svc:8080/api/v1");
    vi.stubEnv("VITE_CLUSTER_ID", "hub-east");

    expect(loadConfig()).toEqual({
      apiBaseUrl: "https://east.svc:8080/api/v1",
      clusterId: "hub-east",
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

    const config = loadConfig();

    expect(config.apiBaseUrl).toBe("http://localhost:8082/api/v1");
    expect(config.clusterId).toBe("this baseline");
  });
});
