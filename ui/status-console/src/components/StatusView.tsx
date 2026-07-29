import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Typography from "@mui/material/Typography";
import type { ApiError } from "../api/client.ts";
import type { components } from "../api/generated/v1.ts";
import type { Peer } from "../api/usePeers.ts";
import type { ActivityEntry } from "../mesh/useMeshActivity.ts";
import { ActivityLog } from "./ActivityLog.tsx";
import { ClusterVerdict } from "./ClusterVerdict.tsx";
import { DiscoveredBaselines } from "./DiscoveredBaselines.tsx";
import { InfrastructureCard } from "./InfrastructureCard.tsx";

/** What the status view renders. */
export interface StatusViewProps {
  /** This baseline as its own gateway reports it. */
  baseline: components["schemas"]["Baseline"];
  /** The peers it has discovered. */
  peers: Peer[];
  /** Why the registry could not be read, when it could not. */
  peersError?: ApiError | null;
  /** What has changed since this page was opened, newest first. */
  activity: ActivityEntry[];
}

/**
 * The signed-in dashboard: this baseline on the left, everyone else on the right.
 *
 * <p><b>The split means something.</b> The left rail carries what this cluster <em>is</em> and then
 * what happened <em>to</em> it; the right column is about the other baselines. That also gives the
 * left column a reason to be full height - it used to stretch to match the mesh panel and pad out
 * the difference with nothing.
 *
 * <p><b>Wrapping rather than shrinking is deliberate.</b> At every width the cluster's own verdict
 * stays first in reading order, which is the one thing this layout must never trade away. The mesh
 * keeps the wider column because its table is the densest thing on the screen and the least able to
 * give up horizontal room.
 *
 * <p>It lives here rather than in the shell because the shell's job is choosing <em>which</em> screen
 * to show - signed out, refused, unreachable, loading, or this one. Drawing this one there made that
 * choice harder to read than the screens it was choosing between.
 *
 * @param props the baseline, its peers, and the activity so far.
 * @returns the dashboard.
 */
export function StatusView({ baseline, peers, peersError, activity }: StatusViewProps) {
  return (
    <Box
      sx={{
        alignItems: "stretch",
        display: "flex",
        flexWrap: "wrap",
        gap: 2,
        // Fills the frame the shell holds, so the panels scroll rather than the page.
        height: { md: "100%" },
        minHeight: 0,
      }}
    >
      <Box
        sx={{
          display: "flex",
          flex: "1 1 300px",
          flexDirection: "column",
          gap: 2,
          minHeight: 0,
          minWidth: 0,
        }}
      >
        {/* An even split, and each panel scrolls its own contents. Sizing them to their content
            left the verdict short and the activity panel holding a screen of nothing; halves keep
            the rail balanced whether a baseline runs two services or a dozen. */}
        <Box sx={{ flex: 1, minHeight: 0 }}>
          <ClusterVerdict health={baseline.health ?? "down"} services={baseline.services ?? []} />
        </Box>
        {/* Sized to its contents and placed without a wrapper, so a baseline that configures no
            infrastructure leaves no gap where a card would have been: the panel renders nothing at
            all rather than an empty frame, and the two panels either side keep the rail. */}
        <InfrastructureCard components={baseline.infrastructure ?? []} />
        <Box sx={{ flex: 1, minHeight: 0 }}>
          <ActivityLog entries={activity} />
        </Box>
      </Box>

      <Box sx={{ display: "flex", flex: "2 1 480px", minHeight: 0, minWidth: 0 }}>
        {/* The panel keeps its frame and the table scrolls inside it, so a mesh of a dozen
            baselines never pushes the verdict off the screen. */}
        <Paper sx={{ overflow: "auto", p: 2, width: "100%" }}>
          {peersError ? (
            <Typography sx={{ color: "warning.main" }} variant="body2">
              Cannot read the mesh registry: {peersError.message}
            </Typography>
          ) : (
            // The link state comes from this baseline's own gateway, never from the mesh: a report
            // about a broken link cannot travel over that link.
            <DiscoveredBaselines meshLink={baseline.meshLink} peers={peers} />
          )}
        </Paper>
      </Box>
    </Box>
  );
}
