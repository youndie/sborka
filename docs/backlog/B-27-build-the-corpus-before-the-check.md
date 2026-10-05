---
id: B-27
title: "Build the corpus of images that do and do not start, before a line of the check exists"
status: done
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

## Done, 2026-09-29 — ten runs, every one as predicted

`docs/research/image-probe/corpus.sh`, run on a Linux x86_64 host with Docker 29.1.3; the output is
[results/2026-09-29-corpus.txt](../research/image-probe/results/2026-09-29-corpus.txt), the bases
are in `image-probe/bases.lock`, keel at `f79f22d`.

| Row | Base | Binary | Docker's verdict |
|---|---|---|---|
| r1 | cc-debian13 | keel | serves: `/health/ready` 200, SIGTERM → exit 0 |
| r2 | base-debian13 | keel | 127, `libgcc_s.so.1: cannot open shared object file` |
| r3 | cc-debian12 | curl probe | 127, `libz.so.1: cannot open shared object file` |
| r4 | cc-debian13 | probe, no `--as-needed` | 127, `libcrypt.so.1: cannot open shared object file` |
| r5a | cc-debian12 + `libcrypt.so.1` from ubuntu 26.04 | probe, no `--as-needed` | 1, `` version `GLIBC_2.38' not found (required by …/libcrypt.so.1) `` |
| r5b | cc-debian12 + `libcrypt.so.1` from ubuntu 24.04 | same | 1, the same line |
| r6 | scratch | keel | 255, `exec /app/keel: no such file or directory` |
| r7a | scratch + loader + every NEEDED entry from cc-debian13 | probe | 0, `probe: loaded` |
| r7b | the same image | `probe iconv` | 3, `iconv UTF-8 -> UTF-16LE refused, errno=22` |
| r7c | cc-debian13 (control) | `probe iconv` | 0, `iconv … ok` |

What the table says beyond "the predictions held":

- **r6 names the wrong file.** The binary is there; the loader it asks for is not. `no such file or
  directory` about a path that exists is the least helpful message in the corpus, and it is the one
  a static check can replace with the right name.
- **r5b: the builder keel uses today would reproduce the copied-library failure too.** Ubuntu 24.04's
  `libcrypt.so.1` also wants `GLIBC_2.38` — so the incident was not about 26.04, and the rule "the
  builder's glibc must be no newer than the runtime's" applies to every copy out of the current
  builder onto `cc-debian12`. keel no longer copies anything ([B-22](B-22-take-the-flag-into-the-two-images.md)),
  which is why this row is a probe and not keel.
- **r7a/r7b are the check's honest limit, measured.** The image satisfies every NEEDED entry and the
  loader, so a load check must call it good — and it is, until glibc needs a module it opens with
  `dlopen`. r7c shows the call itself is fine where gconv exists.

Where the corpus departs from the brief's row list, and why:

- **r4 and r5 use the probe, not keel linked without `--as-needed`.** keel takes `--as-needed` from
  sborka.kmp and has no switch to drop it; the probe links the same runtime with the toolchain's
  default ten NEEDED entries, which is the question the row asks (static-probe measured that list as
  the runtime's, not the application's).
- **The blind spot is a direct `iconv_open`, not a Ktor page.** The page was how the failure was
  first met; the call is the smallest thing that reproduces it, and it does not depend on which Ktor
  version still routes a page through `iconv`.
- **r5 was run from two donors**, since which builder reproduces it was a question rather than a fact.
