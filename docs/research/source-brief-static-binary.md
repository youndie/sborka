---
id: source-brief-static-binary
title: Brief A as it arrived — a Kotlin/Native binary in a scratch image
type: research
status: active
date: 2026-09-11
---

# Source brief: a Kotlin/Native binary in a `scratch` image

Kept verbatim, as the input rather than a finding.
[research-static-binary](research-static-binary.md) §3 lists where it turned out to be asking for
something the toolchain does not support, and that comparison is only readable while the original
text is here to compare against.

It arrived as one of a pair; the other half is [source-brief-parity](source-brief-parity.md), which
was taken first on the brief's own advice.

---

# Two research briefs

Both are time-boxed investigations, not projects. Each ends with a document in `docs/research/`
whatever the colour of the result, and a red result is a deliverable: a list of symbols, a ticket
with a log, or a page saying "not today, here is why".

Subjects are the Kotlin/Native services that already exist as binaries (shildik, booblik, telek,
tracy, metrik, katcher, razves CLI). konekt is JVM and takes no part in either brief.


## Brief A — A Kotlin/Native binary in a `scratch` image

### Question

Can a Kotlin/Native `linux_x64` / `linux_arm64` service run in an image with nothing else in it,
and what does it cost when it can?

### Why it matters

Go's deployment story is `FROM scratch` and a 10 MB image. A Kotlin/Native binary links glibc
dynamically, so today the honest base is `debian-slim` or `distroless/base`. The gap is not the
language, it is the linker configuration — or it is the runtime, and then it is a JetBrains
ticket. Nobody has written down which.

### Non-goals

- Windows, macOS, Alpine-as-a-build-host. Linux targets only, built on Linux.
- Reducing binary size (razves covers that); this brief is about what the binary needs at runtime.
- Any service that uses `ktor-client-curl`: libcurl is a dynamic dependency by design and is out of
  scope here. Note which subjects are excluded by this and why.

### Research questions, with pre-declared outcomes

**RQ0 — Inventory.** What does each subject binary actually need at runtime?
`ldd`, `readelf -d`, `nm -D --undefined-only`, per binary, both targets.
Deliverable: a table — shared objects, and the glibc symbols with a version suffix (`GLIBC_2.34`
etc.). No colour; this is the input to everything below.

**RQ1 — Baseline.** Does the unmodified binary run in `gcr.io/distroless/base` (glibc present) and
fail in `gcr.io/distroless/static` and `scratch`?
- Green: base works, static and scratch fail on a missing loader (`no such file or directory`).
  This is the expected result and the control.
- Anything else: stop and understand it before going on.

**RQ2 — Static glibc.** `-linker-option -static` (and `-static-libstdc++`, `-static-libgcc` as
needed).
- Green: builds, starts in `scratch`, answers `/health`, and — the part that usually breaks —
  resolves a hostname (`postgres` on the compose network) and connects. NSS under static glibc is
  the known failure; the test must include a DNS lookup, not just a listen.
- Red: builds but DNS fails → record the exact error, mark glibc-static as unusable for services,
  move on.
- Prediction: red on DNS.

**RQ3 — musl.** Link against a musl sysroot, two routes tried in order:
1. `-Xoverride-konan-properties` pointing `targetSysRoot`/`linkerKonanFlags` at a musl toolchain;
2. `zig cc` as the linker (`-linker-option` / `KONAN_LINKER`), which brings musl with it.
- Green: builds statically, passes the same checks as RQ2 (health, DNS, database round-trip).
- Red: link fails on symbols the Kotlin/Native runtime takes from glibc. Deliverable is the
  symbol list itself — that list is the JetBrains ticket, and it is more useful than a working
  binary because it says what has to change upstream.
- Prediction: links with a handful of missing symbols (`pthread_getattr_np`,
  `__cxa_thread_atexit_impl` or similar); one route may work with a shim.

**RQ4 — Runtime cost of what works.** For every variant that passed RQ2/RQ3, on the stand, in
alternation, three rounds per point, the same k6 scenario used for the service's own page:
- Binary size (razves, by module — the static libc shows up as its own row).
- RSS at readiness and under load.
- Throughput and p95. musl's allocator is the known risk here; Kotlin/Native's own allocator
  (`-Xallocator`) may or may not take it out of the path — measure both settings.
- Green: within the run-to-run spread of the dynamic build.
- Red: throughput or p95 worse by more than the spread → the recipe is documented with that
  number next to it, not hidden.

**RQ5 — What the image buys.** Image size and cold pull-plus-start on a k0s node with an empty
cache: `scratch` vs `distroless/static` vs `distroless/base` vs `debian-slim`.
- This is the number the whole brief is for. If a 12 MB image starts 300 ms faster than a 90 MB
  one on a cold node, that is the sentence on the page.

### Kill criteria

- RQ3 red on both routes with more than ~10 missing symbols and no shim that holds → stop,
  write the ticket, publish the symbol table. Three days is the box; do not spend a week on a
  linker.
- RQ4 red by more than 20 % on throughput with no allocator setting that recovers it → the recipe
  is not recommended; publish anyway.

### Deliverables

- `docs/research/research-static-binary.md`: the tables, the recipes that worked, the errors
  that did not, verbatim.
- If green: an `image { base = scratch }` option in sborka, and a Dockerfile in the starter.
- If red: a KT ticket with the symbol list and a link to the log; one paragraph on the site
  saying what the honest base image is today and why.

