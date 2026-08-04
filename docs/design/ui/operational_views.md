# Operational views - acting on a baseline from its own console

The status console gains views for placing orders and managing stock. This document settles what those views do, what has to be added to the contract before they can exist, how an operator without write access experiences them, and what happens when a write succeeds or fails.

Related: [_index.md](_index.md) (the visual direction and the Material UI system these are built in), [interop_console.md](../features/interop_console.md) (the peer redirect), [orders.md](../services/orders.md) + [inventory.md](../services/inventory.md) (the operations being surfaced), [api_structure.md](../architecture/api_structure.md) (the response envelope and the error taxonomy), [ui_protocol.md](../../protocol/ui_protocol.md).

---

## Why this needed a design session

`ui_protocol.md` reserves this case explicitly:

> Keep the surface focused on observability. It is read-mostly; any control that mutates cluster state is a deliberate, reviewed addition, not a default.

**This document is that review.** Three write operations are admitted deliberately, and the clause is kept rather than amended, so the next one still has to argue for itself.

Three things follow that have never existed in this console: routing, writes, and role-aware rendering.

---

## The decisions

| # | Question             | Decision                                                              |
|---|----------------------|-----------------------------------------------------------------------|
| 1 | The list gap         | **Add list operations to the contract.** The views must be browsable. |
| 2 | List shape           | **Paged, newest first, no filters.**                                  |
| 3 | Navigation           | **Tabs in the app bar**; Status is the default route.                 |
| 4 | Viewer experience    | **Visible but disabled**, with the reason stated.                     |
| 5 | Confirmation         | **`setStock` only.**                                                  |
| 6 | Errors               | **Inline for field errors**, banner for everything else.              |
| 7 | Peer redirect        | **Stays an action on the peer row.**                                  |
| 8 | After a write        | **Show the created record in place.**                                 |
| 9 | The read-mostly rule | **Kept.**                                                             |

---

## 1. The contract cannot support a browsable view today

The nine operations are `getBaseline`, `getPeers`, `createOrder`, `getOrder`, `setStock`, `getInventory`, `createReservation`, `getHealth`, `getReadiness`. **None of them lists anything.** `getOrder` takes an order id; `getInventory` takes a sku.

So the contract as it stands supports a console an operator can submit to and look things up in, but not one they can browse. "What orders exist on this baseline" is unanswerable without already knowing an id, which in practice means an id they created moments earlier in the same session.

**Two list operations are therefore added**, and they must land before any view is built:

| Operation       | Returns                               |
|-----------------|---------------------------------------|
| `listOrders`    | A page of this baseline's orders      |
| `listInventory` | A page of this baseline's stock items |

**This makes the work span the seam.** It is a change to `lattice-contract` (single-writer), then Elasticsearch queries in two services, then the console. Three build tickets in that order, not one, and the contract change serialises against any other in-flight contract work.

The alternative - shipping lookup-only views with no contract change - was rejected as an API tester rather than an operations console.

---

## 2. Paged, newest first, nothing else

`listOrders` returns orders sorted by `createdAt` descending; `listInventory` returns stock items by sku. Each takes a page size and a position, and nothing more.

**No filters, deliberately.** This is the smallest surface that makes the views browsable, and it maps onto a plain Elasticsearch search with a sort, so **no mapping changes are required**. Filters can be added later without breaking the shape, and choosing them now would be guessing at which ones matter before anyone has used the screen.

**Free-text search was rejected outright**, and not on effort. A shared query contract commits every baseline to the same query semantics, and locked #14 gives each baseline its own possibly-divergent data model. A search endpoint is a much harder cross-baseline promise than a sorted page, and Shape A (locked #37) means nobody queries a peer's data anyway.

---

## 3. Tabs, with Status as home

Three destinations - **Status**, **Orders**, **Inventory** - as tabs in the app bar the Material UI migration introduced. That app bar was built deliberately larger than one screen needed for exactly this.

**Status remains the default route.** The console's first job is still answering "is this baseline healthy", and an operator arriving at it should not have to navigate to find that out.

A drawer was rejected: it spends horizontal space permanently on a console whose main screen is already two columns, and three items in a drawer looks like a drawer waiting for content it does not have. It becomes the right answer somewhere past six or seven destinations.

---

## 4. A viewer sees the screen, disabled, and is told why

Locked #48 gives `viewer` every GET and `operator` the writes. A viewer therefore sees the Orders and Inventory tabs and all their data, with **write controls rendered but disabled**, and a short line stating that the action needs the `operator` role **on this baseline**.

**The last three words are the point.** Locked #49 makes realm membership deliberately unsynchronised: the same person may hold `operator` on one baseline and `viewer` on another, and a redirect from a peer can legitimately land them somewhere they cannot act. Hiding the controls would leave them unable to tell whether the capability is missing from the product or from their grant - which on a mesh of independently governed baselines is the confusing failure, not the informative one.

Rendering the controls live and letting the 403 explain itself was rejected: it teaches operators that buttons sometimes just fail, which is the opposite of what a status console should teach.

---

## 5. One confirmation, on the one irreversible write

`setStock` is a `PUT` of an **absolute** value. Sending `10` to an item holding `500` destroys the `500`, and the service rejects it only if the new value would fall below what is already `reserved` - so most mistakes are accepted silently and cannot be undone.

It gets a confirmation. **Nothing else does.** `createOrder` is additive, and `createReservation` is idempotent by `(orderId, sku)`, so repeating it is safe by construction.

Confirming every write was rejected for the reason confirmations usually fail: a dialog an operator always clicks through stops protecting the one case that mattered.

---

## 6. Errors go where the operator can act on them

The response envelope already distinguishes the cases, so the console uses that rather than flattening it:

| Error                                    | Where it appears                                                                                               |
|------------------------------------------|----------------------------------------------------------------------------------------------------------------|
| `VALIDATION_ERROR`                       | **Against the offending field.** The service names them in `details`.                                          |
| `CONFLICT`                               | **A banner above the form**, carrying the server's wording (insufficient stock, or `onHand` below `reserved`). |
| `UNAVAILABLE`, `NOT_FOUND`, and the rest | The same banner.                                                                                               |

The split is about what the operator can do next: a validation failure is about their input and belongs where they will fix it; a conflict or an outage is about the world, and no amount of editing the form changes it.

Attaching conflicts to fields as well was considered and rejected - a conflict is not always attributable to one field, so it needs the banner as a fallback regardless, and the error-to-field mapping becomes console logic that can drift from the service.

---

## 7. Reaching a peer stays where the peer is

The "go to this baseline" redirect remains a control on the peer's row in the status view. It is a fact about that peer rather than a destination of this console, and the tabs stay about **this** baseline, which is the honest reading of Shape A.

A dedicated baselines tab would duplicate the mesh panel that already exists, and a tab that navigates away from the application is a strange thing for a tab to be.

---

## 8. A successful write shows what it produced

`createOrder` returns the whole `Order`, including its **server-generated** id; `createReservation` returns the `Reservation`. The console renders what came back, in place, with the list refreshing behind it.

**This follows directly from decision 1.** There is no search operation, so a newly created order's id is the operator's only precise handle on it. A toast would show that id and then throw it away.

It also keeps an operator placing several orders in a row on the same screen, which navigating to a detail view would interrupt.

---

## 9. The read-mostly rule is kept, not amended

`ui_protocol.md`'s clause stands exactly as written. **This document is the deliberate review it requires**, admitting three named write operations with the reasoning recorded.

Amending it to describe an operational console was rejected. That clause is the only thing standing between an operator console and a general-purpose administrative surface, and the next mutating control should have to make its own case rather than find the door already open.

---

## What this changes elsewhere

| Document                                                                      | Change                                                                                                               |
|-------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------|
| `lattice-contract` OpenAPI spec                                               | Two new operations, `listOrders` and `listInventory`. Single-writer, so this serialises against other contract work. |
| [orders.md](../services/orders.md) + [inventory.md](../services/inventory.md) | A listing operation each, with its Elasticsearch query. No mapping change.                                           |
| [ui_protocol.md](../../protocol/ui_protocol.md)                               | Navigation section gains the actual route table. The read-mostly clause is **unchanged**.                            |
| [_index.md](_index.md)                                                        | The screen set gains Orders and Inventory.                                                                           |

---

## Build order

1. **Contract** - the two list operations, tested on both sides of the seam.
2. **Services** - the queries behind them, in orders and inventory.
3. **Console** - the tabs, the two views, role-aware controls, and the error surfaces.

The console ticket carries `needs-mockup`: these are new screens with forms, and forms are where a confirmed visual direction earns the most.

---

## Deferred

- **Filters and search.** Revisit once the paged views have been used and it is known which filter is actually wanted.
- **A detail route per resource.** Not needed while a created record renders in place and lists are short.
- **Editing or cancelling an order.** The lifecycle exists in the data model (`RECEIVED` onward) but no transition endpoint does, so there is nothing to surface.
