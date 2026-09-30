---
id: B-37
title: "Move the load check out of docs/research into build-logic, with the corpus as its test suite"
status: done
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

## Done, 2026-09-30 — `build-logic/image`, twelve unit tests, and the corpus as a CI gate

- **Where, and why not `core`**: its own module, `:image`. `core` has no dependencies at all and
  `conventions` exposes it with `api`, so anything `core` carried would reach every consumer of every
  convention. `:image` needs a tar reader and a JSON reader; it takes the two jib-core brings
  (commons-compress, jackson-databind), so the image task of B-33 adds nothing the check had not.
  commons-compress is 1.28.0, not jib-core's 1.26.0: that one's tar writer needs commons-codec
  without declaring it, and the first test run died on it.
- **Unit tests** (`LoadCheckTest`, 12): synthetic ELF files, layers and `ld.so.cache` written in the
  test, so every byte a test depends on is in the review. Each rule has one — absolute interpreter
  link, missing library, no interpreter, static binary, transitive needs, versions and weak versions,
  `RUNPATH`, `LD_LIBRARY_PATH`, the cache, whiteout, opaque directory. Four mutants, each killed by a
  named test: whiteout ignored, weak versions everywhere, no stop at a missing interpreter, `RUNPATH`
  ignored. (A fifth "kill" was a compile error and was redone as a mutant that compiles.)
- **The corpus in CI** (`.github/workflows/image-corpus.yaml`, path-filtered to the check, the corpus,
  the catalog and the setup action): `corpus.sh` writes what `docker run` did to `build/corpus.tsv`,
  and `score.sh` now fails on a row where Docker stopped doing what the row says as well as on one
  where the check disagrees — both halves from the same run. Without keel, the `--as-needed` probe
  stands in; its NEEDED list is keel's. **23 of 23** on the Linux host in exactly that mode
  ([results/2026-09-30-score-b37.txt](../research/image-probe/results/2026-09-30-score-b37.txt)).
- **One implementation**: `docs/research/image-probe/check/` is deleted; the research and B-36 point
  at the module.
- **Published and judged like the others**: `:image` is in the root build's completeness check and in
  proba's list. Its POM carries commons-compress and jackson-databind only — the Kotlin standard
  library the command needs ships in the command's distribution, not in the module's dependencies.
