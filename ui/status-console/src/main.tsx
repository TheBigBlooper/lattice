import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.tsx";
import { loadConfig } from "./config.ts";

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
