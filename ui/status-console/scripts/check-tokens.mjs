#!/usr/bin/env node
// Fails when a colour literal appears outside the theme module.
//
// The design system is only real if it is enforced: a single `#3fb950` in a component silently
// opts that component out of dark mode, and nothing else would catch it. `ui_protocol.md` has
// carried this rule as "verify by inspection" since it was written; this is the gate.
//
// It matters more under Material UI, not less. Components style through `sx`, which accepts a raw
// colour just as readily as a palette key - so `sx={{ color: "#2e7d32" }}` looks entirely idiomatic
// while being exactly the drift this exists to stop. The check is line-based and catches a literal
// wherever it appears, `sx` included.
//
// String literals are NOT blanked here, unlike check-comments: a hardcoded colour IS a string
// literal, so blanking them would hide exactly what this looks for.
//
// Escape hatch: end the line with `// allow-colour-literal: <reason>`. Use it for a value that
// genuinely cannot be a token (a canvas gradient stop, an SVG fill computed from data).

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, posix, sep } from "node:path";

const ROOT = "src";
const EXTS = [".ts", ".tsx", ".css"];
const SKIP_DIRS = new Set(["generated", "node_modules"]);

// The one file where a colour is allowed to exist: the Material UI theme.
const THEME_MODULE = posix.join("src", "theme", "theme.ts");

const COLOUR = /#[\da-fA-F]{3,8}\b|\b(?:rgba?|hsla?)\s*\(/;
const ALLOW = /\/\/\s*allow-colour-literal:/;

/** Walks the source tree, returning every file this check applies to. */
function sourceFiles(dir) {
  const found = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (!SKIP_DIRS.has(entry)) {
        found.push(...sourceFiles(path));
      }
    } else if (EXTS.some((ext) => entry.endsWith(ext))) {
      found.push(path);
    }
  }
  return found;
}

const violations = [];

for (const file of sourceFiles(ROOT)) {
  const normalised = file.split(sep).join(posix.sep);
  if (normalised === THEME_MODULE) {
    continue;
  }
  const lines = readFileSync(file, "utf8").split("\n");
  lines.forEach((line, index) => {
    if (COLOUR.test(line) && !ALLOW.test(line)) {
      violations.push(`${normalised}:${index + 1}  ${line.trim()}`);
    }
  });
}

if (violations.length > 0) {
  console.error("Colour literals found outside the theme module:\n");
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  console.error(
    `\n${violations.length} violation(s). Use a theme palette key (for example "success.main") instead; colours live only in ${THEME_MODULE}.`
  );
  console.error(
    "If it genuinely cannot come from the theme, append: // allow-colour-literal: <reason>"
  );
  process.exit(1);
}

console.log(`check:tokens - no colour literals outside ${THEME_MODULE}`);
