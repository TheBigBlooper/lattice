import Button from "@mui/material/Button";
import Typography from "@mui/material/Typography";
import { StatusBlock } from "./StatusBlock.tsx";

/** What the signed-out screen needs to explain itself and offer a way forward. */
export interface SignedOutProps {
  /** The baseline being signed in to, named because an operator may work across several. */
  baseline: string;
  /** Starts the sign-in flow against this baseline's own identity provider. */
  onSignIn: () => void;
}

/**
 * The signed-out screen.
 *
 * This is a real state, not an error. Identity belongs to the baseline that owns the data, so an
 * operator arriving without a session here is the system working correctly - including an operator
 * redirected from a peer, who may hold a perfectly good session somewhere else. It therefore says
 * plainly what is true and offers the one action that resolves it, rather than rendering an empty
 * dashboard the operator has to interpret.
 *
 * It renders into the same {@link StatusBlock} as the verdict it replaces, at the same size and in
 * the same position, so signing in does not reflow the page.
 *
 * @param props the baseline name and the sign-in action.
 * @returns the signed-out block.
 */
export function SignedOut({ baseline, onSignIn }: SignedOutProps) {
  return (
    <StatusBlock tone="text.primary">
      <Typography component="span" variant="h6">
        Signed out
      </Typography>
      <Typography component="span" sx={{ color: "text.secondary", mb: 1, mt: 0.5 }} variant="body2">
        Sign in to {baseline} to see its services.
      </Typography>
      <Button onClick={onSignIn} variant="outlined">
        Sign in
      </Button>
    </StatusBlock>
  );
}
