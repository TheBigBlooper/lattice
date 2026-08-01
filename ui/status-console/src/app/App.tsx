import Box from "@mui/material/Box";
import CssBaseline from "@mui/material/CssBaseline";
import { ThemeProvider } from "@mui/material/styles";
import { useBaseline } from "../api/useBaseline.ts";
import { usePeers } from "../api/usePeers.ts";
import { returnTo } from "../auth/returnTo.ts";
import { useSession } from "../auth/useSession.ts";
import type { ConsoleConfig } from "../config.ts";
import { ActivityToasts, useActivity } from "../features/activity/index.ts";
import { useThemeChoice } from "../theme/useThemeChoice.ts";
import { ConsoleAppBar } from "./ConsoleAppBar.tsx";
import { ConsoleScreen } from "./ConsoleScreen.tsx";

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
  const { theme, choice, setChoice } = useThemeChoice();
  const session = useSession(config);
  // fetchStatus and dataUpdatedAt were already being tracked here and thrown away, which is why a
  // failed read could say nothing about whether it was still trying. Surfacing them needs no second
  // polling or retry engine - the query layer has held both all along.
  const { data, error, isPending, fetchStatus, dataUpdatedAt } = useBaseline({
    baseUrl: config.apiBaseUrl,
    token: session.token,
  });
  const peers = usePeers({ baseUrl: config.apiBaseUrl, token: session.token });

  // Fed from the same poll the panels render, so the log and the cards can never disagree about
  // what is happening: they are two views of one read, not two reads. That now covers this
  // baseline's own services and infrastructure as well as the mesh, which is what lets both halves
  // share a single timeline.
  const activity = useActivity(
    peers.data && data
      ? {
          meshLink: data.meshLink,
          peers: peers.data,
          services: data.services,
          infrastructure: data.infrastructure,
        }
      : undefined
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
        <ConsoleAppBar
          clusterId={data?.clusterId ?? config.clusterId}
          onSignOut={signedIn ? session.signOut : undefined}
          onThemeChoice={setChoice}
          region={data?.region ?? config.region}
          role={session.role}
          themeChoice={choice}
          username={session.username}
          version={data?.baselineVersion ?? config.baselineVersion}
        />

        <Box
          component="main"
          sx={{ flex: 1, minHeight: 0, overflow: { md: "hidden", xs: "auto" }, p: 3 }}
        >
          <ConsoleScreen
            activity={activity.entries}
            baseline={data}
            config={config}
            error={error}
            isPending={isPending}
            isRetrying={fetchStatus === "fetching"}
            lastGoodRead={dataUpdatedAt || undefined}
            peers={peers.data ?? []}
            peersError={peers.error}
            returnTo={returnTo()}
            session={session}
          />
        </Box>
      </Box>

      {/* Outside main, so the stack is positioned against the window rather than the
          page flow, and a burst cannot push the content it is reporting on. */}
      {signedIn && <ActivityToasts onDismiss={activity.dismissToast} toasts={activity.toasts} />}
    </ThemeProvider>
  );
}
