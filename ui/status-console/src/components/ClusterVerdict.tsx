import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import type { components } from "../api/generated/v1.ts";
import { type ClusterHealth, toneForHealth } from "../theme/tone.ts";
import { ServiceRow } from "./ServiceRow.tsx";
import { StatusBlock } from "./StatusBlock.tsx";
import { StatusIcon } from "./StatusIcon.tsx";

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
    <StatusBlock tone={tone}>
      <Box>
        <Typography
          component="span"
          // Capitalised for display only. The value itself stays exactly as the baseline reported
          // it, because everything that branches on health compares the contract's own lowercase.
          sx={{ alignItems: "center", display: "flex", gap: 1, textTransform: "capitalize" }}
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
      </Box>

      {/* Inside the card and at its base, rather than loose beneath it. The verdict and the
          services that produced it are one statement, and separating them left the column ending
          short of the mesh panel with nothing between the two edges. */}
      {services.length > 0 && (
        <Box aria-label="services" component="ul" sx={{ listStyle: "none", m: 0, mt: 1.5, p: 0 }}>
          {services.map((service) => (
            <ServiceRow key={service.name} name={service.name} status={service.status} />
          ))}
        </Box>
      )}
    </StatusBlock>
  );
}
