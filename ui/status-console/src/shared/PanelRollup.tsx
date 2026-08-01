import Typography from "@mui/material/Typography";
import type { ReactNode } from "react";
import { type ClusterHealth, toneForHealth } from "../theme/tone.ts";
import { StatusIcon } from "./StatusIcon.tsx";

/** The states this console can draw a glyph for. Anything else is rendered without one. */
const DRAWN: ReadonlySet<string> = new Set(["ready", "degraded", "down"]);

/** What a panel's rollup needs to draw itself. */
export interface PanelRollupProps {
  /** The rollup's own word, or a sentence where a panel rolls up a count rather than a state. */
  label: string;
  /** The state that decides the glyph and the tone, exactly as it was reported. */
  tone: string;
  /** The supporting count beneath it. Omitted where a panel has nothing to count. */
  count?: string;
  /** Larger for the screen's primary answer, which is the cluster's own verdict. */
  size?: "primary" | "panel";
  /** A glyph replacing the state icon, where the panel rolls up something that is not health. */
  icon?: ReactNode;
  /** The element the label renders as. A panel that is its own live region wants a span. */
  component?: "span" | "h3";
  /** Capitalises the label for display only, leaving the reported value untouched. */
  capitalize?: boolean;
}

/**
 * The glyph, the word and the count every overview panel opens with.
 *
 * <p><b>Why this exists at all.</b> The cluster verdict, the infrastructure card and the discovered
 * mesh each answer the same question in the same shape, and each drew its own - at three sizes,
 * with three colour scopes, in three components. Reuse over rebuild makes a bespoke second
 * implementation a defect rather than a variant, and this was the third. Panels configure this one
 * now; they do not redraw it.
 *
 * <p><b>The tone lands on the word, never on the panel.</b> The verdict used to set its colour on
 * the whole surface, so the count beneath it inherited the status colour. Status colours are held
 * to 3:1 rather than the 4.5:1 normal text asks for, by a deliberate and measured trade - and that
 * trade was made for the status word, not for the supporting line that happened to sit under it.
 *
 * <p><b>Capitalised for display only.</b> The reported value is passed through untouched, because
 * everything that branches on health compares the contract's own lowercase.
 *
 * <p>A state this console does not recognise draws neutrally and without a glyph: baselines are
 * versioned independently, so a console older than the gateway it reads is ordinary, and inventing
 * a glyph for a word it cannot interpret would assert a meaning it does not have.
 *
 * @param props the label, the state it reports, and how the panel wants it drawn.
 * @returns the rollup.
 */
export function PanelRollup({
  label,
  tone,
  count,
  size = "panel",
  icon,
  component = "span",
  capitalize = false,
}: PanelRollupProps) {
  const palettePath = toneForHealth(tone as ClusterHealth);
  const glyphSize = size === "primary" ? 30 : 20;

  return (
    <>
      <Typography
        component={component}
        // lineHeight 1 is what actually centres the glyph: a heading line box is taller than its
        // letters, so an icon centred against the box sits visibly high against the text.
        sx={{
          alignItems: "center",
          color: palettePath,
          display: "flex",
          gap: 1,
          lineHeight: 1,
          ...(capitalize ? { textTransform: "capitalize" } : {}),
        }}
        variant={size === "primary" ? "h4" : "h6"}
      >
        {icon ?? (DRAWN.has(tone) && <StatusIcon size={glyphSize} tone={tone as ClusterHealth} />)}
        {label}
      </Typography>

      {count && (
        <Typography component="p" sx={{ color: "text.secondary", m: 0, mt: 0.5 }} variant="body2">
          {count}
        </Typography>
      )}
    </>
  );
}
