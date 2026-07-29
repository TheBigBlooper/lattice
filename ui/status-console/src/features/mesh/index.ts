/**
 * What the mesh feature offers the rest of the console.
 *
 * Only what genuinely crosses the boundary is listed. `TransitionIcon` and `activity.ts` are the
 * feature's own internals and stay unexported here on purpose - the `check:deadcode` gate reads
 * through this file, so re-exporting something nobody outside imports fails the build rather than
 * quietly widening the surface.
 */
export { ActivityLog } from "./ActivityLog.tsx";
export { ActivityToasts } from "./ActivityToasts.tsx";
export { DiscoveredBaselines } from "./DiscoveredBaselines.tsx";
export type { ActivityEntry } from "./useMeshActivity.ts";
export { useMeshActivity } from "./useMeshActivity.ts";
