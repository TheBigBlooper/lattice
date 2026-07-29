import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import type { components } from "../api/generated/v1.ts";
import { type ClusterHealth, healthForComponent, toneForHealth } from "../theme/tone.ts";
import { PanelHeader } from "./PanelHeader.tsx";
import { StatusIcon } from "./StatusIcon.tsx";
import { StatusRow } from "./StatusRow.tsx";

/** One infrastructure component's state, exactly as the contract defines it. */
type ComponentHealth = components["schemas"]["ComponentHealth"];

/** What the card renders. */
export interface InfrastructureCardProps {
  /**
   * The infrastructure this baseline depends on, in the order its gateway reports it. Empty when
   * the deployment configures none, which is a supported deployment rather than a fault.
   */
  components: ComponentHealth[];
}

/**
 * Rolls the components up into the one word the card leads with.
 *
 * <p>It reuses the vocabulary the cluster verdict already speaks - every one healthy reads ready,
 * none healthy reads down, anything between reads degraded - so the screen teaches that logic once
 * and applies it twice rather than asking an operator to learn a second scale halfway down a rail.
 *
 * <p>A component that is degraded is not counted as healthy: it is serving with something genuinely
 * lost, and a rollup that rounded it up would report the exact condition this card exists to show.
 */
function rollupState(healthy: number, total: number): ClusterHealth {
  if (healthy === total) {
    return "ready";
  }
  return healthy === 0 ? "down" : "degraded";
}

/**
 * The infrastructure this baseline's services depend on: a rollup, then one row per component.
 *
 * <p><b>Its own rollup rather than a share of the cluster's.</b> The verdict above it stays a rollup
 * of this baseline's own services, so the number a peer reads keeps meaning what it has always
 * meant and a datastore that has merely lost a replica cannot make a peer believe this baseline
 * cannot serve. The two groups speak different vocabularies, and stacking them keeps each one
 * honest instead of averaging them into a word that describes neither.
 *
 * <p><b>The rollup is counted here because nothing serves it.</b> That is the same bounded exception
 * the mesh panel already takes: counting how many components report healthy is arithmetic over a
 * field the gateway sets, not a second definition of what `degraded` means. No component's own
 * state is ever recomputed - each row renders exactly what was reported.
 *
 * <p><b>Nothing is drawn when nothing is reported.</b> A baseline that configures no infrastructure
 * gets no card, rather than one reading "unknown" three times - an empty card on a status screen
 * reads as a fault, where an absent one reads as an option nobody set.
 *
 * <p><b>It never branches on what kind of component a row is.</b> The coarse state exists precisely
 * so a baseline can add a component and have it render with no console change; deciding
 * renderability from the kind would give that away and put a console release in front of every
 * infrastructure addition.
 *
 * @param props the components this baseline reports.
 * @returns the card, or nothing at all when there is no infrastructure to report.
 */
export function InfrastructureCard({ components: reported }: InfrastructureCardProps) {
  if (reported.length === 0) {
    return null;
  }

  const healthy = reported.filter((component) => component.status === "UP").length;
  const state = rollupState(healthy, reported.length);
  const tone = toneForHealth(state);

  return (
    // A section rather than a live region: the cluster verdict above it is the one thing on this
    // rail that announces itself, and a second live region polling beside it would talk over the
    // announcement an operator is actually waiting on.
    <Paper aria-label="infrastructure" component="section" sx={{ minHeight: 0, p: 2 }}>
      <PanelHeader label="Infrastructure" />

      {/* Capitalised for display only, so the value itself stays the one the rest of the console
          branches on. lineHeight 1 is what actually centres the glyph: a heading line box is taller
          than its letters, so an icon centred against the box sits visibly high against the text. */}
      <Typography
        component="h3"
        sx={{
          alignItems: "center",
          color: tone,
          display: "flex",
          gap: 1,
          lineHeight: 1,
          textTransform: "capitalize",
        }}
        variant="h6"
      >
        <StatusIcon size={20} tone={state} />
        {state}
      </Typography>

      <Typography component="p" sx={{ mt: 0.5 }} variant="body2">
        {healthy} of {reported.length} components healthy
      </Typography>

      {/* The list scrolls, not the card, so the rollup stays put while a baseline running more
          than a handful of components is read through. */}
      <Box
        aria-label="infrastructure components"
        component="ul"
        sx={{ listStyle: "none", m: 0, minHeight: 0, mt: 1.5, overflowY: "auto", p: 0 }}
      >
        {reported.map((component) => (
          <StatusRow
            detail={component.detail}
            key={component.name}
            name={component.name}
            state={healthForComponent(component.status)}
          />
        ))}
      </Box>
    </Paper>
  );
}
