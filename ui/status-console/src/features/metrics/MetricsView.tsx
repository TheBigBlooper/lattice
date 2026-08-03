import ClearIcon from "@mui/icons-material/Clear";
import ExpandLessIcon from "@mui/icons-material/ExpandLess";
import ExpandMoreIcon from "@mui/icons-material/ExpandMore";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Collapse from "@mui/material/Collapse";
import Grid from "@mui/material/Grid";
import IconButton from "@mui/material/IconButton";
import InputAdornment from "@mui/material/InputAdornment";
import Paper from "@mui/material/Paper";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useMemo, useState } from "react";
import { PanelHeader } from "../../shared/PanelHeader.tsx";
import { PanelHelp } from "../../shared/PanelHelp.tsx";
import { PANEL_HELP } from "../../shared/panelHelpContent.ts";
import { CARDS, type CardReading } from "./cards.ts";
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

/** What the full-series panel is called, used as its header and as its help title. */
const EVERY_MEASUREMENT = "Every measurement";

/**
 * The readings behind one card's trend.
 *
 * <p>Most cards name a single series. The datastore mean is two series divided element by element,
 * so it builds its own.
 *
 * @param reading the card's current reading.
 * @param history the recorded readings per series.
 * @returns the readings to draw, oldest first.
 */
function trendFor(
  reading: CardReading,
  history: ReadonlyMap<string, readonly number[]>
): readonly number[] {
  if (reading.trendFrom) {
    return reading.trendFrom(history);
  }
  return reading.trendKey ? (history.get(reading.trendKey) ?? []) : [];
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
  const [showAll, setShowAll] = useState(false);

  const isStale = Boolean(error) && samples.length === 0;

  const filtered = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!needle) {
      return samples;
    }
    return samples.filter((sample) => seriesKey(sample).toLowerCase().includes(needle));
  }, [filter, samples]);

  return (
    // Scrolls its own contents rather than the page. The shell hands each view the viewport it
    // fills, so a view taller than that clipped at the fold instead of scrolling - the measurement
    // panel sits below six cards and was the part that disappeared.
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 2,
        height: { md: "100%" },
        minHeight: 0,
        overflowY: "auto",
        pr: { md: 1 },
      }}
    >
      <Grid container spacing={2}>
        {CARDS.map((card) => {
          const reading = card.read(samples, history);
          return (
            <Grid key={card.id} size={{ sm: 6, xs: 12 }}>
              <MetricCard
                card={card}
                isStale={isStale}
                reading={reading}
                readings={trendFor(reading, history)}
              />
            </Grid>
          );
        })}
      </Grid>

      <Paper sx={{ p: 2 }} variant="outlined">
        <PanelHeader
          help={
            <>
              {/* The control comes first and the help icon last, so the icon stays pinned to the
                  panel's edge. With it leading, the button changing between "Show 12" and "Hide"
                  moved the icon sideways on every toggle. */}
              <Button
                onClick={() => setShowAll((shown) => !shown)}
                size="small"
                startIcon={showAll ? <ExpandLessIcon /> : <ExpandMoreIcon />}
              >
                {showAll ? "Hide" : `Show ${samples.length}`}
              </Button>
              <PanelHelp content={PANEL_HELP.metrics} label={EVERY_MEASUREMENT} />
            </>
          }
          label={EVERY_MEASUREMENT}
        />
        <Collapse in={showAll}>
          <TextField
            fullWidth
            label="Filter by name or label"
            onChange={(event) => setFilter(event.target.value)}
            size="small"
            slotProps={{
              input: {
                endAdornment: filter ? (
                  <InputAdornment position="end">
                    <IconButton
                      aria-label="Clear the filter"
                      edge="end"
                      onClick={() => setFilter("")}
                      size="small"
                    >
                      <ClearIcon fontSize="small" />
                    </IconButton>
                  </InputAdornment>
                ) : null,
              },
            }}
            sx={{ mb: 2 }}
            value={filter}
          />
          {/* The table scrolls its own overflow rather than the page: a baseline with several peers
              reports a series per peer, so this grows with the mesh. The right padding is what keeps
              the scrollbar off the value column, which it otherwise sits on top of. */}
          <Box sx={{ maxHeight: 360, overflowY: "auto", pr: 2 }}>
            <SeriesTable samples={filtered} />
          </Box>
          {isLoading || filtered.length > 0 ? null : (
            <Typography color="text.secondary" sx={{ pt: 1 }} variant="body2">
              Nothing matches that filter.
            </Typography>
          )}
        </Collapse>
      </Paper>
    </Box>
  );
}
