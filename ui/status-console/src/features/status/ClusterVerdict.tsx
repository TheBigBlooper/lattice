import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import type { components } from "../../api/generated/v1.ts";
import { PanelHeader, PanelRollup, StatusRow } from "../../shared/index.ts";
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
    // Its own frame rather than the shared status block. The verdict sits in a rail beside the
    // activity panel now and has to behave the same way - fill its share of the height, and scroll
    // its own list - which is not what a fixed-height block centring its contents does.
    // The tone is NOT set here, deliberately. Setting it on the surface coloured everything that
    // did not override it - the count line and every service name in the list beneath - in a colour
    // held to 3:1 rather than 4.5:1 by a trade made for the status word alone. The rollup carries
    // its own colour now, and each row carries its own.
    <Paper
      aria-live="polite"
      role="status"
      sx={{ display: "flex", flexDirection: "column", height: "100%", p: 2 }}
    >
      <PanelHeader label="This baseline" />

      {/* The screen's primary answer, so it is the one rollup drawn at the larger size - the
          settled direction is that the verdict comes first, and flattening the three panels to one
          size would answer a question nobody asked by contradicting it. */}
      <PanelRollup
        capitalize
        count={services.length > 0 ? `${ready} of ${services.length} services ready` : undefined}
        label={health}
        size="primary"
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
            minHeight: 0,
            mt: 1.5,
            overflowY: "auto",
            p: 0,
            width: "100%",
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
