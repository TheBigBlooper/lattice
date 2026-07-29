/**
 * What the activity feature offers the rest of the console.
 *
 * It is its own feature rather than part of mesh because it reports on **both** halves of the
 * screen: this baseline's own services and infrastructure, and the peers it discovered. Owning it
 * from mesh would have meant the mesh feature deciding how a local Elasticsearch is announced.
 *
 * `TransitionIcon` and the snapshot diff are internals - the panel and the toast host are what the
 * shell mounts, and the hook is what feeds them.
 */
export { ActivityLog } from "./ActivityLog.tsx";
export { ActivityToasts } from "./ActivityToasts.tsx";
export type { ActivityEntry } from "./useActivity.ts";
export { useActivity } from "./useActivity.ts";
