---
id: B-33
title: "Make the load check a gate in the home B-32 names, with keel's corpus rows in CI"
status: question
priority: P1
size: M
stage: stage-9-image-ship
blocked_by: [B-32]
---

# B-33 — Make the load check a gate in the home B-32 names, with keel's corpus rows in CI

Only if B-30 is GREEN, and only after the owner's answer on the gate decision B-32 lays out —
hence `question`, not `open`.

- AC: keel's image build fails before push when its base cannot load the binary; the corpus rows run
  in CI.
- Anchors: decided by B-32.
