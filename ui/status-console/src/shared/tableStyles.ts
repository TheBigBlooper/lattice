import type { SxProps, Theme } from "@mui/material/styles";

/**
 * Pulls a table's edge cells flush with the panel that holds it.
 *
 * <p>A Material table indents its content inside a panel that already has padding, so the first
 * column starts two paddings in from the panel's edge while everything else on the screen starts at
 * one. On the overview that put the rail's rows and the peer table at different left edges - a
 * boundary the eye crosses that carries no meaning.
 *
 * <p>It lives here rather than in the panel that first needed it because the second and third tables
 * needed exactly the same thing, and three copies of a spacing rule is how the console ends up with
 * three answers to one question.
 */
export const FLUSH: SxProps<Theme> = {
  "& td:first-of-type, & th:first-of-type": { pl: 0 },
  "& td:last-of-type, & th:last-of-type": { pr: 0 },
};

/**
 * A column of figures: lining numerals so digits stack in place down the column.
 *
 * <p>Anything an operator compares vertically - a count, a quantity, a time - gets this. Without it
 * proportional digits make 1280 narrower than 1000, and a column of stock levels stops being
 * scannable at exactly the size where scanning it is the point.
 */
export const FIGURE: SxProps<Theme> = { fontVariantNumeric: "tabular-nums" };
