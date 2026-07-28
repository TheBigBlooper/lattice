import DnsIcon from "@mui/icons-material/Dns";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import { BaselineChip } from "./BaselineChip.tsx";

/** What the bar needs to identify this baseline and its operator. */
export interface ConsoleAppBarProps {
  /** The baseline being looked at. */
  clusterId: string;
  /** Where it runs, when known. */
  region?: string;
  /** The versioned baseline it runs, when known. */
  version?: string;
  /** Who holds the session, when there is one. */
  username?: string;
  /** Ends the session, when there is one to end. */
  onSignOut?: () => void;
}

/**
 * The title bar: which baseline this is, what it runs, and who is looking at it.
 *
 * <p><b>The baseline alone, not the product name.</b> Repeating "Lattice" on every screen of a
 * console that only ever shows one product spends the most prominent position on the least useful
 * word. Which baseline you are looking at is what an operator working across several actually needs
 * from a title bar - and a cluster glyph rather than the product mark says the same thing again.
 *
 * <p>It lives in its own file because the shell's job is choosing <em>which</em> screen to show;
 * drawing the frame around them there made that choice harder to read than the screens it chose
 * between.
 *
 * @param props the baseline's identity and the session, if any.
 * @returns the title bar.
 */
export function ConsoleAppBar({
  clusterId,
  region,
  version,
  username,
  onSignOut,
}: ConsoleAppBarProps) {
  return (
    <AppBar color="default" position="static">
      <Toolbar variant="dense">
        <DnsIcon sx={{ color: "text.secondary", fontSize: 22, mr: 1.5 }} />
        <Typography component="h1" variant="h6">
          {clusterId}
        </Typography>

        {/*
          Which baseline this cluster runs, beside which cluster it is. The signed-out card carries
          the region and the version, and both used to vanish the moment an operator signed in -
          exactly when they start working across baselines and need to know which one answered.
        */}
        <BaselineChip region={region} version={version} />

        <Box sx={{ flexGrow: 1 }} />

        {onSignOut && (
          <Box sx={{ alignItems: "center", display: "flex", gap: 1 }}>
            <Typography sx={{ color: "text.secondary" }} variant="body2">
              {username}
            </Typography>
            <Button onClick={onSignOut}>Sign out</Button>
          </Box>
        )}
      </Toolbar>
    </AppBar>
  );
}
