import BrightnessAutoIcon from "@mui/icons-material/BrightnessAuto";
import DarkModeIcon from "@mui/icons-material/DarkMode";
import LightModeIcon from "@mui/icons-material/LightMode";
import IconButton from "@mui/material/IconButton";
import ListItemIcon from "@mui/material/ListItemIcon";
import ListItemText from "@mui/material/ListItemText";
import Menu from "@mui/material/Menu";
import MenuItem from "@mui/material/MenuItem";
import { useState } from "react";
import type { ThemeChoice } from "./useThemeChoice.ts";

/** What the control needs. */
export interface ThemeMenuProps {
  /** What the operator has asked for. */
  choice: ThemeChoice;
  /** Records a new choice. */
  onChoose: (choice: ThemeChoice) => void;
}

/** The three, in the order they are offered, with the words the menu says. */
const CHOICES = [
  { icon: BrightnessAutoIcon, label: "Follow this machine", value: "system" },
  { icon: LightModeIcon, label: "Light", value: "light" },
  { icon: DarkModeIcon, label: "Dark", value: "dark" },
] as const;

/**
 * The theme control in the app bar: an icon that opens System, Light and Dark.
 *
 * <p><b>A menu rather than a toggle, because there are three states and not two.</b> Keeping
 * `system` reachable is what stops the first click permanently retiring the console's habit of
 * following the machine - a behaviour chosen deliberately, which a two-state control would drop by
 * accident rather than by decision.
 *
 * <p><b>An icon rather than a labelled control.</b> The bar already carries the baseline, the
 * destinations and the session, and this is something an operator touches perhaps twice a year;
 * a permanently visible three-way control would spend width where there is least of it on the thing
 * used least.
 *
 * <p>The glyph shows what is in force rather than what is chosen - the automatic mark while
 * following, and the sun or moon once told - so the bar answers "what am I looking at" without being
 * opened. The accessible name carries the choice in words, since one glyph of three cannot.
 *
 * @param props the current choice and the handler for a new one.
 * @returns the control and its menu.
 */
export function ThemeMenu({ choice, onChoose }: ThemeMenuProps) {
  const [anchor, setAnchor] = useState<HTMLElement | undefined>(undefined);
  const active = CHOICES.find((option) => option.value === choice) ?? CHOICES[0];
  const Glyph = active.icon;

  return (
    <>
      <IconButton
        aria-label={`Theme: ${active.label.toLowerCase()}`}
        onClick={(event) => setAnchor(event.currentTarget)}
        size="small"
        sx={{ color: "text.secondary" }}
      >
        <Glyph sx={{ fontSize: 19 }} />
      </IconButton>

      <Menu anchorEl={anchor} onClose={() => setAnchor(undefined)} open={Boolean(anchor)}>
        {CHOICES.map((option) => {
          const OptionGlyph = option.icon;
          return (
            <MenuItem
              key={option.value}
              onClick={() => {
                onChoose(option.value);
                setAnchor(undefined);
              }}
              // A radio rather than a plain item: these are three states of one setting, and one of
              // them is always in force. A menu of plain items would say what can be done without
              // saying what is currently true.
              role="menuitemradio"
              aria-checked={option.value === choice}
              selected={option.value === choice}
            >
              <ListItemIcon>
                <OptionGlyph fontSize="small" />
              </ListItemIcon>
              <ListItemText>{option.label}</ListItemText>
            </MenuItem>
          );
        })}
      </Menu>
    </>
  );
}
