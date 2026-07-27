import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CircularProgress from "@mui/material/CircularProgress";
import CssBaseline from "@mui/material/CssBaseline";
import Paper from "@mui/material/Paper";
import { ThemeProvider } from "@mui/material/styles";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import { useBaseline } from "./api/useBaseline.ts";
import { usePeers } from "./api/usePeers.ts";
import { returnTo } from "./auth/returnTo.ts";
import { useSession } from "./auth/useSession.ts";
import { ClusterVerdict } from "./components/ClusterVerdict.tsx";
import { DiscoveredBaselines } from "./components/DiscoveredBaselines.tsx";
import { NoAccess } from "./components/NoAccess.tsx";
import { SignedOut } from "./components/SignedOut.tsx";
import { StatusBlock } from "./components/StatusBlock.tsx";
import type { ConsoleConfig } from "./config.ts";
import { useTheme } from "./theme/useTheme.ts";

/** What the shell needs to render this baseline. */
export interface AppProps {
  /** The console's configuration, read once at the composition root. */
  config: ConsoleConfig;
}

/**
 * The console shell.
 *
 * It owns the page surface, the active theme, and the choice of what fills the status block -
 * which is always exactly one thing, so nothing else on the page moves as that choice changes.
 *
 * The session and the data are separate concerns joined here and nowhere else: the session yields a
 * token, the data layer takes one. Neither reaches for the other, which is why the identity flow
 * replaced a placeholder without any panel changing.
 *
 * **The app bar is deliberately more structure than this one screen needs.** It carries the baseline
 * identity and the session today, and it is where navigation lands when the console gains its
 * operational views - putting it in now costs a toolbar and saves rebuilding the shell later.
 *
 * @param props the console configuration.
 * @returns the shell.
 */
export function App({ config }: AppProps) {
  const theme = useTheme();
  const session = useSession(config);
  const { data, error, isPending } = useBaseline({
    baseUrl: config.apiBaseUrl,
    token: session.token,
  });
  const peers = usePeers({ baseUrl: config.apiBaseUrl, token: session.token });

  const signedIn = session.status === "signed-in";

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <AppBar color="default" position="static">
        <Toolbar variant="dense">
          {/*
            The baseline alone. Repeating the product name on every screen of a console that only
            ever shows one product spends the most prominent position on the least useful word;
            which baseline you are looking at is the thing an operator working across several
            actually needs from a title bar.
          */}
          <Typography component="h1" sx={{ flexGrow: 1 }} variant="h6">
            {data?.clusterId ?? config.clusterId}
          </Typography>
          {signedIn && (
            <Box sx={{ alignItems: "center", display: "flex", gap: 1 }}>
              <Typography sx={{ color: "text.secondary" }} variant="body2">
                {session.username}
              </Typography>
              <Button onClick={session.signOut}>Sign out</Button>
            </Box>
          )}
        </Toolbar>
      </AppBar>

      <Box component="main" sx={{ p: 3 }}>
        {/*
          A spinner rather than a sentence. Asking Keycloak whether a session exists is fast, so
          any words here are read as a flash of something going wrong rather than as information -
          and on a refresh they are gone before they can be finished. The label carries the meaning
          for a screen reader, where a spinner alone would say nothing at all.
        */}
        {session.status === "initialising" && (
          <Box
            sx={{
              alignItems: "center",
              display: "flex",
              justifyContent: "center",
              minHeight: "70vh",
            }}
          >
            <CircularProgress aria-label="Checking your session" />
          </Box>
        )}

        {session.status === "signed-out" && (
          <SignedOut
            baseline={config.clusterId}
            baselineVersion={config.baselineVersion}
            onSignIn={session.signIn}
            region={config.region}
            returnTo={returnTo()}
          />
        )}

        {/*
          A refusal is not an outage, and conflating the two is the most misleading thing this
          console can say about a mesh: an operator redirected to a peer where they hold no role
          used to be told the baseline was unreachable, when it was serving perfectly.
        */}
        {signedIn && error?.code === "FORBIDDEN" && (
          <NoAccess
            baseline={data?.clusterId ?? config.clusterId}
            onSignOut={session.signOut}
            returnTo={returnTo()}
          />
        )}

        {signedIn && error && error.code !== "FORBIDDEN" && (
          <StatusBlock tone="error.main">
            <Typography component="span" variant="h6">
              {error.code === "UNAUTHORIZED" ? "Session rejected" : "Cannot reach this baseline"}
            </Typography>
            <Typography component="span" sx={{ color: "text.secondary" }} variant="body2">
              {error.message}
            </Typography>
          </StatusBlock>
        )}

        {signedIn && !error && isPending && (
          <StatusBlock tone="text.secondary">
            <Typography component="span" variant="h6">
              Reading this baseline
            </Typography>
          </StatusBlock>
        )}

        {signedIn && !error && data && (
          // The mesh sits beside the verdict and wraps beneath it on a narrow window. Wrapping
          // rather than shrinking is deliberate: the cluster's own state stays first in reading
          // order at every width, which is the one thing this layout must never trade away.
          <Box sx={{ display: "flex", flexWrap: "wrap", gap: 2 }}>
            <Box sx={{ flex: "1 1 320px", minWidth: 0 }}>
              <ClusterVerdict health={data.health ?? "down"} services={data.services ?? []} />
            </Box>
            <Box sx={{ flex: "2 1 480px", minWidth: 0 }}>
              <Paper sx={{ p: 2 }}>
                {peers.error ? (
                  <Typography sx={{ color: "warning.main" }} variant="body2">
                    Cannot read the mesh registry: {peers.error.message}
                  </Typography>
                ) : (
                  <DiscoveredBaselines peers={peers.data ?? []} />
                )}
              </Paper>
            </Box>
          </Box>
        )}
      </Box>
    </ThemeProvider>
  );
}
