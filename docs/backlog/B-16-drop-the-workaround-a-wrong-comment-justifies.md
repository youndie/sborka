---
id: B-16
title: "Correct booblik's Dispatchers.IO comment, and decide whether the thread it justifies is still wanted"
status: open
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
