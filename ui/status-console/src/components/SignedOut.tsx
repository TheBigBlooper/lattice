import { leading, type Palette, radius, scale, type as typeScale } from "../theme/tokens.ts";
import { StatusBlock } from "./StatusBlock.tsx";

/** What the signed-out screen needs to explain itself and offer a way forward. */
export interface SignedOutProps {
  /** The baseline being signed in to, named because an operator may work across several. */
  baseline: string;
  /** The active palette. */
  palette: Palette;
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
 * @param props the baseline name, the palette, and the sign-in action.
 * @returns the signed-out block.
 */
export function SignedOut({ baseline, palette, onSignIn }: SignedOutProps) {
  return (
    <StatusBlock tone={palette.textPrimary} palette={palette}>
      <span
        style={{
          fontSize: typeScale.section,
          lineHeight: leading.verdict,
        }}
      >
        Signed out
      </span>
      <span
        style={{
          color: palette.textSecondary,
          fontSize: typeScale.body,
          lineHeight: leading.body,
          margin: `${scale.xs}px 0 ${scale.sm}px`,
        }}
      >
        Sign in to {baseline} to see its services.
      </span>
      <button
        onClick={onSignIn}
        style={{
          backgroundColor: palette.surfacePage,
          border: `1px solid ${palette.border}`,
          borderRadius: radius.pill,
          color: palette.textPrimary,
          cursor: "pointer",
          fontSize: typeScale.body,
          padding: `${scale.xs}px ${scale.md}px`,
        }}
        type="button"
      >
        Sign in
      </button>
    </StatusBlock>
  );
}
