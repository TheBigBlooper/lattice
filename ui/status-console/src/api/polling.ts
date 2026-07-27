/**
 * How often the console re-reads the API.
 *
 * Polling is the settled live-status transport: neither Server-Sent Events nor a WebSocket earns
 * its keep here, because staleness is dominated by the mesh's peer time-to-live rather than by the
 * read interval, and a push transport would shorten only the smaller part of that budget.
 *
 * It lives in its own module because more than one hook polls. Were each to declare its own
 * interval, the two would drift apart the first time one was tuned, and the console would refresh
 * its own baseline and its view of the mesh at silently different rates.
 */
export const POLL_INTERVAL_MS = 10_000;
