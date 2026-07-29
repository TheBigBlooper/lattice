import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { type ApiError, readEnvelope } from "./client.ts";
import type { components } from "./generated/v1.ts";

/** One stock item, exactly as the contract defines it. `available` is computed by the service. */
export type InventoryItem = components["schemas"]["InventoryItem"];

/** A hold against an order line, exactly as the contract defines it. */
export type Reservation = components["schemas"]["Reservation"];

/** What creating a reservation needs, exactly as the contract defines it. */
export type CreateReservationRequest = components["schemas"]["CreateReservationRequest"];

/** How many items one page holds. Matches the orders page, so paging feels the same on both. */
export const INVENTORY_PAGE_SIZE = 20;

/** What the hooks need to reach this baseline. */
export interface InventoryOptions {
  /** The API base for this baseline, from config. */
  baseUrl: string;
  /** The operator's bearer token. Absent means signed out, and nothing is fetched. */
  token?: string | undefined;
  /** The zero-based page to read. */
  page?: number;
}

/** What setting stock needs: which item, and the absolute value to write. */
export interface SetStockCommand {
  /** The item being written. */
  sku: string;
  /** The new absolute on-hand count. Not an adjustment. */
  onHand: number;
}

/** The query key every hook here agrees on, so a write invalidates exactly what the read filled. */
function inventoryKey(baseUrl: string, token: string | undefined) {
  return ["inventory", baseUrl, token] as const;
}

/**
 * Reads a page of this baseline's stock, by sku.
 *
 * <p>Sorted by sku rather than by recency, because inventory is a catalogue an operator scans for
 * a known item where orders are a feed they read from the top. It does not poll, for the same
 * reason the orders list does not: stock changes when somebody changes it.
 *
 * @param options the API base, the operator's token, and which page to read.
 * @returns the query result, whose error is an {@link ApiError} carrying the taxonomy code.
 */
export function useInventory({
  baseUrl,
  token,
  page = 0,
}: InventoryOptions): UseQueryResult<InventoryItem[], ApiError> {
  return useQuery<InventoryItem[], ApiError>({
    queryKey: [...inventoryKey(baseUrl, token), page],
    queryFn: ({ signal }) =>
      readEnvelope<InventoryItem[]>({
        baseUrl,
        path: `/inventory?page=${page}&size=${INVENTORY_PAGE_SIZE}`,
        signal,
        token,
      }),
    enabled: Boolean(token),
    retry: 1,
  });
}

/**
 * Writes an item's on-hand count.
 *
 * <p><b>This is the irreversible one.</b> It writes an absolute value rather than an adjustment, so
 * sending 10 to an item holding 500 destroys the 500, and the service refuses only when the result
 * would fall below what is already reserved - which means most mistakes are accepted silently. It
 * is the single write the console confirms before running, and the confirmation is the caller's to
 * show: this hook performs what it is told.
 *
 * @param options the API base and the operator's token.
 * @returns the mutation, whose error is an {@link ApiError}.
 */
export function useSetStock({
  baseUrl,
  token,
}: InventoryOptions): UseMutationResult<InventoryItem, ApiError, SetStockCommand> {
  const queryClient = useQueryClient();

  return useMutation<InventoryItem, ApiError, SetStockCommand>({
    mutationFn: ({ sku, onHand }) =>
      readEnvelope<InventoryItem>({
        baseUrl,
        body: { onHand },
        method: "PUT",
        path: `/inventory/${encodeURIComponent(sku)}`,
        token,
      }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: inventoryKey(baseUrl, token) }),
  });
}

/**
 * Holds stock against an order line.
 *
 * <p>Idempotent by `(orderId, sku)`, which is why it is **not** confirmed: repeating it is safe by
 * construction, and a dialog an operator always clicks through stops protecting the one case that
 * mattered.
 *
 * @param options the API base and the operator's token.
 * @returns the mutation, whose error is an {@link ApiError} carrying any field-level details.
 */
export function useCreateReservation({
  baseUrl,
  token,
}: InventoryOptions): UseMutationResult<Reservation, ApiError, CreateReservationRequest> {
  const queryClient = useQueryClient();

  return useMutation<Reservation, ApiError, CreateReservationRequest>({
    mutationFn: (reservation) =>
      readEnvelope<Reservation>({
        baseUrl,
        body: reservation,
        method: "POST",
        path: "/inventory/reservations",
        token,
      }),
    // A hold changes what is available, so the page behind the form has to catch up.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: inventoryKey(baseUrl, token) }),
  });
}
