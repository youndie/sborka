---
id: B-32
title: "Write the verdict: which of RQ0–RQ2 held, and where the check lives"
status: done
priority: P1
size: S
stage: stage-8-image-spike
blocked_by: [B-28, B-29, B-30, B-31]
---

# B-32 — Write the verdict: which of RQ0–RQ2 held, and where the check lives

The spike ends in a research document, not in a plugin: `research-native-image.md`, beside the
static-binary research, with the numbers from B-27…B-31 and a home for what ships.

- **Three possible homes, chosen by the numbers**: a task in `sborka.native-service` next to
  `stageNativeImage` (Jib works, the check is small); a Jib extension (Jib works and the check needs
  its build plan); a separate plugin with `publishImage` (Jib does not work in any arm).
- **The two sborka decisions the brief revisits are answered here, not earlier**: whether `NEEDED`
  becomes a gate (B-21 said no, against an expected list), and whether the Dockerfile stops being
  the repository's (`writeNativeDockerfile`). The document states the case; the owner decides.
- Where the brief asked the wrong question, the document says so against
  [source-brief-native-image](../research/source-brief-native-image.md).

- AC: `docs/research/research-native-image.md` with RQ0, RQ1, RQ2 each GREEN or RED and the table
  behind it; the home named; B-33 and B-34 rewritten to match, left as `question` for the owner.
- Anchors: `docs/research/research-native-image.md`

## Done, 2026-09-30

[research-native-image](../research/research-native-image.md): RQ0 RED as asked and explained to the
byte (kore-build's `builtAt`, and the linker's `.comment` across hosts); RQ1 GREEN once asked as "with
no daemon" rather than "without Java"; RQ2 GREEN, 15 of 15 against `docker run`, with the `dlopen`
blind spot as a corpus row. The home it recommends — one `jib-core` task in `sborka.native-service`,
published later — and the two sborka decisions it revisits are the owner's; B-33 and B-34 were
rewritten to say what each answer would make them, and stay `question`.
