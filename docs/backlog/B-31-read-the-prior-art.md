---
id: B-31
title: "Read melange's SCA and container-structure-test against the corpus: does anything already fail this build?"
status: done
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

## Done, 2026-09-30 — nothing fails this build without a list; the nearest tool reads the host

Three tools, one of them run on the whole corpus. Per row, "yes" means the tool, with no hand-written
list and no daemon at check time, would have failed the build naming the file Docker named.

| Row | melange SCA + apko | container-structure-test | `lddtree -R` | this strand's check (B-30) |
|---|---|---|---|---|
| r1 starts | n/a | n/a | found all, exit 0 | loads |
| r2 no `libgcc_s` | no | no | names it, **exit 0** | yes |
| r3 no `libz` (cc-debian12) | no | no | **every library "not found"**, exit 0 | yes |
| r4 no `libcrypt` | no | no | names it, exit 0 | yes |
| r5a/r5b `GLIBC_2.38` | no | no | **every library "not found"**, exit 0 | yes |
| r6 no loader | no | no | every library "not found", the interpreter printed as if present, exit 0 | yes |
| r7b gconv | no | no | no | no — the declared limit |

- **melange's SCA** (`pkg/sca/sca.go` at v0.61.1, read): turns a built package's `PT_INTERP` and
  `NEEDED` into `so:<name>` runtime dependencies, and versions into `so-ver:` from *package*
  metadata, which apk's solver satisfies from a repository. It never looks at an image's files, and
  never compares `VERNEED` with `VERDEF`. It answers the question for an image apko builds from Wolfi
  packages; a Gradle-built binary on a distroless base is not a package and that base is not a
  repository. Not run: there is nothing in the corpus it applies to.
- **container-structure-test** (README at v1.22.1, read): the `tar` driver needs no daemon, and
  `fileExistenceTests` would catch any row — for the path someone wrote down. That is exactly the
  expected list [B-21](B-21-print-what-the-binary-declares.md) refused to become a gate. Not run for
  the same reason: a test that passes because I typed the right path measures my typing.
- **lddtree** (run, [results/2026-09-30-lddtree.txt](../research/image-probe/results/2026-09-30-lddtree.txt)):
  the nearest thing, and three ways short of a gate. It exits 0 on every row, `not found` included —
  a build step would have to grep its tree. It fails every library on the cc-debian12 rows, where the
  base's interpreter is an absolute symlink: resolved against the checking container rather than the
  image's root, which is the one thing a check of an image must not do. And it has no notion of
  symbol versions, so r5 could not be caught even on a base it reads correctly. It also needs the
  image flattened first (`docker export`), i.e. a daemon or another tool.

**For B-32:** "nobody checks NEEDED against the base" holds in the form that matters — nothing fails
a build, from a registry digest, without a list. lddtree is the honest nearest neighbour, and the
corpus is what separates this check from it: r3 and r5 on an absolute-symlink base, versions, and
exit codes.
