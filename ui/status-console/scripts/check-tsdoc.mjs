#!/usr/bin/env node
// Fails when an exported declaration carries no docstring.
//
// The Java half of this repo fails the build on a missing Javadoc for public API. Without this the
// documentation standard would cover one language and not the other, which is how a standard
// quietly becomes a suggestion.
//
// This is a structural check, not a type-aware one: it asserts a block comment immediately precedes
// each top-level `export`. That is deliberately cheap - a type-aware pass would mean adding a
// TypeScript AST library for a rule this simple, against the minimal-dependency rule. The cost is
// that it cannot judge whether the docstring is any good; review does that.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, posix, sep } from "node:path";

const ROOT = "src";
const EXTS = [".ts", ".tsx"];
const SKIP_DIRS = new Set(["generated", "node_modules"]);
const TEST_FILE = /\.(test|spec)\.tsx?$/;

// Top-level exported declarations that need a docstring. `export default` and re-exports
// (`export { x } from "./y"`, `export type { T }`) are excluded: they introduce no new API,
// they forward one that is documented at its definition.
const EXPORTED = /^export\s+(?:async\s+)?(?:function|const|let|class|interface|type|enum)\s+(\w+)/;

/** Walks the source tree, returning every file this check applies to. */
function sourceFiles(dir) {
  const found = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) {
      if (!SKIP_DIRS.has(entry)) {
        found.push(...sourceFiles(path));
      }
    } else if (EXTS.some((ext) => entry.endsWith(ext)) && !TEST_FILE.test(entry)) {
      found.push(path);
    }
  }
  return found;
}

/** True when the lines above the declaration end a block comment. */
function hasDocstring(lines, index) {
  for (let i = index - 1; i >= 0; i--) {
    const line = lines[i]?.trim() ?? "";
    if (line === "") {
      continue;
    }
    return line.endsWith("*/");
  }
  return false;
}

const violations = [];

for (const file of sourceFiles(ROOT)) {
  const normalised = file.split(sep).join(posix.sep);
  const lines = readFileSync(file, "utf8").split("\n");
  lines.forEach((line, index) => {
    const match = EXPORTED.exec(line);
    if (match && !hasDocstring(lines, index)) {
      violations.push(`${normalised}:${index + 1}  export ${match[1]}`);
    }
  });
}

if (violations.length > 0) {
  console.error("Exported declarations with no docstring:\n");
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  console.error(
    `\n${violations.length} violation(s). Say what it is for and why it exists, not what its name already says.`
  );
  process.exit(1);
}

console.log("check:tsdoc - every exported declaration carries a docstring");
