import {
  type UseMutationResult,
  type UseQueryResult,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { type ApiError, readEnvelope } from "./client.ts";
import type { components } from "./generated/v1.ts";

/** One order, exactly as the contract defines it. */
export type Order = components["schemas"]["Order"];

/** What creating an order needs, exactly as the contract defines it. */
export type CreateOrderRequest = components["schemas"]["CreateOrderRequest"];

/** How many orders one page holds. Small enough to read, large enough to be worth paging. */
export const ORDERS_PAGE_SIZE = 20;

/** What the hooks need to reach this baseline. */
export interface OrdersOptions {
  /** The API base for this baseline, from config. */
  baseUrl: string;
  /** The operator's bearer token. Absent means signed out, and nothing is fetched. */
  token?: string | undefined;
  /** The zero-based page to read. */
  page?: number;
}

/**
 * The query key both hooks agree on.
 *
 * <p>Declared once because the mutation has to invalidate exactly what the query filled. Written
 * out twice they would agree until the day one gained a parameter, and the symptom - a list that
 * silently stops refreshing after a create - looks nothing like the cause.
 */
function ordersKey(baseUrl: string, token: string | undefined) {
  return ["orders", baseUrl, token] as const;
}

/**
 * Reads a page of this baseline's orders, newest first.
 *
 * <p><b>It does not poll.</b> The status view polls because it answers a question whose answer
 * changes on its own; a list of orders changes when somebody changes it. The list refreshes when
 * this console creates one, and an operator who wants to see another's work reloads - which is a
 * far better trade than every open Orders tab re-reading the index every ten seconds.
 *
 * <p>Nothing is fetched without a token, for the same reason as every other read: an
 * unauthenticated call is a known 401, so making it anyway would turn the signed-out screen into a
 * request that fails on every render.
 *
 * @param options the API base, the operator's token, and which page to read.
 * @returns the query result, whose error is an {@link ApiError} carrying the taxonomy code.
 */
export function useOrders({
  baseUrl,
  token,
  page = 0,
}: OrdersOptions): UseQueryResult<Order[], ApiError> {
  return useQuery<Order[], ApiError>({
    queryKey: [...ordersKey(baseUrl, token), page],
    queryFn: ({ signal }) =>
      readEnvelope<Order[]>({
        baseUrl,
        path: `/orders?page=${page}&size=${ORDERS_PAGE_SIZE}`,
        signal,
        token,
      }),
    enabled: Boolean(token),
    retry: 1,
  });
}

/**
 * Places an order on this baseline.
 *
 * <p><b>It returns the created order rather than a success flag.</b> The service generates the id,
 * and with no search operation in the contract that id is the operator's only precise handle on
 * what they just made - so the console renders what came back rather than announcing that
 * something happened.
 *
 * <p>The list is invalidated on success, so the page behind the form catches up without this hook
 * knowing how the list is rendered.
 *
 * @param options the API base and the operator's token.
 * @returns the mutation, whose error is an {@link ApiError} carrying any field-level details.
 */
export function useCreateOrder({
  baseUrl,
  token,
}: OrdersOptions): UseMutationResult<Order, ApiError, CreateOrderRequest> {
  const queryClient = useQueryClient();

  return useMutation<Order, ApiError, CreateOrderRequest>({
    mutationFn: (order) =>
      readEnvelope<Order>({ baseUrl, body: order, method: "POST", path: "/orders", token }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ordersKey(baseUrl, token) }),
  });
}
