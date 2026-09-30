---
id: B-37
title: "Move the load check out of docs/research into build-logic, with the corpus as its test suite"
status: open
priority: P1
size: M
stage: stage-9-image-ship
blocked_by: [B-36]
---

# B-37 — Move the load check out of docs/research into build-logic, with the corpus as its test suite

The prototype lives in `docs/research/image-probe/check/` and is scored by a script run by hand. A gate
needs it where conventions can call it, under the repository's own rules (explicit API, ktlint, its
tests in `./gradlew check`), and it needs the corpus to run where every pull request runs.

- **Where**: a module of `build-logic` — `core`, beside the class-file readers, if its dependencies
  (commons-compress, a JSON reader, an HTTP client) are acceptable on every convention's classpath;
  otherwise its own module that only `sborka.native-service` pulls in. The item decides and says why.
- **Tests at two levels**: unit tests on ELF and layer fixtures small enough to commit; and the corpus
  — built from the probe binaries, not keel, since a hello-world asks the loader the same question —
  as a CI job with Docker on the runner, comparing the check with `docker run` row by row.
- **The research copy is deleted** in the same change, and `score.sh` points at the moved code, so
  there is one implementation to be wrong.
- Does not cover: any task a consumer applies (B-33).

- AC: the check runs from `./gradlew check` with its unit tests, and a CI job reports the corpus row
  by row, red on any disagreement except the declared `dlopen` row.
- Anchors: `build-logic/core/`, `.github/workflows/check.yaml`, `docs/research/image-probe/`
