#!/usr/bin/env node
// Fails when a source comment references an issue number (for example "#42").
//
// Issue references belong in commit messages, pull requests, and the changelog - never in code
// comments (`core_protocol.md`, Code Commenting and Docstrings). A comment saying "workaround for
// #42" is unreadable a year later: the issue is closed, the context is gone, and the code still
// says something a reader cannot check. Write what the code does and why instead.
//
// String literals are blanked before scanning, so a colour like "#141416" or a URL fragment is
// never mistaken for a reference.

import { readdirSync, readFileSync, statSync } from "node:fs";
import { join, posix, sep } from "node:path";

const ROOT = "src";
const EXTS = [".ts", ".tsx"];
const SKIP_DIRS = new Set(["generated", "node_modules"]);

const ISSUE_REF = /#\d+\b/;

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

const violations = [];

for (const file of sourceFiles(ROOT)) {
  const normalised = file.split(sep).join(posix.sep);
  const lines = blankStringLiterals(readFileSync(file, "utf8")).split("\n");
  let insideBlock = false;

  lines.forEach((line, index) => {
    const text = commentText(line, insideBlock);
    if (text && ISSUE_REF.test(text)) {
      violations.push(`${normalised}:${index + 1}  ${line.trim()}`);
    }
    const opened = line.lastIndexOf("/*");
    const closed = line.lastIndexOf("*/");
    if (opened !== -1 && opened > closed) {
      insideBlock = true;
    } else if (closed !== -1 && closed > opened) {
      insideBlock = false;
    }
  });
}

if (violations.length > 0) {
  console.error("Issue references found in code comments:\n");
  for (const violation of violations) {
    console.error(`  ${violation}`);
  }
  console.error(
    `\n${violations.length} violation(s). Describe what the code does and why, not which ticket asked for it.`
  );
  process.exit(1);
}

console.log("check:comments - no issue references in code comments");
