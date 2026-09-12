---
id: platform-probe
title: platform-probe — the assertions a build makes about its own platform
type: service
module: platform-probe
tech_stack: [Kotlin Multiplatform, ktor-network, kotlinx-coroutines, kotlinx-io]
owner: unassigned
depends_on: []
publishes: [io.github.youndie.sborka:platform-probe]
---

# platform-probe

## 1. Responsibility

The assertions a repository puts on `commonTest` so that its build finds out, **on every target it
declares**, whether the platform underneath does what the code assumes: that a hostname resolves and
a socket connects, that the environment can be read, that a file survives a round trip, that
`Dispatchers.IO` dispatches.

It exists because of a measurement rather than a principle. Of the divergences this portfolio has
paid for, none was in the standard library
([research-parity §1.5](../research/research-parity.md)) — they were a socket that would not resolve
a name on Kotlin/Native, a client engine with no TLS, a Ktor plugin published for one target, and a
dispatcher that was never missing at all. A gate built out of standard-library behaviour would have
been green through every one.

**Why it is a published library and not a convention.** A convention configures a build; these have
to compile and run inside it. Generating the source into each consumer was the cheaper idea and it
does not work: the assertions go through **ktor** rather than through the syscall underneath, and a
generated source cannot bring a dependency. Going through ktor is the whole point — the hostname
failure was in ktor's own `InetSocketAddress` while every syscall below it worked, so a probe one
layer down would have passed while production did not.

## 2. Code anchors

| What | Code |
|---|---|
| the assertions and the report | `platform-probe/src/commonMain/kotlin/io/github/youndie/sborka/probe/PlatformProbe.kt` |
| the two actuals | `platform-probe/src/jvmMain/…/PlatformProbe.jvm.kt`, `platform-probe/src/nativeMain/…/PlatformProbe.native.kt` |
| the probe probing itself, on every target | `platform-probe/src/commonTest/kotlin/io/github/youndie/sborka/probe/PlatformProbeTest.kt` |
| targets, dependencies and publication | `platform-probe/build.gradle.kts` |
| the completeness check over what it publishes | `build.gradle.kts`, `verifyBuildLogicPublications` |

## 3. How a consumer uses it

```kotlin
commonTest.dependencies {
    implementation("io.github.youndie.sborka:platform-probe:<version>")
}
```

```kotlin
@Test
fun thePlatformDoesWhatThisServiceAssumes() = runBlocking {
    probePlatform(host = "postgres", port = 5432).orFail()
}
```

`host` is the repository's own stand, not a public address: a probe that needs the internet goes red
on an outage somebody else had, and then gets switched off.

## 4. Quirks

- **It publishes five coordinates, not one.** The root module plus one per target
  (`-jvm`, `-linuxx64`, `-linuxarm64`, `-macosarm64`). A consumer on a target whose variant did not
  publish fails to resolve while the root module sits on the server looking complete — which is
  version 0.1.0.3's defect one level down, and why all five are in the completeness check.
- **A target added here and not to `nativeProbeVariants` in the root build publishes unchecked.**
  The check was confirmed to fail on a variant that is not there before it was trusted.
- **The report names what it did not cover**, and that is load-bearing rather than polite: TLS
  through the engine a repository pins, the Ktor plugins it pins, and standard-library behaviour are
  all outside it. A green report that does not say where it stopped looking accumulates trust it has
  not earned.
- **`runBlocking`, never `runTest`.** The test dispatcher's clock is virtual, so a timeout around a
  socket fires before the socket has done anything, and the result reads as a platform verdict when
  it is a harness one. That mistake was made once while this module was written.
- **Four targets, because four are what the portfolio's services declare.** Others are not excluded
  on principle; they are excluded because nothing here has run them.
- **Not in proba's coordinate list yet.** `catalog` and `kapkan` are both out of it because proba
  reports UNDETERMINED for artefacts nobody compiles against, which is a red job saying nothing.
  Whether a multiplatform root module resolves cleanly in proba's consumer build has not been
  tested, and guessing would put a red job on `main`.
