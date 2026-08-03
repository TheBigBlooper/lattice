import DnsIcon from "@mui/icons-material/Dns";
import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Divider from "@mui/material/Divider";
import Tab from "@mui/material/Tab";
import Tabs from "@mui/material/Tabs";
import Toolbar from "@mui/material/Toolbar";
import { NavLink, useLocation } from "react-router";
import { ThemeMenu } from "../theme/ThemeMenu.tsx";
import type { ThemeChoice } from "../theme/useThemeChoice.ts";
import { BarSegment } from "./BarSegment.tsx";

/**
 * Where this console can go, in the order the bar shows them.
 *
 * <p>Status is first and is the default route: the console's first job is still answering whether
 * this baseline is healthy, and an operator arriving should not have to navigate to find that out.
 * The other two are about acting on this baseline - the peer redirect deliberately stays an action
 * on the peer's row rather than becoming a fourth destination, because it navigates away from this
 * console entirely and that is a strange thing for a tab to do.
 */
const DESTINATIONS = [
  { label: "Status", to: "/" },
  { label: "Orders", to: "/orders" },
  { label: "Inventory", to: "/inventory" },
  { label: "Metrics", to: "/metrics" },
] as const;

/**
 * Which destination a path belongs to.
 *
 * <p>Longest match rather than equality, so a future detail route under a tab keeps that tab
 * selected instead of quietly deselecting every one of them - which Material UI reports as an
 * out-of-range value rather than rendering nothing.
 */
function currentTab(pathname: string): string {
  const match = DESTINATIONS.filter((destination) => destination.to !== "/").find((destination) =>
    pathname.startsWith(destination.to)
  );
  return match?.to ?? "/";
}

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
  /** The strongest realm role they hold here, when there is one. */
  role?: string;
  /** Ends the session, when there is one to end. */
  onSignOut?: () => void;
  /** What theme the operator has asked for. */
  themeChoice: ThemeChoice;
  /** Records a new theme choice. */
  onThemeChoice: (choice: ThemeChoice) => void;
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
 * <p><b>User and role are separate facts, shown separately.</b> They coincide for the local demo
 * account and for nobody else: the role is read from the token realm grants, so a person called
 * anything at all is reported by name AND by what they may do here. Collapsing them would have put
 * somebody own name where their grant belongs.
 *
 * @param props the baseline's identity and the session, if any.
 * @returns the title bar.
 */
export function ConsoleAppBar({
  clusterId,
  region,
  version,
  username,
  role,
  onSignOut,
  themeChoice,
  onThemeChoice,
}: ConsoleAppBarProps) {
  const { pathname } = useLocation();
  const identity = [clusterId, region, version].filter(Boolean).join(" · ");
  const session = [username, role].filter(Boolean).join(" · ");

  return (
    <AppBar color="default" position="static">
      <Toolbar sx={{ gap: 2 }} variant="dense">
        <DnsIcon sx={{ color: "text.secondary", fontSize: 22 }} />

        {/* ONE SEGMENT, NOT THREE. Where this console is pointed is a single fact - the baseline,
            where it runs, and what it is running - and drawn as three labelled pairs it read as the
            first three items of a run that continued straight into the destinations. Joined, the
            left side is one object, which is what it actually is, and the tabs become the bar's
            second element rather than its fourth. Absent parts fall out of the join rather than
            leaving a separator with nothing after it. */}
        <BarSegment label="Baseline" primary value={identity} />

        {/* Offered only to a session. Every destination behind these is a bearer-protected read, so
            showing them signed out would advertise three routes that can answer nothing but 401 -
            the console teaching that its own controls sometimes just fail. */}
        {onSignOut && <Divider flexItem orientation="vertical" />}

        {onSignOut && (
          <Tabs sx={{ minHeight: 0 }} value={currentTab(pathname)}>
            {DESTINATIONS.map((destination) => (
              <Tab
                component={NavLink}
                end={destination.to === "/"}
                key={destination.to}
                label={destination.label}
                sx={{ minHeight: 0, py: 1.5 }}
                to={destination.to}
                value={destination.to}
              />
            ))}
          </Tabs>
        )}

        <Box sx={{ flexGrow: 1 }} />

        {/* Offered signed out as well as in. It is a preference about reading the screen rather than
            about the baseline, and the sign-in card is a screen too. */}
        <ThemeMenu choice={themeChoice} onChoose={onThemeChoice} />

        {onSignOut && (
          <>
            {/* Joined for the same reason as the baseline, and labelled for a different one: who is
                signed in and what they may do is one answer, and drawn at the identical weight as
                the left side it competed with the fact the page is actually about. */}
            <BarSegment label="Signed in" value={session} />
            <Button onClick={onSignOut} sx={{ ml: 1 }}>
              Sign out
            </Button>
          </>
        )}
      </Toolbar>
    </AppBar>
  );
}
