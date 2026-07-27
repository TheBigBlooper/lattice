---
name: qa-steps
description: Produce a numbered, step-by-step QA script for a branch - every step with an exact action, an exact expected result, and a place to record pass/fail. Use when a change is ready for founder QA, or when a QA round found problems and the next round needs to be precise about what to re-check.
---

# QA steps - the numbered script

Turn "ready to test" into a script a founder can work through without inventing the steps themselves, and without the ambiguity that makes a QA round inconclusive.

**Why this exists.** A prose handover ("check the failure states, try dark mode") puts the work of designing the test on the person doing it, and produces feedback like "looks fine" that cannot be acted on. A numbered script with an exact expected result per step produces feedback like "3b failed, the chip stayed green" - which is a defect report, a reproduction, and a regression test all at once.

---

## When to run

- A branch is pushed and about to be handed over for founder QA.
- A QA round found problems, and the next round needs to state precisely what to re-check.
- A change is hard to verify by reading the diff (anything visual, stateful, or timing-dependent).

Skip it for a change with no observable surface - a build gate, a doc edit, a refactor with no behavioural change. Those are verified by the build, and a QA script for them is ceremony.

---

## Step 1 - Establish where QA actually happens

**Before writing any step, confirm what the founder will be looking at, and that it is running the code under test.** Getting this wrong wastes an entire QA round and is the most common way a script fails.

Ask, and answer concretely:

- **Which surface?** A dev server, a rebuilt container, a deployed cluster, a command line.
- **Is it running this branch's code?** A container built before the change is running the *old* code. Say so explicitly, and say what has to be rebuilt or restarted.
- **What must be up first?** Name the exact command that brings the dependencies up.
- **What credentials?** Name them, and say where they come from.

State this as a **Preconditions** block at the top of the script. A step that assumes the wrong surface is worse than no step.

---

## Step 2 - Derive the steps from what changed

Work from the diff, not from imagination. Every step traces to something the change actually did.

Cover, in this order:

1. **The happy path** - the thing the change was for, in its normal state.
2. **The states that motivated the change** - the failure states, empty states, and edge cases the design exists to handle. These are usually the point; a script that only checks the healthy path proves the least.
3. **What must NOT have changed** - the regression surface. Name the neighbouring behaviour the change could plausibly have broken.
4. **The environment dimensions** - theme, viewport, role, and permission, where the change is sensitive to them.

---

## Step 3 - Write the steps

**Numbering is the contract.** Top-level steps are numbers. Sub-steps are letters, used **when a single step has more than one thing to observe** - so a founder can report "4c failed" and both sides know exactly which observation broke.

Every step has three parts, and none is optional:

- **Do** - one concrete action. If a step needs two actions, it is two steps.
- **Expect** - the exact observable result. Not "looks right" but "the row stays, dims, and shows an `unreachable` chip".
- **Result** - left blank, for the founder to fill.

**Rules for a step that is worth writing:**

- **One assertion per sub-step.** "Check the colours and the layout" cannot be failed precisely.
- **Exact values, never approximations.** "the count reads `1 of 2 peers reachable`", not "the count updates".
- **Include the wait.** If a state takes 45 seconds to appear, say 45 seconds and say why, or the founder will call it a failure at 10.
- **Say what failure looks like**, where a step has a plausible near-miss. "If the row disappears instead of dimming, that is the defect this step exists to catch."
- **Never write a step whose result you already know.** If the build proves it, the build is the check.

---

## Step 4 - Render it

Use this shape. It goes in a ticket comment, not a file, because it is per-round rather than permanent.

```markdown
## QA script - <branch>

**Preconditions**
- Surface: <where to look, and confirmation it runs this branch's code>
- Bring up: `<exact command>`
- Credentials: <exact credentials, and where they come from>
- Expected duration: <rough>

### 1. <What this group checks>
**Do:** <action>
**Expect:** <exact observable>
**Result:**

### 2. <Group with several observations>
**Do:** <action>
- **2a. Expect:** <one observable>
  **Result:**
- **2b. Expect:** <another observable>
  **Result:**

### 3. Regression - <what must not have changed>
...
```

End with:

```markdown
**Report back as:** the step number, what you saw, and a screenshot for anything visual.
A bare "looks good" on a numbered script loses the information the numbering exists to carry.
```

---

## Step 5 - Close the loop

When the founder reports failures:

1. **Quote the step number** in the fix, so the change and the observation stay linked.
2. **Re-issue only the affected steps** plus any regression steps the fix could plausibly touch. Do not re-issue the whole script; that trains people to skim it.
3. **A step that failed once is a candidate for a test.** If it can be asserted automatically, say so and propose it - a QA step that keeps failing is a missing test wearing a disguise.

---

## What this is not

- **Not a substitute for automated tests.** Anything a test can assert belongs in a test. This covers what automation genuinely cannot: does it look right, does it read right, does the real system behave.
- **Not a checklist to pad.** Ten precise steps beat forty vague ones. Every step earns its place or comes out.
- **Not a place to hide uncertainty.** If you do not know what a step should produce, say so in the step rather than writing a vague expectation and hoping.
