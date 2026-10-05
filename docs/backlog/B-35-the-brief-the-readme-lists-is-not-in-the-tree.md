---
id: B-35
title: "The README lists a memory-limit brief the tree does not have, and the documentation gate is red on main"
status: done
priority: P2
size: XS
stage: stage-8-image-spike
---

# B-35 — The README lists a memory-limit brief the tree does not have, and the documentation gate is red on main

`make gate` fails on `main` with one discrepancy, and has since the line arrived:

```
[Research] in the map, but there is no file: source-brief-memory-limit
```

`docs/README.md` lists `research/source-brief-memory-limit.md` ("kept as it arrived while the work
is still ahead"); the file has never been committed. CI does not run `make gate` (`check.yaml` runs
`./gradlew check`), so nothing red shows on a pull request, and every branch cut from `main`
inherits the failure.

Filed here because the native-image loop found it in its first orientation: the loop measures its
own pull requests against this one known discrepancy rather than against a green gate. It is not in
this strand's stage by subject — only by who noticed.

- **The question, for the owner of that brief**: commit the brief (the work it describes is under
  way), or take the line out of the README until it is. Either makes the gate green; which one is not
  this strand's to choose.
- Does not cover: whether CI should run `make gate`. It would have caught this on day one, and it is
  a separate decision.

- AC: `make gate` on `main` reports no discrepancies.
- Anchors: `docs/README.md`

## Resolution (2026-10-05)

The owner chose the second option: the brief is dropped, not committed. The README line is gone, so
`make gate` on `main` reports no discrepancies. The same change runs `make gate` in CI (a `docs` job in
`check.yaml`), so a README line naming a file that is not in the tree turns a pull request red instead
of every branch cut from `main`.
