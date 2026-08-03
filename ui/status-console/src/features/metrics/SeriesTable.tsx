import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import { memo } from "react";
import { FIGURE, FLUSH } from "../../shared/tableStyles.ts";
import type { MetricSample } from "./metrics.ts";
import { seriesKey } from "./metrics.ts";

/** What the table needs to render the full series list. */
export interface SeriesTableProps {
  /** The samples to show, already filtered by whatever the operator typed. */
  samples: readonly MetricSample[];
}

/** Renders a sample's labels as one readable string, sorted so the order never wanders. */
function labelText(sample: MetricSample): string {
  const labels = Object.entries(sample.labels ?? {})
    .filter(([key]) => key !== "service")
    .map(([key, value]) => `${key}=${value}`)
    .sort();
  return labels.length > 0 ? labels.join(", ") : "-";
}

/**
 * Every series the operation returned, as a table.
 *
 * <p>This is the half of the view the cards cannot serve: a question nobody anticipated. It is the
 * honest alternative to guessing which seventh metric matters, and it is why the cards can stay at
 * six.
 *
 * <p>Values carry tabular numerals so a column of them stays scannable, and the table is flush with
 * the panel edge like every other table in the console.
 *
 * @param props the samples to render.
 * @returns the table.
 */
export const SeriesTable = memo(function SeriesTable({ samples }: SeriesTableProps) {
  return (
    <Table size="small" sx={FLUSH}>
      <TableHead>
        <TableRow>
          <TableCell>Metric</TableCell>
          <TableCell>Service</TableCell>
          <TableCell>Labels</TableCell>
          <TableCell align="right">Value</TableCell>
        </TableRow>
      </TableHead>
      <TableBody>
        {samples.map((sample) => (
          <TableRow key={seriesKey(sample)}>
            <TableCell sx={{ fontFamily: "monospace" }}>{sample.name}</TableCell>
            <TableCell>{sample.labels?.service ?? "-"}</TableCell>
            <TableCell sx={{ color: "text.secondary" }}>{labelText(sample)}</TableCell>
            <TableCell align="right" sx={FIGURE}>
              {Number.isInteger(sample.value) ? sample.value : sample.value.toFixed(3)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </Table>
  );
});
