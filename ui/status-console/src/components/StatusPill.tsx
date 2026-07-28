import Box from "@mui/material/Box";
import Chip from "@mui/material/Chip";
import type { components } from "../api/generated/v1.ts";
import { StatusIcon } from "./StatusIcon.tsx";

type ServiceHealth = components["schemas"]["ServiceHealth"];

/** What a pill needs to render one service's state. */
export interface StatusPillProps {
  /** The service's display name, as the cluster reports it. */
  name: ServiceHealth["name"];
  /** Whether the service's readiness probe answered. */
  status: ServiceHealth["status"];
}

/**
 * One service's state: its name, a glyph, and the state as a word.
 *
 * The word is not optional decoration beside the colour. An operator who cannot distinguish red
 * from green must still be able to read this console, so every state is carried by text and shape
 * as well as hue. That rule is why this component exists at all rather than each panel colouring
 * its own text.
 *
 * It renders as a list item so a group of pills is a list to assistive technology rather than a run
 * of unrelated text.
 *
 * @param props the service to render.
 * @returns a list item carrying the service and its state.
 */
export function StatusPill({ name, status }: StatusPillProps) {
  const isUp = status === "UP";
  return (
    <Chip
      component="li"
      icon={<StatusIcon size={16} tone={isUp ? "ready" : "down"} />}
      label={
        // Two nodes rather than one string: the name and the state are separate facts, and keeping
        // them separately addressable is what lets each be found on its own.
        <>
          <Box component="span" sx={{ color: "text.primary", mr: 0.5 }}>
            {name}
          </Box>
          <span>{isUp ? "up" : "down"}</span>
        </>
      }
      sx={{
        // The icon inherits this, which is what keeps one colour decision per state rather than one
        // per element inside the pill.
        color: isUp ? "success.main" : "error.main",
        // Material sizes a chip for a bare word; this one holds a name, a state and a glyph, so it
        // needs the room its contents actually ask for rather than the default for a tag.
        height: 28,
        "& .MuiChip-icon": { color: "inherit", ml: 1 },
        "& .MuiChip-label": { px: 1.25 },
      }}
      variant="outlined"
    />
  );
}
