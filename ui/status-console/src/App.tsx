import AppBar from "@mui/material/AppBar";
import Box from "@mui/material/Box";
import Button from "@mui/material/Button";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import Toolbar from "@mui/material/Toolbar";
import Typography from "@mui/material/Typography";
import { useBaseline } from "./api/useBaseline.ts";
import { usePeers } from "./api/usePeers.ts";
import { returnTo } from "./auth/returnTo.ts";
import { useSession } from "./auth/useSession.ts";
import { ActivityToasts } from "./components/ActivityToasts.tsx";
import { LoadingScreen } from "./components/LoadingScreen.tsx";
import { NoAccess } from "./components/NoAccess.tsx";
import { SignedOut } from "./components/SignedOut.tsx";
import { StatusBlock } from "./components/StatusBlock.tsx";
import { StatusView } from "./components/StatusView.tsx";
import type { ConsoleConfig } from "./config.ts";
import { useMeshActivity } from "./mesh/useMeshActivity.ts";
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

  // Fed from the same poll the panels render, so the log and the table can never disagree about
  // what the mesh looks like: they are two views of one read, not two reads.
  const activity = useMeshActivity(
    peers.data && data ? { meshLink: data.meshLink, peers: peers.data } : undefined
  );

  const signedIn = session.status === "signed-in";

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/*
        The shell is the viewport. Holding the height here rather than letting the page grow is what
        lets each panel scroll inside its own frame - an operator watching a mesh should not lose the
        cluster verdict off the top because the activity log filled up.

        Only once the columns sit side by side. Below that they wrap into a single column, where a
        fixed height would squeeze three panels into a third of a screen each; there the page scrolls
        as usual.
      */}
      <Box
        sx={{
          display: "flex",
          flexDirection: "column",
          height: { md: "100vh" },
          overflow: "hidden",
        }}
      >
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

        <Box
          component="main"
          sx={{ flex: 1, minHeight: 0, overflow: { md: "hidden", xs: "auto" }, p: 3 }}
        >
          {session.status === "initialising" && <LoadingScreen label="Checking your session" />}

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

          {/*
          The same screen as the session check, deliberately. Starting the console runs the two
          waits back to back, and giving each its own size made the spinner jump from one position
          to another between them - the page appearing to flinch rather than load.

          This is only ever a first paint: it is keyed on isPending, which is false while data
          exists, so the ten-second poll refreshes the dashboard underneath without replacing it.
        */}
          {signedIn && !error && isPending && <LoadingScreen label="Reading this baseline" />}

          {signedIn && !error && data && (
            <StatusView
              activity={activity.entries}
              baseline={data}
              peers={peers.data ?? []}
              peersError={peers.error}
            />
          )}
        </Box>
      </Box>

      {/* Outside main, so the stack is positioned against the window rather than the
          page flow, and a burst cannot push the content it is reporting on. */}
      {signedIn && <ActivityToasts onDismiss={activity.dismissToast} toasts={activity.toasts} />}
    </ThemeProvider>
  );
}
