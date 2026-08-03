/**
 * The shared tier's public surface - the components more than one feature renders.
 *
 * A component earns a place here by being rendered by two or more features, or by being a primitive
 * the next feature will certainly need. Everything else stays inside the feature that uses it, so
 * this file does not become a second home for one feature's internals.
 */

export { ArrivalCard } from "./ArrivalCard.tsx";
// ConnectionLost is no longer re-exported: ListPanel is now its only caller and imports it
// directly, so a barrel entry would advertise a component no feature reaches for.
export { ago } from "./ConnectionLost.tsx";
export { ListPanel } from "./ListPanel.tsx";
export { CROSS_FADE, ENTER_DOWN, ENTER_RIGHT, EXIT_MS, EXIT_RIGHT } from "./motion.ts";
export { PanelHeader } from "./PanelHeader.tsx";
export { PanelHelp } from "./PanelHelp.tsx";
export { PanelRollup } from "./PanelRollup.tsx";
export { PANEL_HELP } from "./panelHelpContent.ts";
export { StatusIcon } from "./StatusIcon.tsx";
export { StatusRow } from "./StatusRow.tsx";
export { SCROLL_PANE } from "./scrollStyles.ts";
export { FIGURE, FLUSH } from "./tableStyles.ts";
export { ViewerNotice } from "./ViewerNotice.tsx";
