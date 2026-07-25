#!/usr/bin/env node
// Fails when a colour literal appears outside the token module.
//
// The design system is only real if it is enforced: a single `#3fb950` in a component silently
// opts that component out of dark mode, and nothing else would catch it. `ui_protocol.md` has
// carried this rule as "verify by inspection" since it was written; this is the gate.
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

// The one file where the palette is allowed to exist.
const TOKEN_MODULE = posix.join("src", "theme", "tokens.ts");

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
  if (normalised === TOKEN_MODULE) {
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
  console.error("Colour literals found outside the token module:\n");
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  console.error(
    `\n${violations.length} violation(s). Read the value from the palette in ${TOKEN_MODULE} instead.`
  );
  console.error("If it genuinely cannot be a token, append: // allow-colour-literal: <reason>");
  process.exit(1);
}

console.log(`check:tokens - no colour literals outside ${TOKEN_MODULE}`);
