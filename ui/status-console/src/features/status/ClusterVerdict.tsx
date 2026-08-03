import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import type { components } from "../../api/generated/v1.ts";
import {
  PANEL_HELP,
  PanelHeader,
  PanelHelp,
  PanelRollup,
  SCROLL_PANE,
  StatusRow,
} from "../../shared/index.ts";
import { type ClusterHealth, healthForService } from "../../theme/tone.ts";

type ServiceHealth = components["schemas"]["ServiceHealth"];

/** What the verdict needs: the cluster's own rollup and the services behind it. */
export interface ClusterVerdictProps {
  /** The rollup as the cluster reports it. Never recomputed here. */
  health: ClusterHealth;
  /** The per-service readiness behind that rollup. */
  services: ServiceHealth[];
}

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
  const ready = services.filter((service) => service.status === "UP").length;

  return (
    // Its own frame rather than the shared status block: the verdict sits in a rail and has to fill
    // its share of the height and scroll its own list. The tone is deliberately not set here -
    // on the surface it would tint the rows too, at a ratio traded for the status word alone.
    <Paper
      aria-live="polite"
      role="status"
      sx={{ display: "flex", flexDirection: "column", height: "100%", p: 2 }}
    >
      <PanelHeader
        help={<PanelHelp content={PANEL_HELP.baseline} label="Baseline" />}
        label="Baseline"
      />

      {/* The same size as the infrastructure rollup beside it. The verdict keeps its primacy by
          being first in the rail rather than by being larger - two rollups of the same shape at two
          scales read as an inconsistency before they read as a hierarchy. */}
      <PanelRollup
        capitalize
        count={services.length > 0 ? `${ready} of ${services.length} services ready` : undefined}
        label={health}
        tone={health}
      />

      {/* The list scrolls, not the card: the verdict and its count stay put while a baseline with
          a dozen services is read through. Full width so each row own state lands in one column. */}
      {services.length > 0 && (
        <Box
          aria-label="services"
          component="ul"
          sx={{
            flex: 1,
            listStyle: "none",
            m: 0,
            mt: 1.5,
            p: 0,
            width: "100%",
            ...SCROLL_PANE,
          }}
        >
          {services.map((service) => (
            <StatusRow
              key={service.name}
              name={service.name}
              state={healthForService(service.status)}
            />
          ))}
        </Box>
      )}
    </Paper>
  );
}
