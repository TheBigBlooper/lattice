import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import Accordion from "@mui/material/Accordion";
import AccordionDetails from "@mui/material/AccordionDetails";
import AccordionSummary from "@mui/material/AccordionSummary";
import Box from "@mui/material/Box";
import Grid from "@mui/material/Grid";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useMemo, useState } from "react";
import { PanelHelp } from "../../shared/PanelHelp.tsx";
import { PANEL_HELP } from "../../shared/panelHelpContent.ts";
import { CARDS } from "./cards.ts";
import { MetricCard } from "./MetricCard.tsx";
import { seriesKey } from "./metrics.ts";
import { SeriesTable } from "./SeriesTable.tsx";
import { useMetrics } from "./useMetrics.ts";

/** What the view needs to read this baseline's instrumentation. */
export interface MetricsViewProps {
  /** The API bases of every service on this baseline, from config. */
  baseUrls: readonly string[];
  /** The operator's bearer token. */
  token?: string | undefined;
}

/**
 * This baseline's own instrumentation: six cards over the full series list.
 *
 * <p><b>Why cards carry a trend at all.</b> Most of these are counters, and a counter's
 * instantaneous value is close to meaningless - that announcements received reads 46 says nothing,
 * while its having stopped moving four polls ago says everything.
 *
 * <p><b>The history is this tab's own memory.</b> Nothing stores these numbers, so a reload starts
 * the trends empty. The cards say so rather than letting a reader assume the line covers more than
 * it does.
 *
 * <p><b>A failed read annotates rather than blocking.</b> The other operational views put a dialog
 * over a failed read, because behind it sit a form and a list that has become a memory. There is
 * nothing to act on here, and an incident is exactly when these numbers matter, so the cards keep
 * their last values and say they are stale.
 *
 * @param props the API bases and the operator's token.
 * @returns the view.
 */
export function MetricsView({ baseUrls, token }: MetricsViewProps) {
  const { error, history, isLoading, samples } = useMetrics({ baseUrls, token });
  const [filter, setFilter] = useState("");

  const isStale = Boolean(error) && samples.length === 0;

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return samples;
    }
    return samples.filter((sample) => seriesKey(sample).toLowerCase().includes(needle));
  }, [filter, samples]);

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2, minHeight: 0 }}>
      <Grid container spacing={2}>
        {CARDS.map((card) => {
          const reading = card.read(samples, history);
          return (
            <Grid key={card.id} size={{ sm: 6, xs: 12 }}>
              <MetricCard
                card={card}
                isStale={isStale}
                reading={reading}
                readings={reading.trendKey ? (history.get(reading.trendKey) ?? []) : []}
              />
            </Grid>
          );
        })}
      </Grid>

      <Accordion disableGutters variant="outlined">
        <AccordionSummary expandIcon={<ExpandMoreIcon />}>
          <Typography sx={{ mr: 1 }}>All series</Typography>
          <Typography color="text.disabled">{samples.length}</Typography>
          <Box
            onClick={(event) => event.stopPropagation()}
            onKeyDown={(event) => event.stopPropagation()}
            sx={{ ml: "auto" }}
          >
            <PanelHelp content={PANEL_HELP.metrics} label="All series" />
          </Box>
        </AccordionSummary>
        <AccordionDetails>
          <TextField
            fullWidth
            label="Filter by name or label"
            onChange={(event) => setFilter(event.target.value)}
            size="small"
            sx={{ mb: 2 }}
            value={filter}
          />
          {/* The table scrolls its own overflow rather than the page: a baseline with several peers
              reports a series per peer, so this grows with the mesh. */}
          <Box sx={{ maxHeight: 360, overflowY: "auto" }}>
            <SeriesTable samples={filtered} />
          </Box>
          {isLoading || filtered.length > 0 ? null : (
            <Typography color="text.secondary" variant="body2">
              Nothing matches that filter.
            </Typography>
          )}
        </AccordionDetails>
      </Accordion>
    </Box>
  );
}
