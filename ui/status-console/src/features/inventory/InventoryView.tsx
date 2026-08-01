import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { useState } from "react";
import {
  type InventoryItem,
  useCreateReservation,
  useInventory,
  useSetStock,
} from "../../api/useInventory.ts";
import { FIGURE, FLUSH, ListPanel, PANEL_HELP } from "../../shared/index.ts";
import { ReserveForm } from "./ReserveForm.tsx";
import { SetStockDialog } from "./SetStockDialog.tsx";

/** What the view needs to read and write this baseline's stock. */
export interface InventoryViewProps {
  /** The API base for this baseline, from config. */
  baseUrl: string;
  /** The operator's bearer token. */
  token?: string | undefined;
  /** The strongest realm role they hold here. */
  role?: string | undefined;
  /** This baseline, named wherever a grant or a placement is described. */
  baseline: string;
}

/**
 * This baseline's stock, and the two writes against it.
 *
 * <p><b>Set stock is an action on the row</b> rather than a form asking which item it means: the
 * sku is already on screen, and a form that has to be told it invites the wrong one being typed
 * into the one write that cannot be undone.
 *
 * <p><b>Reserving is a panel</b>, because it needs an order the row cannot supply.
 *
 * @param props the API base, the session, and this baseline's name.
 * @returns the Inventory screen.
 */
export function InventoryView({ baseUrl, token, role, baseline }: InventoryViewProps) {
  const inventory = useInventory({ baseUrl, token });
  const setStock = useSetStock({ baseUrl, token });
  const reserve = useCreateReservation({ baseUrl, token });
  const [editing, setEditing] = useState<InventoryItem | undefined>(undefined);

  const canWrite = role === "operator";

  const confirm = (onHand: number) => {
    if (editing) {
      setStock.mutate({ onHand, sku: editing.sku });
    }
    setEditing(undefined);
  };

  return (
    // Fills the frame the shell holds, so the table scrolls inside its panel rather than leaving
    // the page short and the space below it empty.
    <Box
      sx={{
        display: "flex",
        flexDirection: "column",
        gap: 2,
        height: { md: "100%" },
        minHeight: 0,
      }}
    >
      <ReserveForm
        baseline={baseline}
        canWrite={canWrite}
        created={reserve.data}
        error={reserve.error}
        isReserving={reserve.isPending}
        onReserve={reserve.mutate}
      />

      {/* setStock reports its own failure here rather than through the panel: the panel's dialog is
          for a read that cannot be made, and a refused write leaves the list perfectly readable. */}
      {setStock.error && (
        <Typography sx={{ color: "error.main", py: 1 }} variant="body2">
          {setStock.error.message}
        </Typography>
      )}

      <ListPanel
        caption="by sku"
        count={inventory.data?.length}
        emptyMessage="No stock on this baseline yet."
        errorDetail={inventory.error?.message}
        help={PANEL_HELP.inventory}
        isRetrying={inventory.fetchStatus === "fetching"}
        label="Inventory"
        lastGoodRead={inventory.dataUpdatedAt || undefined}
      >
        <Table aria-label="inventory" sx={FLUSH}>
          <TableHead>
            <TableRow>
              <TableCell>Sku</TableCell>
              <TableCell align="right">On hand</TableCell>
              <TableCell align="right">Reserved</TableCell>
              <TableCell align="right">Available</TableCell>
              <TableCell />
            </TableRow>
          </TableHead>
          <TableBody>
            {inventory.data?.map((item) => (
              <TableRow key={item.sku}>
                <TableCell>{item.sku}</TableCell>
                <TableCell align="right" sx={FIGURE}>
                  {item.onHand}
                </TableCell>
                <TableCell align="right" sx={FIGURE}>
                  {item.reserved}
                </TableCell>
                <TableCell align="right" sx={FIGURE}>
                  {item.available}
                </TableCell>
                <TableCell align="right">
                  <Button
                    aria-label={`Update stock for ${item.sku}`}
                    disabled={!canWrite}
                    onClick={() => setEditing(item)}
                  >
                    Update stock
                  </Button>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </ListPanel>

      <SetStockDialog item={editing} onCancel={() => setEditing(undefined)} onConfirm={confirm} />
    </Box>
  );
}
