import { createTheme, type Theme } from "@mui/material/styles";

/**
 * The console's Material UI theme, and the only place in this codebase where a colour is written.
 *
 * Every value here is settled in `docs/design/ui/material_ui.md`; this module is that document
 * expressed as code, not a second source of truth. The `check:tokens` gate enforces the boundary: a
 * colour literal anywhere else in `src` fails the build, and this file is its single documented
 * exception.
 *
 * **The palette is Material UI's own.** `ready`, `degraded` and `down` map onto `success`, `warning`
 * and `error`, which the library supplies per mode, so nothing is overridden and nothing has to be
 * maintained. One measured consequence is recorded rather than hidden: in light mode
 * `warning.main` (#ed6c02) sits at 3.11:1 against the page, below the 4.5:1 WCAG AA asks of normal
 * text. That is a deliberate trade, and it is bounded because no state is carried by colour alone -
 * every one renders its colour, its glyph, and its word, and the word is what an operator who
 * cannot resolve the hue actually reads. Dark mode measures 9.64:1.
 *
 * **Two themes rather than CSS variables.** Material UI can drive light and dark entirely through
 * CSS custom properties, which is elegant and needs no JavaScript. It is not used here because it
 * would render every colour as `var(--mui-palette-...)`, so nothing - a test or a person reading
 * computed styles - could tell which colour a component actually resolved to. Two themes selected
 * by the operator's system preference keeps that observable.
 */

/** The font stack. */
const FONT_FAMILY = [
  "ui-sans-serif",
  "system-ui",
  "-apple-system",
  "Segoe UI",
  "Roboto",
  "Helvetica",
  "Arial",
  "sans-serif",
].join(",");

/**
 * Shared shape for both modes: the density and surface treatment the confirmed direction calls for.
 *
 * Outlined rather than elevated is a deliberate choice for a console read in both themes. Material's
 * elevation is a shadow, and a shadow against a near-black background is close to invisible, so an
 * elevated card in dark mode floats with no edge at all. An outline is legible in both.
 */
function baseOptions() {
  return {
    typography: { fontFamily: FONT_FAMILY },
    components: {
      MuiPaper: { defaultProps: { variant: "outlined" as const } },
      MuiCard: { defaultProps: { variant: "outlined" as const } },
      MuiChip: { defaultProps: { size: "small" as const } },
      MuiTable: { defaultProps: { size: "small" as const } },
      MuiButton: { defaultProps: { size: "small" as const } },
      MuiAppBar: { defaultProps: { elevation: 0 } },
    },
  };
}

/** The light theme. */
export const lightTheme: Theme = createTheme({ ...baseOptions(), palette: { mode: "light" } });

/** The dark theme. */
export const darkTheme: Theme = createTheme({ ...baseOptions(), palette: { mode: "dark" } });
