import type { components } from "../api/generated/v1.ts";
import { leading, type Palette, scale, type as typeScale } from "../theme/tokens.ts";
import { StatusBlock } from "./StatusBlock.tsx";
import { StatusIcon } from "./StatusIcon.tsx";
import { StatusPill } from "./StatusPill.tsx";

type ClusterHealth = components["schemas"]["ClusterHealth"];
type ServiceHealth = components["schemas"]["ServiceHealth"];

/** What the verdict needs: the cluster's own rollup and the services behind it. */
export interface ClusterVerdictProps {
  /** The rollup as the cluster reports it. Never recomputed here. */
  health: ClusterHealth;
  /** The per-service readiness behind that rollup. */
  services: ServiceHealth[];
  /** The active palette. */
  palette: Palette;
}

/** Maps a verdict to its palette colour. Exhaustive, so a new state cannot be added silently. */
function toneFor(health: ClusterHealth, palette: Palette): string {
  switch (health) {
    case "ready":
      return palette.statusReady;
    case "degraded":
      return palette.statusDegraded;
    case "down":
      return palette.statusDown;
    default:
      return palette.textSecondary;
  }
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
 * @param props the rollup, the services behind it, and the palette.
 * @returns the verdict block.
 */
export function ClusterVerdict({ health, services, palette }: ClusterVerdictProps) {
  const tone = toneFor(health, palette);
  const ready = services.filter((service) => service.status === "UP").length;

  return (
    <>
      <StatusBlock tone={tone} palette={palette}>
        <span
          style={{
            alignItems: "center",
            display: "flex",
            fontSize: typeScale.verdict,
            gap: scale.sm,
            lineHeight: leading.verdict,
          }}
        >
          <StatusIcon tone={health} size={typeScale.verdict} />
          {health}
        </span>
        {services.length > 0 && (
          <span style={{ fontSize: typeScale.body, lineHeight: leading.body }}>
            {ready} of {services.length} services ready
          </span>
        )}
      </StatusBlock>
      {services.length > 0 && (
        <ul
          aria-label="services"
          style={{
            display: "flex",
            flexWrap: "wrap",
            gap: scale.sm,
            listStyle: "none",
            margin: `${scale.md}px 0 0`,
            padding: 0,
          }}
        >
          {services.map((service) => (
            <StatusPill
              key={service.name}
              name={service.name}
              palette={palette}
              status={service.status}
            />
          ))}
        </ul>
      )}
    </>
  );
}
