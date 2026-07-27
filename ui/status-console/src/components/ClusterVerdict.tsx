import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { components } from "../api/generated/v1.ts";
import { type ClusterHealth, toneForHealth } from "../theme/tone.ts";
import { StatusBlock } from "./StatusBlock.tsx";
import { StatusIcon } from "./StatusIcon.tsx";
import { StatusPill } from "./StatusPill.tsx";

type ServiceHealth = components["schemas"]["ServiceHealth"];

/** What the verdict needs: the cluster's own rollup and the services behind it. */
export interface ClusterVerdictProps {
  /** The rollup as the cluster reports it. Never recomputed here. */
  health: ClusterHealth;
  /** The per-service readiness behind that rollup. */
  services: ServiceHealth[];
}

/** The glyphs this console draws. Any other health renders neutrally, without one. */
const DRAWN: ReadonlySet<string> = new Set(["ready", "degraded", "down"]);

/**
 * The cluster's verdict, with the per-service breakdown beneath it.
 *
 * **The verdict is read, not computed.** The mesh-gateway already rolls its services' readiness
 * into one label and serves it; recomputing that here would fork the definition of "degraded"
 * across two languages and let this console disagree with what the baseline announces to its peers.
 * The count below it is derived from the same payload purely to say how degraded.
 *
 * The breakdown stays on screen rather than hiding behind a click, so an operator seeing
 * `degraded` sees which service caused it in the same glance.
 *
 * @param props the rollup and the services behind it.
 * @returns the verdict block.
 */
export function ClusterVerdict({ health, services }: ClusterVerdictProps) {
  const tone = toneForHealth(health);
  const ready = services.filter((service) => service.status === "UP").length;

  return (
    <>
      <StatusBlock tone={tone}>
        <Typography
          component="span"
          sx={{ alignItems: "center", display: "flex", gap: 1 }}
          variant="h4"
        >
          {DRAWN.has(health) && (
            <StatusIcon size={34} tone={health as "ready" | "degraded" | "down"} />
          )}
          {health}
        </Typography>
        {services.length > 0 && (
          <Typography component="span" variant="body2">
            {ready} of {services.length} services ready
          </Typography>
        )}
      </StatusBlock>
      {services.length > 0 && (
        <Box
          aria-label="services"
          component="ul"
          sx={{
            display: "flex",
            flexWrap: "wrap",
            gap: 1,
            listStyle: "none",
            m: 0,
            mt: 2,
            p: 0,
          }}
        >
          {services.map((service) => (
            <StatusPill key={service.name} name={service.name} status={service.status} />
          ))}
        </Box>
      )}
    </>
  );
}
