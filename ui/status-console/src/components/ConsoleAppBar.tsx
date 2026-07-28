import DnsIcon from "@mui/icons-material/Dns";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Toolbar from "@mui/material/Toolbar";
import { BarSegment } from "./BarSegment.tsx";

/** What the bar needs to identify this baseline and its operator. */
export interface ConsoleAppBarProps {
  /** The baseline being looked at. */
  clusterId: string;
  /** Where it runs, when known. */
  region?: string;
  /** The versioned baseline it runs, when known. */
  version?: string;
  /** The strongest realm role they hold here, when there is one. */
  role?: string;
  /** Ends the session, when there is one to end. */
  onSignOut?: () => void;
}

/**
 * The title bar: which baseline this is, what it runs, and who is looking at it.
 *
 * <p><b>Built from the same vocabulary as the status panels</b> - a micro-label in uppercase over
 * its value, segments separated by rules - so the bar reads as part of the page rather than a
 * different piece of design sitting on top of it.
 *
 * <p><b>The baseline alone, not the product name.</b> Repeating "Lattice" on every screen of a
 * console that only ever shows one product spends the most prominent position on the least useful
 * word. Which baseline you are looking at is what an operator working across several actually needs,
 * and a cluster glyph rather than the product mark says the same thing again.
 *
 * <p><b>The role comes from the token, not from the username.</b> They coincide for the local demo
 * account, which is exactly the trap: showing the username under a Role label would report a
 * person's own name where their grant belongs, for anybody not called {@code operator}.
 *
 * @param props the baseline's identity and the session, if any.
 * @returns the title bar.
 */
export function ConsoleAppBar({ clusterId, region, version, role, onSignOut }: ConsoleAppBarProps) {
  return (
    <AppBar color="default" position="static">
      <Toolbar sx={{ gap: 1.75 }} variant="dense">
        <DnsIcon sx={{ color: "text.secondary", fontSize: 22 }} />

        <BarSegment label="Baseline" primary value={clusterId} />
        {region && <Divider flexItem orientation="vertical" />}
        <BarSegment label="Region" value={region} />
        {version && <Divider flexItem orientation="vertical" />}
        <BarSegment label="Version" value={version} />

        <Box sx={{ flexGrow: 1 }} />

        {onSignOut && (
          <>
            <BarSegment label="Role" value={role} />
            <Button onClick={onSignOut} sx={{ ml: 1 }}>
              Sign out
            </Button>
          </>
        )}
      </Toolbar>
    </AppBar>
  );
}
