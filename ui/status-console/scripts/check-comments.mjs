#!/usr/bin/env node
// Fails when a source comment references an issue number (for example "#42").
//
// Issue references belong in commit messages, pull requests, and the changelog - never in code
// comments (`core_protocol.md`, Code Commenting and Docstrings). A comment saying "workaround for
// #42" is unreadable a year later: the issue is closed, the context is gone, and the code still
// says something a reader cannot check. Write what the code does and why instead.
//
// A `locked #NN` citation is exempt, because the same standard permits it: it points into an
// append-only registry that is never renumbered, so it means something to a reader holding only
// the repository. That is the one form the standard calls out, and the Java sources use it freely.
//
// String literals are blanked before scanning, so a colour like "#141416" or a URL fragment is
// never mistaken for a reference.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, posix, resolve, sep } from "node:path";

const ROOT = "src";
const EXTS = [".ts", ".tsx"];
const SKIP_DIRS = new Set(["generated", "node_modules"]);

const ISSUE_REF = /#\d+\b/;

// One citation may name several decisions: `locked #42, #43, #66`. Only the numbers this form
// covers are removed, so a bare reference sharing the line is still caught.
const LOCKED_CITATION = /\blocked\s+#\d+(?:\s*(?:,|and)\s*#\d+)*/g;

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

/** Replaces the contents of string and template literals with spaces, preserving line structure. */
function blankStringLiterals(source) {
  return source
    .replace(/"(?:[^"\\\n]|\\.)*"/g, (match) => `"${" ".repeat(Math.max(0, match.length - 2))}"`)
    .replace(/'(?:[^'\\\n]|\\.)*'/g, (match) => `'${" ".repeat(Math.max(0, match.length - 2))}'`)
    .replace(/`(?:[^`\\]|\\.)*`/gs, (match) =>
      match
        .replace(/[^\n`]/g, " ")
        .replace(/^ /, "`")
        .replace(/ $/, "`")
    );
}

/** Returns the comment text on a line, or an empty string when there is none. */
function commentText(line, insideBlock) {
  if (insideBlock) {
    return line;
  }
  const lineComment = line.indexOf("//");
  const blockComment = line.indexOf("/*");
  if (lineComment === -1 && blockComment === -1) {
    return "";
  }
  if (blockComment !== -1 && (lineComment === -1 || blockComment < lineComment)) {
    return line.slice(blockComment);
  }
  return line.slice(lineComment);
}

/**
 * Finds every comment in one source file that references an issue number.
 *
 * @param source the file's full text.
 * @returns one entry per offending line - its 1-based number and its trimmed text.
 */
export function commentViolations(source) {
  const found = [];
  const lines = blankStringLiterals(source).split("\n");
  let insideBlock = false;

  lines.forEach((line, index) => {
    const text = commentText(line, insideBlock).replace(LOCKED_CITATION, "");
    if (text && ISSUE_REF.test(text)) {
      found.push({ line: index + 1, text: line.trim() });
    }
    const opened = line.lastIndexOf("/*");
    const closed = line.lastIndexOf("*/");
    if (opened !== -1 && opened > closed) {
      insideBlock = true;
    } else if (closed !== -1 && closed > opened) {
      insideBlock = false;
    }
  });
  return found;
}

// Importing this file must not scan anything: the test imports it for the function above.
if (process.argv[1] && resolve(process.argv[1]) === import.meta.filename) {
  main();
}

function main() {
  const violations = [];

  for (const file of sourceFiles(ROOT)) {
    const normalised = file.split(sep).join(posix.sep);
    for (const violation of commentViolations(readFileSync(file, "utf8"))) {
      violations.push(`${normalised}:${violation.line}  ${violation.text}`);
    }
  }

  if (violations.length === 0) {
    console.log("check:comments - no issue references in code comments");
    return;
  }
  console.error("Issue references found in code comments:\n");
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  console.error(
    `\n${violations.length} violation(s). Describe what the code does and why, not which ticket asked for it.`
  );
  console.error(
    'A `locked #NN` citation is permitted and is the intended short form; a bare "#42" is not.'
  );
  process.exit(1);
}
