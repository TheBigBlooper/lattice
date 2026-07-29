import Box from "@mui/material/Box";
import Paper from "@mui/material/Paper";
import Table from "@mui/material/Table";
import TableBody from "@mui/material/TableBody";
import TableCell from "@mui/material/TableCell";
import TableHead from "@mui/material/TableHead";
import TableRow from "@mui/material/TableRow";
import Typography from "@mui/material/Typography";
import { useCreateOrder, useOrders } from "../../api/useOrders.ts";
import { PanelHeader } from "../../shared/index.ts";
import { NewOrderForm } from "./NewOrderForm.tsx";

/** What the view needs to read and write this baseline's orders. */
export interface OrdersViewProps {
  /** The API base for this baseline, from config. */
  baseUrl: string;
  /** The operator's bearer token. */
  token?: string | undefined;
  /** The strongest realm role they hold here. */
  role?: string | undefined;
  /** This baseline, named wherever a grant or a placement is described. */
  baseline: string;
}

/** The clock time an order was received, which is what an operator scanning a feed compares. */
function receivedAt(createdAt: string): string {
  return new Date(createdAt).toLocaleTimeString(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
  });
}

/**
 * This baseline's orders, and the form that adds one.
 *
 * <p><b>Form above the list, full width.</b> The confirmed direction. The table has five columns
 * and would lose a third of them to a side rail; more importantly the created order needs somewhere
 * to land, and between the form and the list is exactly where the operator is already looking.
 *
 * <p><b>It shows this baseline's orders only.</b> Under Shape A each baseline owns its own data, so
 * there is nothing here about a peer - reaching one stays an action on its row in the status view.
 *
 * @param props the API base, the session, and this baseline's name.
 * @returns the Orders screen.
 */
export function OrdersView({ baseUrl, token, role, baseline }: OrdersViewProps) {
  const orders = useOrders({ baseUrl, token });
  const create = useCreateOrder({ baseUrl, token });

  return (
    <Box sx={{ display: "flex", flexDirection: "column", gap: 2 }}>
      <NewOrderForm
        baseline={baseline}
        canWrite={role === "operator"}
        created={create.data}
        error={create.error}
        isCreating={create.isPending}
        onCreate={create.mutate}
      />

      <Paper sx={{ p: 2 }}>
        <PanelHeader caption="newest first" label="Orders" />

        {orders.error && (
          <Typography sx={{ color: "error.main", py: 1 }} variant="body2">
            {orders.error.message}
          </Typography>
        )}

        {orders.data?.length === 0 && (
          <Typography sx={{ color: "text.secondary", py: 1 }} variant="body2">
            No orders on this baseline yet.
          </Typography>
        )}

        {orders.data && orders.data.length > 0 && (
          // The table scrolls inside its own frame rather than widening the page: a console runs at
          // an unknown width and the body must never scroll sideways.
          <Box sx={{ overflowX: "auto" }}>
            <Table aria-label="orders">
              <TableHead>
                <TableRow>
                  <TableCell>Order</TableCell>
                  <TableCell>Customer</TableCell>
                  <TableCell>Status</TableCell>
                  <TableCell align="right">Lines</TableCell>
                  <TableCell>Received</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {orders.data.map((order) => (
                  <TableRow key={order.orderId}>
                    <TableCell>{order.orderId}</TableCell>
                    <TableCell>{order.customerId}</TableCell>
                    <TableCell>{order.status}</TableCell>
                    <TableCell align="right" sx={{ fontVariantNumeric: "tabular-nums" }}>
                      {order.lines.length}
                    </TableCell>
                    <TableCell sx={{ color: "text.secondary", fontVariantNumeric: "tabular-nums" }}>
                      {receivedAt(order.createdAt)}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Box>
        )}
      </Paper>
    </Box>
  );
}
