---
id: B-16
title: "Correct booblik's Dispatchers.IO comment, and decide whether the thread it justifies is still wanted"
status: done
priority: P2
size: XS
stage: stage-4-parity-evidence
---

# B-16 — Correct booblik's Dispatchers.IO comment, and decide whether the thread it justifies is still wanted

`booblik/booblik-native/build.gradle.kts` states that on Kotlin/Native `Dispatchers.IO` is
`internal` — "checked by compiling against coroutines 1.11.0, not read in the documentation, which
says otherwise" — and gives that as the reason the producer owns a thread through
`newSingleThreadContext`.

It is not internal. Compiled against coroutines 1.11.0
([research-parity §1.5](../research/research-parity.md)): with only
`import kotlinx.coroutines.Dispatchers` the compiler says *"Cannot access 'val IO': it is internal
in 'kotlinx.coroutines.Dispatchers'"*; add `import kotlinx.coroutines.IO` and it compiles for
`macosArm64` and `linuxX64`, and `withContext(Dispatchers.IO) { … }` runs. `Dispatchers.IO` on
native is an **extension property** in package `kotlinx.coroutines` and needs its own import;
without it the compiler resolves the internal member of the same name and says so, accurately, in
words that read like a platform limitation.

- **The decision and its reason.** Fix the comment first and separately. It is load-bearing
  documentation — it is cited as a platform fact, it was copied into this portfolio's divergence
  list, and it survived because "I checked by compiling" is the strongest evidence anyone offers.
  The correction costs nothing and stops the claim spreading further.
- **Whether to drop `newSingleThreadContext` is a second, open question.** `Connection` is
  blocking, and whether a blocking socket wants a dedicated thread or a dispatcher is a design
  decision about that module, not a platform one. This item does not pre-decide it; it removes the
  false reason so the real one has to be written down or the code has to change.
- **Rejected: changing the code and the comment in one commit.** Then the diff cannot show which
  part was the mistake, and the mistake is the interesting half.
- **Does not cover** auditing every other "checked by compiling" note in the portfolio. That is
  worth doing and is not this item; the pattern to look for is a claim whose evidence is a compiler
  message rather than a run.

- AC: booblik's comment states what actually holds, with the import named; and the module either
  keeps `newSingleThreadContext` with a reason that is true, or moves to `Dispatchers.IO` and the
  producer's tests still pass on `linuxX64` and `macosArm64`.
- Anchors: `booblik/booblik-native/build.gradle.kts`,
  `booblik/booblik-native/src/commonMain`, `docs/research/research-parity.md`

Settles hypothesis H5 of the research.

## Done, 2026-09-11 — comment corrected in two places, code untouched, question written down

booblik branch `docs/dispatchers-io-is-not-internal` (`08c9bae`). The claim was in **two** places,
not one: `booblik-native/build.gradle.kts` and `Producer`'s own KDoc, where it was the stated reason
the producer owns a thread. Both now carry the correction rather than a silent edit — the mistake is
the part worth keeping.

Re-verified inside the module rather than trusting the scratch project it was first caught in: a
file in `booblik-native/src/nativeMain` using `Dispatchers.IO` with `import kotlinx.coroutines.IO`
compiles for `linuxX64` against this module's own coroutines 1.11.0. `ktlintCheck` passes.

**`newSingleThreadContext` was left in place, as this item said it would be.** The question is now
stated where the code is, with what it turns on:

- *for the thread* — this is an actor with one consumer and one socket, and a dedicated thread makes
  that structural rather than incidental;
- *against it* — `Dispatchers.IO` is a growing pool meant for exactly this, and switching would
  delete the `close()` contract, which is the one thing a caller of this class can get wrong and the
  one that leaks when they do.

Nobody has measured either, so nothing moved. H5 is therefore *narrowed*, not closed: the platform
half is settled, the design half is a booblik decision and belongs in booblik's backlog.
