import HelpOutlineIcon from "@mui/icons-material/HelpOutlineRounded";
import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import IconButton from "@mui/material/IconButton";
import Typography from "@mui/material/Typography";
import { useState } from "react";

/** What a panel says about itself. The same three answers on every panel. */
export interface PanelHelpContent {
  /** What the panel puts on screen. */
  shows: string;
  /** Where that reading comes from, in terms an operator can act on. */
  source: string;
  /**
   * What the panel deliberately leaves out, and why.
   *
   * <p>This is the section that earns the dialog. The console makes a great many considered
   * exclusions - the mesh view never contacts a peer, the announced verdict omits infrastructure,
   * the activity log is scoped to the session - and every one of them is recorded in a design
   * document an operator will never read. An exclusion nobody can discover in the product is
   * indistinguishable from a bug when it matters.
   */
  omits: string;
}

/** What the control needs. */
export interface PanelHelpProps {
  /** The panel this explains, named in the dialog and in the control's accessible name. */
  label: string;
  /** The three answers. */
  content: PanelHelpContent;
}

/**
 * Titles a panel name for the dialog that explains it.
 *
 * <p>The dialog is the one place a panel's name is a heading rather than a label, and a heading
 * reads as a title. The name itself is left alone everywhere else, so the panel header keeps the
 * quieter form it shares with every other header on the screen.
 *
 * @param label the panel name.
 * @returns the name with each word capitalised.
 */
function titleCase(label: string): string {
  return label.replace(/\b[a-z]/g, (letter) => letter.toUpperCase());
}

/**
 * The quiet control in a panel header that explains the panel.
 *
 * <p><b>One shape on every panel.</b> Three headings in the same order everywhere, so an operator
 * learns where the answer is once rather than reading each dialog as a fresh document. A panel that
 * has nothing to say under one of them does not get a shorter dialog - it gets a better sentence.
 *
 * <p><b>It is a dialog rather than a tooltip.</b> The content is three paragraphs and has to survive
 * being read slowly during an incident; a tooltip that vanishes on a mouse move is the wrong
 * container for anything worth writing down. It is also reachable by keyboard, which a hover
 * affordance is not.
 *
 * <p>The control sits at the end of the header and takes the secondary text colour, so it is
 * available without competing with the panel's own reading.
 *
 * @param props the panel's name and what it says about itself.
 * @returns the control, and the dialog it opens.
 */
export function PanelHelp({ label, content }: PanelHelpProps) {
  const [open, setOpen] = useState(false);
  const title = titleCase(label);

  return (
    <>
      <IconButton
        aria-label={`About ${title}`}
        onClick={() => setOpen(true)}
        size="small"
        sx={{ color: "text.secondary", ml: 0.5, p: 0.25 }}
      >
        <HelpOutlineIcon sx={{ fontSize: 16 }} />
      </IconButton>

      <Dialog aria-label={`About ${title}`} onClose={() => setOpen(false)} open={open}>
        <DialogTitle sx={{ pb: 0.5 }}>
          {title}
          <Typography
            component="span"
            sx={{
              color: "text.secondary",
              display: "block",
              letterSpacing: "0.07em",
              textTransform: "uppercase",
            }}
            variant="caption"
          >
            About this panel
          </Typography>
        </DialogTitle>

        <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
          <Section body={content.shows} heading="What it shows" />
          <Section body={content.source} heading="Where it comes from" />
          <Section body={content.omits} heading="What it deliberately does not show" />
        </DialogContent>

        <DialogActions>
          <Button onClick={() => setOpen(false)}>Close</Button>
        </DialogActions>
      </Dialog>
    </>
  );
}

/** One heading and its answer, so the three cannot be spaced or weighted differently. */
function Section({ heading, body }: { heading: string; body: string }) {
  return (
    <div>
      <Typography
        component="h3"
        sx={{
          color: "text.secondary",
          letterSpacing: "0.08em",
          textTransform: "uppercase",
        }}
        variant="caption"
      >
        {heading}
      </Typography>
      <Typography sx={{ mt: 0.5 }} variant="body2">
        {body}
      </Typography>
    </div>
  );
}
