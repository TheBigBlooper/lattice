import { describe, expect, it } from "vitest";
import { commentViolations } from "./check-comments.mjs";

describe("commentViolations", () => {
  /** A bare issue reference in a line comment is what this gate exists to catch. */
  it("flags a bare issue reference", () => {
    expect(commentViolations("// workaround for #42\n")).toEqual([
      { line: 1, text: "// workaround for #42" },
    ]);
  });

  /** `locked #NN` is a citation into an append-only registry, which core_protocol.md permits. */
  it("permits a locked-decision citation", () => {
    expect(commentViolations("// Derived from the peer states (locked #80).\n")).toEqual([]);
  });

  /** Several decisions are cited as one comma-separated list in the existing sources. */
  it("permits a list of locked-decision citations", () => {
    expect(commentViolations("// locked #42, #43, #66\n// locked #66 and #67\n")).toEqual([]);
  });

  /** Permitting the citation must not blank the rest of the line and hide a real reference. */
  it("flags a bare reference sharing a line with a locked citation", () => {
    expect(commentViolations("// locked #80, pending #191\n")).toEqual([
      { line: 1, text: "// locked #80, pending #191" },
    ]);
  });

  /** "locked" has to precede the number: a bare citation elsewhere in the sentence is not one. */
  it("flags a reference that only mentions a decision nearby", () => {
    expect(commentViolations("// see the locked decisions, especially #80\n")).toHaveLength(1);
  });

  /** Code is not a comment: a colour or a fragment in a string literal is never a reference. */
  it("ignores an issue-shaped literal in code", () => {
    expect(commentViolations('const border = "#141416";\n')).toEqual([]);
  });

  /** A block comment is scanned across every one of its lines, not only the opening one. */
  it("flags a bare reference inside a block comment", () => {
    expect(commentViolations("/*\n * see #42\n */\n")).toEqual([{ line: 2, text: "* see #42" }]);
  });
});
