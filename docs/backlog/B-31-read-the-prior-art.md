---
id: B-31
title: "Read melange's SCA and container-structure-test against the corpus: does anything already fail this build?"
status: wip
priority: P2
size: S
stage: stage-8-image-spike
---

# B-31 — Read melange's SCA and container-structure-test against the corpus: does anything already fail this build?

"Nobody checks NEEDED against the base" is the claim the publishable part stands on, and it is a
hypothesis. The two nearest things: melange derives `so:` dependencies from `NEEDED` and apko
resolves them — at package level, for images built from packages; container-structure-test has a
tar driver that checks file existence — against a list someone writes by hand.

- **Asked of each**: given keel's binary and a distroless base by digest, does it fail on the
  corpus rows without a hand-written list, and without a daemon?
- Does not cover: adopting either. If one of them answers the question, B-32 says so and B-33 changes.

- AC: a paragraph per tool with what was run (or read, and where), and a yes/no per corpus row.
- Anchors: `docs/research/image-probe/`
