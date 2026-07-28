import Box from "@mui/material/Box";
import Typography from "@mui/material/Typography";
import { StatusIcon } from "./StatusIcon.tsx";

/** One service's readiness, as this baseline's gateway reports it. */
export interface ServiceRowProps {
  /** The service name. */
  name: string;
  /** Its readiness, exactly as reported: `UP` or anything else. */
  status: string;
}

/**
 * One service on its own line: glyph, name, and state.
 *
 * <p><b>A row rather than a pill, because this list is about to grow.</b> Two services fit either
 * way; every service in the baseline does not. Wrapped pills become a cloud where nothing aligns and
 * the one that is down sits somewhere in the middle, so finding it means reading all of them. Rows
 * put every state in one right-hand column, which is where the eye goes for the only question this
 * card is asked: which one is wrong.
 *
 * <p>The state is a word as well as a colour and a shape. A status surface that distinguishes states
 * by hue alone is unreadable to a colour-blind operator.
 *
 * @param props the service and its readiness.
 * @returns the row.
 */
export function ServiceRow({ name, status }: ServiceRowProps) {
  const isUp = status === "UP";

  return (
    <Box
      component="li"
      sx={{
        alignItems: "center",
        borderColor: "divider",
        borderTop: 1,
        display: "flex",
        gap: 1,
        py: 0.75,
        "&:first-of-type": { borderTop: 0 },
      }}
    >
      {/* The glyph takes the state's colour, so one decision per state covers the whole row. */}
      <Box sx={{ color: isUp ? "success.main" : "error.main", display: "flex" }}>
        <StatusIcon size={16} tone={isUp ? "ready" : "down"} />
      </Box>
      <Typography variant="body2">{name}</Typography>
      <Typography
        sx={{
          color: isUp ? "text.secondary" : "error.main",
          letterSpacing: "0.04em",
          ml: "auto",
          textTransform: "uppercase",
        }}
        variant="caption"
      >
        {isUp ? "up" : "down"}
      </Typography>
    </Box>
  );
}
