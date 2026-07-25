import type { components } from "../api/generated/v1.ts";
import { type Palette, radius, scale, type as typeScale } from "../theme/tokens.ts";
import { StatusIcon } from "./StatusIcon.tsx";

type ServiceHealth = components["schemas"]["ServiceHealth"];

/** What a pill needs to render one service's state. */
export interface StatusPillProps {
  /** The service's display name, as the cluster reports it. */
  name: ServiceHealth["name"];
  /** Whether the service's readiness probe answered. */
  status: ServiceHealth["status"];
  /** The active palette; colour is never written inline. */
  palette: Palette;
}

/**
 * One service's state: its name, a glyph, and the state as a word.
 *
 * The word is not optional decoration beside the colour. An operator who cannot distinguish red
 * from green must still be able to read this console, so every state is carried by text and shape
 * as well as hue. That rule is why this component exists at all rather than each panel colouring
 * its own text.
 *
 * @param props the service and the palette to render it with.
 * @returns a list item, so a group of pills is a list to assistive technology rather than a run of
 *     unrelated text.
 */
export function StatusPill({ name, status, palette }: StatusPillProps) {
  const isUp = status === "UP";
  return (
    <li
      style={{
        alignItems: "center",
        backgroundColor: palette.surfaceRaised,
        borderRadius: radius.pill,
        color: isUp ? palette.statusReady : palette.statusDown,
        display: "inline-flex",
        fontSize: typeScale.meta,
        gap: scale.xs,
        padding: `${scale.xs}px ${scale.sm}px`,
      }}
    >
      <StatusIcon tone={isUp ? "ready" : "down"} size={typeScale.meta} />
      <span style={{ color: palette.textPrimary }}>{name}</span>
      <span>{isUp ? "up" : "down"}</span>
    </li>
  );
}
