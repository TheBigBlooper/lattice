import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./app/App.tsx";
import { captureReturnTo } from "./auth/returnTo.ts";
import { loadConfig } from "./config.ts";

// Before anything renders or redirects: the session check navigates to Keycloak on mount, which
// replaces the referrer that confirms where an operator arrived from. Confirming here is the only
// moment the browser can still corroborate it.
captureReturnTo();

const root = document.getElementById("root");
if (!root) {
  throw new Error("no #root element - index.html and this entry point disagree");
}

const queryClient = new QueryClient();

createRoot(root).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <App config={loadConfig()} />
    </QueryClientProvider>
  </StrictMode>
);
