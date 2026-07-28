import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import type { components } from "../api/generated/v1.ts";
import { type ClusterHealth, toneForHealth } from "../theme/tone.ts";
import { PanelHeader } from "./PanelHeader.tsx";
import { ServiceRow } from "./ServiceRow.tsx";
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
    // Its own frame rather than the shared status block. The verdict sits in a rail beside the
    // activity panel now and has to behave the same way - fill its share of the height, and scroll
    // its own list - which is not what a fixed-height block centring its contents does.
    <Paper
      aria-live="polite"
      role="status"
      sx={{ color: tone, display: "flex", flexDirection: "column", height: "100%", p: 2 }}
    >
      <PanelHeader label="This baseline" />

      <Typography
        component="span"
        // Capitalised for display only. The value itself stays exactly as the baseline reported
        // it, because everything that branches on health compares the contract own lowercase.
        //
        // lineHeight 1 is what actually centres the glyph: a heading line box is taller than its
        // letters, so an icon centred against the box sits visibly high against the text.
        sx={{
          alignItems: "center",
          display: "flex",
          gap: 1,
          lineHeight: 1,
          textTransform: "capitalize",
        }}
        variant="h4"
      >
        {DRAWN.has(health) && (
          <StatusIcon size={30} tone={health as "ready" | "degraded" | "down"} />
        )}
        {health}
      </Typography>

      {services.length > 0 && (
        <Typography component="span" sx={{ mt: 0.5 }} variant="body2">
          {ready} of {services.length} services ready
        </Typography>
      )}

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
            <ServiceRow key={service.name} name={service.name} status={service.status} />
          ))}
        </Box>
      )}
    </Paper>
  );
}
