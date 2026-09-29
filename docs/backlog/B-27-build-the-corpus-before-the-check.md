---
id: B-27
title: "Build the corpus of images that do and do not start, before a line of the check exists"
status: wip
priority: P1
size: M
stage: stage-7-image-ground-truth
---

# B-27 — Build the corpus of images that do and do not start, before a line of the check exists

The check this strand proposes ([source-brief-native-image](../research/source-brief-native-image.md)
§4, feature-base-load-check) is only worth anything against failures that actually happened, and it
can only be judged against a verdict it did not produce. So the verdicts come first: seven images,
each run with `docker run`, each result written down before [B-30](B-30-resolve-the-binary-against-the-base.md)
writes code that could shape what "fails" means.

- **The rows are the failures this portfolio paid for, not a taxonomy.** keel on `cc-debian13`
  (starts); on `base-debian13` (no `libgcc_s`); a curl-carrying binary on `cc-debian12` (no `libz`);
  keel linked without `--as-needed` on `cc-debian13` (no `libcrypt.so.1`); a `libcrypt.so.1` copied
  from an Ubuntu 24.04 builder onto `cc-debian12` (`GLIBC_2.38`); keel on `scratch` (no loader); a
  static binary on `scratch` with loader and libc but no gconv (starts, fails on the first page).
- **Bases by digest, recorded here.** A tag moves; a corpus whose base moved under it is a different
  corpus.
- **The gconv row is kept on purpose.** A static check cannot see it, and a corpus without it would
  let a green check read as "the image works".
- Rejected: reusing `static-probe/experiments.sh` as is. Its matrix answers "which base can run a
  static binary", not "which failure is this"; its build steps are reused, its rows are not.
- Does not cover: the check itself (B-30), or any Jib question (B-29).

- AC: `docs/research/image-probe/corpus/` builds all seven on a Linux host with Docker; each row has
  its base digest, the binary's sha256, the exit code and the first line of stderr, and for the
  starting rows a `/health` answer (and for the gconv row the failing page).
- Anchors: `docs/research/image-probe/`, `docs/research/static-probe/experiments.sh`,
  `keel/Dockerfile`
