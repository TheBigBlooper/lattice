import Button from "@mui/material/Button";
import Dialog from "@mui/material/Dialog";
import DialogActions from "@mui/material/DialogActions";
import DialogContent from "@mui/material/DialogContent";
import DialogTitle from "@mui/material/DialogTitle";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";
import type { InventoryItem } from "../../api/useInventory.ts";

/** What the dialog needs to describe the write it is about to make. */
export interface SetStockDialogProps {
  /** The item being written, or undefined when the dialog is closed. */
  item?: InventoryItem | undefined;
  /** Performs the write. */
  onConfirm: (onHand: number) => void;
  /** Abandons it. */
  onCancel: () => void;
}

/**
 * Confirms the one write that cannot be undone.
 *
 * <p><b>It is the only confirmation in the console, and that is what makes it work.</b> `setStock`
 * writes an ABSOLUTE value, so sending 10 to an item holding 500 destroys the 500, and the service
 * refuses only when the result would fall below what is already reserved - most mistakes are simply
 * accepted. Creating an order is additive and a reservation is idempotent by `(orderId, sku)`, so
 * neither is gated: confirming every write would make the dialog reflexive and stop it protecting
 * the case that mattered.
 *
 * <p>It states what the value replaces and what is already reserved, because those are the two
 * facts that decide whether the number being typed is a mistake.
 *
 * @param props the item, and what to do about it.
 * @returns the confirmation.
 */
export function SetStockDialog({ item, onConfirm, onCancel }: SetStockDialogProps) {
  const [onHand, setOnHand] = useState("");

  if (!item) {
    return null;
  }

  const requested = Number(onHand);
  const wouldStrand = onHand !== "" && requested < item.reserved;

  return (
    <Dialog onClose={onCancel} open>
      <DialogTitle>Set stock for {item.sku}?</DialogTitle>
      <DialogContent sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
        <TextField
          autoFocus
          label="On hand"
          onChange={(event) => setOnHand(event.target.value)}
          size="small"
          value={onHand}
        />
        <Typography sx={{ color: "text.secondary" }} variant="body2">
          This writes an absolute value, replacing {item.onHand}. It is not an adjustment.
        </Typography>
        {/* Said before the write rather than reported after it: the service refuses this case, and
            an operator who knows why in advance does not have to interpret a conflict. */}
        {wouldStrand && (
          <Typography sx={{ color: "warning.main" }} variant="body2">
            {item.reserved} are already reserved, so this will be refused.
          </Typography>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onCancel}>Cancel</Button>
        <Button
          color="error"
          disabled={onHand === "" || Number.isNaN(requested)}
          onClick={() => onConfirm(requested)}
          variant="contained"
        >
          Set stock
        </Button>
      </DialogActions>
    </Dialog>
  );
}
