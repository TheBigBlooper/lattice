import CloseIcon from "@mui/icons-material/Close";
import Alert from "@mui/material/Alert";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import IconButton from "@mui/material/IconButton";
import Paper from "@mui/material/Paper";
import TextField from "@mui/material/TextField";
import Typography from "@mui/material/Typography";
import { useState } from "react";
import type { ApiError } from "../../api/client.ts";
import type { CreateOrderRequest, Order } from "../../api/useOrders.ts";
import { PanelHeader } from "../../shared/index.ts";

/** What the form needs to submit, and to know whether it may. */
export interface NewOrderFormProps {
  /** Places the order. */
  onCreate: (order: CreateOrderRequest) => void;
  /** Whether a create is in flight. */
  isCreating: boolean;
  /** Why the last attempt failed, when it did. */
  error?: ApiError | null;
  /** What the last successful attempt produced, rendered in place. */
  created?: Order | undefined;
  /** Whether this operator may write here. A viewer sees the form, disabled. */
  canWrite: boolean;
  /** The baseline the grant would have to be held on, named because grants are per baseline. */
  baseline: string;
}

/** One line being composed. Kept as text so a half-typed quantity is not coerced to a number. */
interface DraftLine {
  /**
   * A stable identity for React, which the position cannot supply.
   *
   * <p>A line has no id until the order exists, and lines can be removed from the middle - so
   * keying by index would have React reuse a row for a different line the moment one is dropped.
   * The service still names its problems BY POSITION (`lines[0].quantity`), and that matching is
   * done from the map index rather than from this, so the two do not have to agree.
   */
  id: number;
  sku: string;
  quantity: string;
}

/** Hands out draft-line identities. Module-scoped, since uniqueness within a form is all it needs. */
let nextLineId = 0;

/** A fresh empty line. A form starts with one, since an order with no lines cannot be submitted. */
function emptyLine(): DraftLine {
  nextLineId += 1;
  return { id: nextLineId, quantity: "", sku: "" };
}

/**
 * The issue the service raised against one field, if it raised one.
 *
 * <p>Matched on the field name the service sent rather than a mapping kept here. The service is the
 * one that knows which field is wrong, and a console-side table of field names would drift from the
 * contract the first time a constraint moved.
 */
function issueFor(error: ApiError | null | undefined, field: string): string | undefined {
  return error?.details.find((detail) => detail.field === field)?.issue;
}

/**
 * Places an order on this baseline.
 *
 * <p><b>It sits above the list rather than beside it.</b> The confirmed direction: the table keeps
 * its full width, and what a create returns has an obvious home between the form and the list -
 * which a dialog cannot give, because it closes over exactly that moment.
 *
 * <p><b>A viewer sees it, disabled, and is told which grant is missing and where.</b> Realm
 * membership is deliberately unsynchronised, so the same person may hold `operator` on another
 * baseline; hiding the controls would leave them unable to tell a missing capability from a missing
 * grant.
 *
 * <p><b>Errors go where the operator can act on them.</b> A validation failure is about their input
 * and renders against the field the service named; a conflict or an outage is about the world and
 * renders as a banner, because no amount of editing the form changes it.
 *
 * @param props the submit action, its state, and whether this operator may use it.
 * @returns the create panel.
 */
export function NewOrderForm({
  onCreate,
  isCreating,
  error,
  created,
  canWrite,
  baseline,
}: NewOrderFormProps) {
  const [customerId, setCustomerId] = useState("");
  const [lines, setLines] = useState<DraftLine[]>(() => [emptyLine()]);

  const updateLine = (index: number, patch: Partial<DraftLine>) => {
    setLines((held) => held.map((line, at) => (at === index ? { ...line, ...patch } : line)));
  };

  const submit = () => {
    onCreate({
      customerId,
      lines: lines.map((line) => ({ sku: line.sku, quantity: Number(line.quantity) })),
    });
  };

  // A validation failure names its field, so it is already shown against that input. Everything
  // else is about the world rather than the input and belongs in the banner.
  const banner = error && error.code !== "VALIDATION_ERROR" ? error : undefined;

  return (
    <Paper sx={{ p: 2 }}>
      <PanelHeader caption={`placed on ${baseline}`} label="New order" />

      <Box sx={{ display: "flex", flexDirection: "column", gap: 2, mt: 1 }}>
        {banner && (
          <Alert severity={banner.code === "CONFLICT" ? "warning" : "error"}>
            {banner.message}
          </Alert>
        )}

        {created && (
          <Alert icon={false} severity="success">
            <Typography sx={{ fontWeight: 600 }} variant="body2">
              Order {created.orderId}
            </Typography>
            <Typography sx={{ color: "text.secondary" }} variant="caption">
              {created.customerId} · {created.status} · {created.lines.length} line
              {created.lines.length === 1 ? "" : "s"}
            </Typography>
          </Alert>
        )}

        <TextField
          disabled={!canWrite}
          error={Boolean(issueFor(error, "customerId"))}
          helperText={issueFor(error, "customerId")}
          label="Customer"
          onChange={(event) => setCustomerId(event.target.value)}
          size="small"
          sx={{ maxWidth: 320 }}
          value={customerId}
        />

        <Box sx={{ display: "flex", flexDirection: "column", gap: 1 }}>
          {lines.map((line, index) => (
            <Box
              // Keyed by identity, not position: a line removed from the middle would otherwise
              // have React reuse this row for a different line. The error matching below stays
              // positional, because that is how the service names its problems.
              key={line.id}
              sx={{ alignItems: "flex-start", display: "flex", gap: 1 }}
            >
              <TextField
                disabled={!canWrite}
                error={Boolean(issueFor(error, `lines[${index}].sku`))}
                helperText={issueFor(error, `lines[${index}].sku`)}
                label="Sku"
                onChange={(event) => updateLine(index, { sku: event.target.value })}
                size="small"
                value={line.sku}
              />
              <TextField
                disabled={!canWrite}
                error={Boolean(issueFor(error, `lines[${index}].quantity`))}
                helperText={issueFor(error, `lines[${index}].quantity`)}
                label="Quantity"
                onChange={(event) => updateLine(index, { quantity: event.target.value })}
                size="small"
                sx={{ width: 120 }}
                value={line.quantity}
              />
              {lines.length > 1 && (
                <IconButton
                  aria-label={`Remove line ${index + 1}`}
                  disabled={!canWrite}
                  onClick={() => setLines((held) => held.filter((_, at) => at !== index))}
                  size="small"
                >
                  <CloseIcon fontSize="small" />
                </IconButton>
              )}
            </Box>
          ))}
          <Button
            disabled={!canWrite}
            onClick={() => setLines((held) => [...held, emptyLine()])}
            sx={{ alignSelf: "flex-start" }}
          >
            Add line
          </Button>
        </Box>

        <Box sx={{ alignItems: "center", display: "flex", flexWrap: "wrap", gap: 2 }}>
          <Button disabled={!canWrite || isCreating} onClick={submit} variant="contained">
            Create order
          </Button>
          {/* The last three words carry it: the grant is per baseline, so an operator who holds it
              elsewhere needs to know it is THIS one they are missing. */}
          {!canWrite && (
            <Typography sx={{ color: "text.secondary" }} variant="caption">
              Creating an order needs the operator role on {baseline}.
            </Typography>
          )}
        </Box>
      </Box>
    </Paper>
  );
}
