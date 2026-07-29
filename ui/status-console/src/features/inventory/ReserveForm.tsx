import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import Paper from "@mui/material/Paper";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";
import type { ApiError } from "../../api/client.ts";
import type { CreateReservationRequest, Reservation } from "../../api/useInventory.ts";
import { PanelHeader, ViewerNotice } from "../../shared/index.ts";

/** What the form needs to submit, and to know whether it may. */
export interface ReserveFormProps {
  /** Places the hold. */
  onReserve: (reservation: CreateReservationRequest) => void;
  /** Whether a reservation is in flight. */
  isReserving: boolean;
  /** Why the last attempt failed, when it did. */
  error?: ApiError | null;
  /** What the last successful attempt produced, rendered in place. */
  created?: Reservation | undefined;
  /** Whether this operator may write here. */
  canWrite: boolean;
  /** This baseline, named because a grant is held per baseline. */
  baseline: string;
}

/** The issue the service raised against one field, matched on the name the service sent. */
function issueFor(error: ApiError | null | undefined, field: string): string | undefined {
  return error?.details.find((detail) => detail.field === field)?.issue;
}

/**
 * Holds stock against an order line.
 *
 * <p><b>Not confirmed, deliberately.</b> A reservation is idempotent by `(orderId, sku)`, so
 * repeating it is safe by construction - only `setStock` is gated, because it is the only write
 * that destroys what it replaces.
 *
 * <p>Errors split the same way as everywhere else: a validation failure against the field the
 * service named, a conflict as a banner carrying the server's wording. Insufficient stock is the
 * conflict this form produces, and it is about the world rather than the input.
 *
 * @param props the submit action, its state, and whether this operator may use it.
 * @returns the reserve panel.
 */
export function ReserveForm({
  onReserve,
  isReserving,
  error,
  created,
  canWrite,
  baseline,
}: ReserveFormProps) {
  const [orderId, setOrderId] = useState("");
  const [sku, setSku] = useState("");
  const [quantity, setQuantity] = useState("");
  const [dismissedError, setDismissedError] = useState<string | undefined>(undefined);

  // The same fallback the orders form has, and for the same reason: the services report every
  // validation problem against the field `body` rather than naming the offending one, so nothing
  // matches an input and the message would vanish entirely. Submitting this form empty was exactly
  // that case - a refusal with no explanation anywhere on the screen.
  const placed = error?.details.some((detail) =>
    ["orderId", "sku", "quantity"].includes(detail.field)
  );
  const raw = error && !(error.code === "VALIDATION_ERROR" && placed) ? error : undefined;
  // Dismissible but never self-dismissing - see the orders form for why.
  const banner = raw?.message === dismissedError ? undefined : raw;

  return (
    <Paper sx={{ p: 2 }}>
      <PanelHeader caption={`held on ${baseline}`} label="Reserve stock" />

      <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
        {!canWrite && <ViewerNotice action="Setting stock and reserving" baseline={baseline} />}

        {banner && (
          <Alert
            onClose={() => setDismissedError(banner.message)}
            severity={banner.code === "CONFLICT" ? "warning" : "error"}
          >
            {banner.message}
          </Alert>
        )}

        {created && (
          <Alert icon={false} severity="success">
            <Typography sx={{ fontWeight: 600 }} variant="body2">
              Reserved {created.quantity} of {created.sku}
            </Typography>
            <Typography sx={{ color: "text.secondary" }} variant="caption">
              {created.reservationId} · against {created.orderId}
            </Typography>
          </Alert>
        )}

        <Box sx={{ display: "flex", flexWrap: "wrap", gap: 1 }}>
          <TextField
            disabled={!canWrite}
            error={Boolean(issueFor(error, "orderId"))}
            helperText={issueFor(error, "orderId")}
            label="Order"
            onChange={(event) => setOrderId(event.target.value)}
            size="small"
            value={orderId}
          />
          <TextField
            disabled={!canWrite}
            error={Boolean(issueFor(error, "sku"))}
            helperText={issueFor(error, "sku")}
            label="Sku"
            onChange={(event) => setSku(event.target.value)}
            size="small"
            value={sku}
          />
          <TextField
            disabled={!canWrite}
            error={Boolean(issueFor(error, "quantity"))}
            helperText={issueFor(error, "quantity")}
            label="Quantity"
            onChange={(event) => setQuantity(event.target.value)}
            size="small"
            sx={{ width: 120 }}
            value={quantity}
          />
        </Box>

        <Box sx={{ alignItems: "center", display: "flex", flexWrap: "wrap", gap: 2 }}>
          <Button
            disabled={!canWrite || isReserving}
            onClick={() => onReserve({ orderId, quantity: Number(quantity), sku })}
            variant="contained"
          >
            Reserve
          </Button>
        </Box>
      </Box>
    </Paper>
  );
}
